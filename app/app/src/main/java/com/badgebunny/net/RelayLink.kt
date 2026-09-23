package com.badgebunny.net

import com.badgebunny.log.BbLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Phone-to-phone relay link. One side listens (server), the other connects.
 * Intended over Tailscale (WireGuard) direct P2P or plain LAN; TCP_NODELAY on.
 *
 * Message framing: [type:1][len:2 big-endian][payload:len].
 * Protocol order: reader sends UID then ATS; then a symmetric APDU ping-pong.
 */
class RelayLink {

    companion object {
        private const val T = "BB.NET"
        const val UID = 0x01
        const val ATS = 0x02
        const val APDU = 0x03
        const val PING = 0x10   // preflight latency probe (connector -> listener)
        const val PONG = 0x11   // preflight echo / verdict (listener -> connector, or median payload)
        const val ERR = 0xFF

        const val PREFLIGHT_ROUNDS = 7
    }

    data class Msg(val type: Int, val payload: ByteArray)

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var din: DataInputStream? = null
    private var dout: DataOutputStream? = null

    /** Bind the listening socket only. Split from accept() so the caller can open the port
     *  BEFORE doing anything slow (e.g. resetting a flaky PM3 over USB) — otherwise the peer's
     *  connect() attempts fail for as long as the reset takes and it looks like a dead tunnel. */
    @Throws(Exception::class)
    fun bindListener(port: Int) {
        BbLog.d(T, "bindListener($port)")
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
    }

    /** Block until the peer connects. Requires bindListener() first. */
    @Throws(Exception::class)
    fun acceptPeer() {
        val ss = server ?: throw IllegalStateException("listener not bound")
        ss.soTimeout = 30_000
        while (true) {
            try {
                val s = ss.accept()
                s.tcpNoDelay = true
                BbLog.d(T, "accepted connection from ${s.remoteSocketAddress}")
                bind(s)
                return
            } catch (_: SocketTimeoutException) {
                if (ss.isClosed) throw SocketTimeoutException("server closed")
            }
        }
    }

    @Throws(Exception::class)
    fun listen(port: Int) { bindListener(port); acceptPeer() }

    @Throws(Exception::class)
    fun connect(host: String, port: Int, timeoutMs: Int = 8000) {
        BbLog.d(T, "connect($host:$port)")
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        BbLog.d(T, "connected to $host:$port")
        bind(s)
    }

    private fun bind(s: Socket) {
        s.soTimeout = 30_000
        socket = s
        din = DataInputStream(s.getInputStream())
        dout = DataOutputStream(s.getOutputStream())
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    @Throws(Exception::class)
    fun sendMsg(type: Int, payload: ByteArray) {
        val o = dout ?: throw IllegalStateException("relay not connected")
        BbLog.d(T, "sendMsg type=0x${"%02X".format(type)} len=${payload.size}")
        synchronized(o) {
            o.writeByte(type)
            o.writeShort(payload.size)
            if (payload.isNotEmpty()) o.write(payload)
            o.flush()
        }
    }

    fun sendErr() { try { sendMsg(ERR, ByteArray(0)) } catch (_: Exception) {} }

    @Throws(Exception::class)
    fun recv(): Msg {
        val i = din ?: throw IllegalStateException("relay not connected")
        BbLog.d(T, "recv: waiting…")
        val type: Int
        while (true) {
            try { type = i.readUnsignedByte(); break }
            catch (_: SocketTimeoutException) { throw SocketTimeoutException("relay recv timed out") }
        }
        val len = i.readUnsignedShort()
        val payload = ByteArray(len)
        if (len > 0) i.readFully(payload)
        BbLog.d(T, "recv: type=0x${"%02X".format(type)} len=$len")
        return Msg(type, payload)
    }

    // ---------- preflight latency probe ----------
    // The Seos relay is latency-bound: the real card runs its native frame-waiting time (FWI≈9,
    // ~155 ms), which the cooked door-reader ATS does NOT extend. When the phone-to-phone round
    // trip exceeds that budget (e.g. Tailscale falling back to a DERP relay, ~200 ms) the card
    // drops the ISO-DEP session mid-auth and the transaction collapses. So before arming we measure
    // the real link RTT and refuse to relay on a link that will fail. Both ends learn the number so
    // either operator can see it.

    /** Connector side: send PREFLIGHT_ROUNDS pings, return the median round-trip in ms, and ship
     *  that median to the listener so it can display it too. */
    @Throws(Exception::class)
    fun measureRttMs(rounds: Int = PREFLIGHT_ROUNDS): Long {
        val samples = ArrayList<Long>(rounds)
        for (i in 0 until rounds) {
            val t0 = System.nanoTime()
            sendMsg(PING, ByteArray(0))
            val m = recv()
            if (m.type != PONG) throw IllegalStateException("preflight desync: expected PONG, got 0x${"%02X".format(m.type)}")
            samples.add((System.nanoTime() - t0) / 1_000_000)
        }
        samples.sort()
        val median = samples[samples.size / 2]
        val b = byteArrayOf(
            (median ushr 24).toByte(), (median ushr 16).toByte(),
            (median ushr 8).toByte(), median.toByte()
        )
        sendMsg(PONG, b) // verdict frame — tells the listener the measured median
        BbLog.d(T, "preflight median RTT=${median}ms over $rounds rounds")
        return median
    }

    /** Listener side: echo PREFLIGHT_ROUNDS pings, then read the connector's measured median (ms). */
    @Throws(Exception::class)
    fun serveRtt(rounds: Int = PREFLIGHT_ROUNDS): Long {
        for (i in 0 until rounds) {
            val m = recv()
            if (m.type != PING) throw IllegalStateException("preflight desync: expected PING, got 0x${"%02X".format(m.type)}")
            sendMsg(PONG, ByteArray(0))
        }
        val res = recv()
        val ms = if (res.type == PONG && res.payload.size == 4)
            ((res.payload[0].toLong() and 0xFF) shl 24) or ((res.payload[1].toLong() and 0xFF) shl 16) or
            ((res.payload[2].toLong() and 0xFF) shl 8) or (res.payload[3].toLong() and 0xFF)
        else -1L
        BbLog.d(T, "preflight peer median RTT=${ms}ms")
        return ms
    }

    fun close() {
        try { socket?.shutdownInput() } catch (_: Exception) {}
        try { socket?.shutdownOutput() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        socket = null; server = null; din = null; dout = null
    }
}
