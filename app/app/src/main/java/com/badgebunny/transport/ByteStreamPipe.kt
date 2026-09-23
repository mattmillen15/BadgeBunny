package com.badgebunny.transport

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * Thread-safe byte FIFO exposed as an InputStream.
 *
 * Async serial RX (the USB SerialInputOutputManager, or BLE notifications) calls [feed]; the
 * blocking CardhopperCodec reader consumes it through the InputStream API. Unlike
 * PipedInputStream/PipedOutputStream there is no per-thread "read end" that dies when a relay
 * worker thread exits — so the same pipe keeps delivering bytes across successive relay sessions
 * instead of silently dropping the next byte (which stalled sendFrame's ACK wait forever).
 */
class ByteStreamPipe : InputStream() {
    private val q = LinkedBlockingQueue<Int>()
    @Volatile private var closed = false

    /** Feed received bytes from the transport callback thread. */
    fun feed(data: ByteArray) {
        if (closed) return
        for (b in data) q.offer(b.toInt() and 0xFF)
    }

    /** Signal end-of-stream so blocked readers unblock with -1. */
    fun shutdown() {
        closed = true
        q.offer(EOF)
    }

    override fun read(): Int {
        val v = q.take()               // blocks; interruptible
        if (v == EOF) { q.offer(EOF); return -1 }
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        val first = q.take()           // block for at least one byte
        if (first == EOF) { q.offer(EOF); return -1 }
        b[off] = first.toByte()
        var n = 1
        while (n < len) {
            val v = q.poll() ?: break   // drain what's ready without blocking
            if (v == EOF) { q.offer(EOF); break }
            b[off + n] = v.toByte()
            n++
        }
        return n
    }

    override fun available(): Int = q.size

    companion object { private const val EOF = -1 }
}
