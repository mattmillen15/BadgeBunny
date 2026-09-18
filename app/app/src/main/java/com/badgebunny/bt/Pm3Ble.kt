package com.badgebunny.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import com.badgebunny.transport.CardhopperCodec
import com.badgebunny.transport.Pm3Link
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE GATT transport to a Proxmark5 with a built-in BLE module (Nordic UART Service).
 * The BLE module bridges USART ↔ BLE transparently, so hf_cardhopper's serial framing
 * (CardhopperCodec) runs unchanged over the NUS RX/TX characteristics.
 *
 * Contrast with Pm3Bluetooth, which uses Bluetooth Classic SPP/RFCOMM (RDV4 + BlueShark).
 */
@SuppressLint("MissingPermission")
class Pm3Ble(private val context: Context) : Pm3Link {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_MTU = 247
        private const val TIMEOUT_SEC = 12L
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var codec: CardhopperCodec? = null
    private val pipeIn = PipedInputStream(1 shl 16)
    private val pipeOut = PipedOutputStream(pipeIn)
    @Volatile private var mtu = 23
    @Volatile private var connected = false

    private var setupLatch = CountDownLatch(1)
    private var writeLatch: CountDownLatch? = null

    @Suppress("DEPRECATION")
    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(TARGET_MTU)
            } else {
                connected = false
                try { pipeOut.close() } catch (_: Exception) {}
                setupLatch.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { setupLatch.countDown(); return }
            val svc = g.getService(NUS_SERVICE)
            if (svc == null) { setupLatch.countDown(); return }
            rxChar = svc.getCharacteristic(NUS_RX)
            val tx = svc.getCharacteristic(NUS_TX)
            if (rxChar == null || tx == null) { setupLatch.countDown(); return }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(CCC)
            if (d == null) {
                connected = true; setupLatch.countDown(); return
            }
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            connected = status == BluetoothGatt.GATT_SUCCESS
            setupLatch.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            try { pipeOut.write(v); pipeOut.flush() } catch (_: Exception) {}
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            try { pipeOut.write(v); pipeOut.flush() } catch (_: Exception) {}
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeLatch?.countDown()
        }
    }

    @Throws(Exception::class)
    fun connect(device: BluetoothDevice) {
        setupLatch = CountDownLatch(1)
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (!setupLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) throw IOException("BLE connect timed out")
        if (!connected) throw IOException("BLE setup failed (NUS service not found?)")

        val out = object : OutputStream() {
            override fun write(b: Int) = bleWrite(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) = bleWrite(b.copyOfRange(off, off + len))
        }
        codec = CardhopperCodec(pipeIn, out)
    }

    @Suppress("DEPRECATION")
    private fun bleWrite(data: ByteArray) {
        val g = gatt ?: throw IOException("BLE disconnected")
        val c = rxChar ?: throw IOException("no RX characteristic")
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < data.size) {
            val end = (off + chunk).coerceAtMost(data.size)
            c.value = data.copyOfRange(off, end)
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeLatch = CountDownLatch(1)
            if (!g.writeCharacteristic(c)) throw IOException("BLE write rejected")
            if (!writeLatch!!.await(TIMEOUT_SEC, TimeUnit.SECONDS)) throw IOException("BLE write timed out")
            off = end
        }
    }

    override fun isConnected(): Boolean = connected
    override fun sendFrame(payload: ByteArray) = (codec ?: error("BLE not connected")).sendFrame(payload)
    override fun recvFrame(): ByteArray = (codec ?: error("BLE not connected")).recvFrame()

    override fun close() {
        connected = false
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        try { pipeOut.close() } catch (_: Exception) {}
        gatt = null; rxChar = null; codec = null
    }
}
