package com.badgebunny.transport

import com.badgebunny.log.BbLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Cardhopper wire framing over any serial byte stream (Bluetooth SPP or USB-CDC).
 *
 *   frame = [len:1][payload:len],  len = 1..255
 *   after each HOST->PM3 frame the PM3 replies 0xFE (ACK), or 0xFF "ERR" on overrun.
 *   PM3->HOST frames are not acked.
 *
 * The PM3/PM5 sends PacketResponseNG debug/status packets (from DbpString) asynchronously on the
 * same serial channel. Those carry the device->host RESPONSE magic "PM3b" (50 4D 33 62) — NOT the
 * host->device command magic "PM3a" (…61). Both sendFrame (ACK scan) and recvFrame detect and skip
 * them by their 4-byte magic header, mirroring the firmware's own PM3-NG filter in read_packet().
 */
class CardhopperCodec(private val input: InputStream, private val output: OutputStream) {

    companion object {
        private const val T = "BB.CH"
        const val ACK = 0xFE
        const val ERR_FIRST = 0xFF
        // PacketResponseNG preamble magic 0x62334D50 -> little-endian bytes 50 4D 33 62 ("PM3b").
        private const val NG_P = 0x50   // 'P'
        private const val NG_M = 0x4D   // 'M'
        private const val NG_3 = 0x33   // '3'
        private const val NG_B = 0x62   // 'b'  (response; the old 0x61 'a' was the command magic)
        private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }
    }

    @Throws(Exception::class)
    fun sendFrame(payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= 255) { "bad frame len ${payload.size}" }
        BbLog.d(T, "sendFrame ${payload.size}B: ${payload.hex()}")

        val stale = input.available()
        if (stale > 0) {
            input.read(ByteArray(stale))
            BbLog.d(T, "pre-send drain: ${stale}B")
        }

        val buf = ByteArray(payload.size + 1)
        buf[0] = payload.size.toByte()
        System.arraycopy(payload, 0, buf, 1, payload.size)
        output.write(buf)
        output.flush()
        BbLog.d(T, "waiting for ACK…")

        val deadline = System.currentTimeMillis() + 500
        var skipped = 0
        while (System.currentTimeMillis() < deadline) {
            // Poll rather than block on readByte(): a bare read() blocks past the deadline, so a
            // missing ACK would hang forever instead of timing out.
            if (input.available() == 0) { Thread.sleep(5); continue }
            val a = readByte()
            when {
                a == ACK -> {
                    if (skipped > 0) BbLog.d(T, "ACK received (skipped $skipped stale bytes)")
                    else BbLog.d(T, "ACK received")
                    return
                }
                a == NG_P -> {
                    val m1 = readByte(); val m2 = readByte(); val m3 = readByte()
                    if (m1 == NG_M && m2 == NG_3 && m3 == NG_B) skipNgBody()
                    skipped++
                }
                a == ERR_FIRST && skipped == 0 -> {
                    readFully(ByteArray(3)); throw IOException("PM3 signalled ERR")
                }
                else -> skipped++
            }
        }
        throw IOException("PM3 ACK timeout (skipped $skipped bytes)")
    }

    /** Read and discard everything the PM3 has queued, until the stream is quiet for [quietMs].
     *  Used before a reset to clear leftover bytes from an aborted session (which otherwise
     *  desync the next session's framing). Hard-capped so a chatty/looping firmware can't hang us. */
    fun drain(quietMs: Long = 250, hardCapMs: Long = 2500) {
        val hardDeadline = System.currentTimeMillis() + hardCapMs
        var lastData = System.currentTimeMillis()
        val buf = ByteArray(512)
        var total = 0
        while (System.currentTimeMillis() < hardDeadline &&
               System.currentTimeMillis() - lastData < quietMs) {
            val avail = try { input.available() } catch (_: Exception) { 0 }
            if (avail > 0) {
                val n = try { input.read(buf, 0, minOf(avail, buf.size)) } catch (_: Exception) { -1 }
                if (n > 0) { total += n; lastData = System.currentTimeMillis() }
                else break
            } else {
                try { Thread.sleep(10) } catch (_: Exception) {}
            }
        }
        if (total > 0) BbLog.d(T, "drain: discarded ${total}B")
    }

    @Throws(Exception::class)
    fun recvFrame(): ByteArray {
        BbLog.d(T, "recvFrame: waiting for len byte…")
        while (true) {
            val len = readByte()
            if (len == NG_P) {
                val m1 = readByte(); val m2 = readByte(); val m3 = readByte()
                if (m1 == NG_M && m2 == NG_3 && m3 == NG_B) {
                    skipNgBody()
                    continue
                }
                val payload = ByteArray(len)
                payload[0] = m1.toByte()
                payload[1] = m2.toByte()
                payload[2] = m3.toByte()
                readFully(payload, 3, len - 3)
                BbLog.d(T, "recvFrame ${len}B: ${payload.hex()}")
                return payload
            }
            if (len == ERR_FIRST) {
                // 0xFF is NEVER a valid frame length (max is MAX_FRAME_SIZE-2 = 254). It is either
                // the firmware's magicERR "\xffERR" or a corrupt length byte. If the next byte is
                // 'E' (0x45), it's a real ERR — return the 4-byte marker so RelayEngine's ERR logic
                // handles it. Otherwise the length is corrupt (the 255-byte garbage-frame bug), so
                // bail cleanly to recycle the session instead of reading 255 stale bytes into the card.
                val n1 = readByte()
                if (n1 == 0x45) {
                    readByte(); readByte() // consume "RR"
                    BbLog.d(T, "recvFrame: PM3 ERR (\\xffERR)")
                    return byteArrayOf(0xFF.toByte(), 0x45, 0x52, 0x52)
                }
                throw IOException("corrupt frame length 0xFF (next=0x${"%02X".format(n1)}) — recycling")
            }
            val payload = ByteArray(len)
            if (len > 0) readFully(payload)
            BbLog.d(T, "recvFrame ${len}B: ${payload.hex()}")
            return payload
        }
    }

    // The 4-byte magic (50 4D 33 62) is already consumed. PacketResponseNG preamble remainder is
    //   length:15 | ng:1  (2 bytes LE) · status (2) · cmd (2)
    // followed by `length` data bytes and a 2-byte CRC/postamble. Consume exactly that.
    private fun skipNgBody() {
        val lenLo = readByte(); val lenHi = readByte()
        val dataLen = (lenLo or (lenHi shl 8)) and 0x7FFF   // top bit is the ng flag, not length
        val rest = 2 /*status*/ + 2 /*cmd*/ + dataLen + 2 /*crc*/
        readFully(ByteArray(rest))
        BbLog.d(T, "skipped NG response (${4 + 2 + rest}B, data=$dataLen)")
    }

    @Throws(Exception::class)
    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw IOException("PM3 stream closed")
        return b and 0xFF
    }

    @Throws(Exception::class)
    private fun readFully(dst: ByteArray, off: Int = 0, len: Int = dst.size - off) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val n = input.read(dst, pos, end - pos)
            if (n < 0) throw IOException("PM3 stream closed")
            pos += n
        }
    }
}
