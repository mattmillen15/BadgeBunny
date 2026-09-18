package com.badgebunny.transport

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
 * Ground-truth: armsrc/Standalone/hf_cardhopper.c (read_packet / write_packet).
 */
class CardhopperCodec(private val input: InputStream, private val output: OutputStream) {

    companion object {
        const val ACK = 0xFE
        const val ERR_FIRST = 0xFF
    }

    @Throws(Exception::class)
    fun sendFrame(payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= 255) { "bad frame len ${payload.size}" }
        val buf = ByteArray(payload.size + 1)
        buf[0] = payload.size.toByte()
        System.arraycopy(payload, 0, buf, 1, payload.size)
        output.write(buf)
        output.flush()
        when (val a = readByte()) {
            ACK -> {}
            ERR_FIRST -> { readFully(ByteArray(3)); throw IOException("PM3 signalled ERR") }
            else -> throw IOException("PM3 bad ACK 0x%02X".format(a))
        }
    }

    @Throws(Exception::class)
    fun recvFrame(): ByteArray {
        val len = readByte()
        val payload = ByteArray(len)
        if (len > 0) readFully(payload)
        return payload
    }

    @Throws(Exception::class)
    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw IOException("PM3 stream closed")
        return b and 0xFF
    }

    @Throws(Exception::class)
    private fun readFully(dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw IOException("PM3 stream closed")
            off += n
        }
    }
}
