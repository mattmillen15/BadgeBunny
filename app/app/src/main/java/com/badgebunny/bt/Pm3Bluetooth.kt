package com.badgebunny.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.badgebunny.log.BbLog
import com.badgebunny.transport.CardhopperCodec
import com.badgebunny.transport.Pm3Link
import java.util.UUID

/**
 * Bluetooth-Classic SPP/RFCOMM link to a Proxmark3 + BlueShark running hf_cardhopper (the RDV4).
 * The cardhopper framing lives in the shared CardhopperCodec; this just owns the RFCOMM socket.
 */
@SuppressLint("MissingPermission") // caller ensures BLUETOOTH_CONNECT is granted
class Pm3Bluetooth : Pm3Link {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var codec: CardhopperCodec? = null

    @Throws(Exception::class)
    fun connect(device: BluetoothDevice) {
        val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        BbLog.d("BB.BT", "RFCOMM connected, triggering standalone mode")
        s.outputStream.write(Pm3Link.CMD_STANDALONE_NG)
        s.outputStream.flush()
        Thread.sleep(500)
        BbLog.d("BB.BT", "CardhopperCodec ready")
        codec = CardhopperCodec(s.inputStream, s.outputStream)
    }

    override fun isConnected(): Boolean = socket?.isConnected == true
    override fun sendFrame(payload: ByteArray) = (codec ?: error("BT not connected")).sendFrame(payload)
    override fun recvFrame(): ByteArray = (codec ?: error("BT not connected")).recvFrame()
    override fun drainInput() { codec?.drain() }
    // reset() uses the Pm3Link default (sends the cardhopper RESTART frame).

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; codec = null
    }
}
