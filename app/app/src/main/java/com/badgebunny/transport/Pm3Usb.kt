package com.badgebunny.transport

import android.hardware.usb.UsbManager
import com.badgebunny.log.BbLog
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

class Pm3Usb(
    private val manager: UsbManager,
    private val driver: UsbSerialDriver,
    private val portIndex: Int
) : Pm3Link {

    companion object {
        private const val T = "BB.USB"
        const val WRITE_TIMEOUT = 2000
    }

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var codec: CardhopperCodec? = null
    private var pipe = ByteStreamPipe()
    @Volatile private var ioAlive = false

    @Throws(Exception::class)
    fun open() {
        try { io?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        pipe.shutdown()
        io = null; port = null; codec = null

        pipe = ByteStreamPipe()

        val dev = driver.device
        BbLog.d(T, "open: vid=%04x pid=%04x ports=${driver.ports.size} portIndex=$portIndex".format(dev.vendorId, dev.productId))
        val conn = manager.openDevice(dev) ?: throw IOException("USB openDevice failed (permission?)")
        val p = driver.ports[portIndex]
        p.open(conn)
        p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try { p.dtr = true; p.rts = true } catch (_: Exception) {}
        port = p
        BbLog.d(T, "port open, DTR/RTS set, 115200/8N1")

        startIoManager(p)

        val out = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                // A write timeout (rc=-1) means the PM3 stopped draining its USB RX (firmware busy
                // in a card cycle). Mark the link dead so the relay reconnects — reopening the port
                // resets the CDC endpoint and re-arms standalone, instead of spinning on a stuck write.
                try { port?.write(b.copyOfRange(off, off + len), WRITE_TIMEOUT) }
                catch (e: Exception) { ioAlive = false; throw e }
            }
        }
        BbLog.d(T, "sending CMD_STANDALONE_NG (${Pm3Link.CMD_STANDALONE_NG.size}B)")
        out.write(Pm3Link.CMD_STANDALONE_NG)
        BbLog.d(T, "waiting 500ms for RunMod() init…")
        Thread.sleep(500)
        val avail = pipe.available()
        if (avail > 0) { pipe.read(ByteArray(avail)); BbLog.d(T, "drained ${avail}B post-init") }
        BbLog.d(T, "CardhopperCodec ready")
        codec = CardhopperCodec(pipe, out)
    }

    private fun startIoManager(p: UsbSerialPort) {
        ioAlive = true
        val sink = pipe
        val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                BbLog.d(T, "USB rx ${data.size}B")
                sink.feed(data)
            }
            override fun onRunError(e: Exception) {
                BbLog.e(T, "USB IO error: ${e.message}")
                ioAlive = false
                sink.shutdown()
            }
        })
        Executors.newSingleThreadExecutor().submit(mgr)
        io = mgr
    }

    // reset() uses the Pm3Link default (sends the cardhopper RESTART frame).

    override fun isConnected(): Boolean = port != null && ioAlive
    override fun sendFrame(payload: ByteArray) = (codec ?: error("USB not connected")).sendFrame(payload)
    override fun recvFrame(): ByteArray = (codec ?: error("USB not connected")).recvFrame()
    override fun drainInput() { codec?.drain() }
    override fun close() {
        try { io?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        pipe.shutdown()
        port = null; codec = null; io = null; ioAlive = false
    }
}
