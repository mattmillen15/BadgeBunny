package com.badgebunny.transport

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors

/**
 * USB-CDC transport to a Proxmark running hf_cardhopper built with -DCARDHOPPER_USB (the PM5).
 * Secondary transport; the RDV4/BlueShark Bluetooth path is primary.
 *
 * usb-serial-for-android delivers RX asynchronously, so we pump it into a PipedInputStream and
 * hand (pipe-in, port-out) to the shared CardhopperCodec — same framing as the Bluetooth path.
 * The PM5 exposes two CDC interfaces; the cardhopper comms is the higher-index one (portIndex).
 */
class Pm3Usb(
    private val manager: UsbManager,
    private val driver: UsbSerialDriver,
    private val portIndex: Int
) : Pm3Link {

    companion object { const val WRITE_TIMEOUT = 2000 }

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var codec: CardhopperCodec? = null
    private val pipeIn = PipedInputStream(1 shl 16)
    private val pipeOut = PipedOutputStream(pipeIn)

    @Throws(Exception::class)
    fun open() {
        val conn = manager.openDevice(driver.device) ?: throw IOException("USB openDevice failed (permission?)")
        val p = driver.ports[portIndex]
        p.open(conn)
        p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try { p.dtr = true; p.rts = true } catch (_: Exception) {}
        port = p

        val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                try { pipeOut.write(data); pipeOut.flush() } catch (_: Exception) {}
            }
            override fun onRunError(e: Exception) {
                try { pipeOut.close() } catch (_: Exception) {}
            }
        })
        Executors.newSingleThreadExecutor().submit(mgr)
        io = mgr

        val out = object : OutputStream() {
            override fun write(b: Int) { port?.write(byteArrayOf(b.toByte()), WRITE_TIMEOUT) }
            override fun write(b: ByteArray, off: Int, len: Int) { port?.write(b.copyOfRange(off, off + len), WRITE_TIMEOUT) }
        }
        codec = CardhopperCodec(pipeIn, out)
    }

    override fun isConnected(): Boolean = port != null
    override fun sendFrame(payload: ByteArray) = (codec ?: error("USB not connected")).sendFrame(payload)
    override fun recvFrame(): ByteArray = (codec ?: error("USB not connected")).recvFrame()
    override fun close() {
        try { io?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        try { pipeOut.close() } catch (_: Exception) {}
        port = null; codec = null; io = null
    }
}
