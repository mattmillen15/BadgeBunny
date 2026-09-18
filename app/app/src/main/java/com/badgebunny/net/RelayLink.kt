package com.badgebunny.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Phone-to-phone relay link. One side listens (server), the other connects.
 * Intended over Tailscale (WireGuard) direct P2P or plain LAN; TCP_NODELAY on.
 *
 * Message framing: [type:1][len:2 big-endian][payload:len].
 * Protocol order: reader sends UID then ATS; then a symmetric APDU ping-pong.
 */
class RelayLink {

    companion object {
        const val UID = 0x01
        const val ATS = 0x02
        const val APDU = 0x03
        const val ERR = 0xFF
    }

    data class Msg(val type: Int, val payload: ByteArray)

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var din: DataInputStream? = null
    private var dout: DataOutputStream? = null

    @Throws(Exception::class)
    fun listen(port: Int) {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        val s = ss.accept()
        s.tcpNoDelay = true
        bind(s)
    }

    @Throws(Exception::class)
    fun connect(host: String, port: Int, timeoutMs: Int = 8000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        bind(s)
    }

    private fun bind(s: Socket) {
        socket = s
        din = DataInputStream(s.getInputStream())
        dout = DataOutputStream(s.getOutputStream())
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    @Throws(Exception::class)
    fun sendMsg(type: Int, payload: ByteArray) {
        val o = dout ?: throw IllegalStateException("relay not connected")
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
        val type = i.readUnsignedByte()
        val len = i.readUnsignedShort()
        val payload = ByteArray(len)
        if (len > 0) i.readFully(payload)
        return Msg(type, payload)
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        socket = null; server = null; din = null; dout = null
    }
}
