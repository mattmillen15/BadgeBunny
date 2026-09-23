package com.badgebunny.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import com.badgebunny.log.BbLog
import com.badgebunny.transport.ByteStreamPipe
import com.badgebunny.transport.CardhopperCodec
import com.badgebunny.transport.Pm3Link
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE GATT transport to a Proxmark5 with BWM (Bluetooth Wireless Module, ESP32-C2).
 * The BWM bridges UART4/app_com ↔ BLE using a custom GATT service (0xAE86) with a single
 * bidirectional data characteristic (0xAE88: WRITE + WRITE_NO_RSP + NOTIFY).
 *
 * Contrast with Pm3Bluetooth, which uses Bluetooth Classic SPP/RFCOMM (RDV4 + BlueShark).
 */
@SuppressLint("MissingPermission")
class Pm3Ble(private val context: Context) : Pm3Link {

    companion object {
        private const val T = "BB.BLE"
        // PM5 BWM custom GATT profile (NOT Nordic UART Service)
        val BWM_SERVICE: UUID = UUID.fromString("0000AE86-0000-1000-8000-00805F9B34FB")
        val BWM_DATA: UUID = UUID.fromString("0000AE88-0000-1000-8000-00805F9B34FB")
        val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_MTU = 247
        private const val TIMEOUT_SEC = 12L
        private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }
    }

    private var gatt: BluetoothGatt? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var codec: CardhopperCodec? = null
    private val pipe = ByteStreamPipe()
    @Volatile private var mtu = 23
    @Volatile private var connected = false

    private var setupLatch = CountDownLatch(1)
    private var writeLatch: CountDownLatch? = null

    @Suppress("DEPRECATION")
    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            BbLog.d(T, "onConnectionStateChange status=$status state=$state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                // NB: do NOT request CONNECTION_PRIORITY_HIGH here — the PM5's ESP32 BWM cannot hold
                // the aggressive ~7.5ms interval and the link times out (onConnectionStateChange
                // status=8) ~15s in. The default (balanced) interval is what completed the full Seos
                // exchange reliably. Stability beats the few ms of latency.
                g.requestMtu(TARGET_MTU)
            } else {
                BbLog.w(T, "BLE disconnected (status=$status)")
                connected = false
                pipe.shutdown()
                setupLatch.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            BbLog.d(T, "MTU negotiated: $mtu (requested $TARGET_MTU)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            BbLog.d(T, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) { BbLog.e(T, "service discovery failed"); setupLatch.countDown(); return }
            val svc = g.getService(BWM_SERVICE)
            if (svc == null) {
                BbLog.e(T, "BWM service 0xAE86 NOT FOUND — services: ${g.services.map { it.uuid }}")
                setupLatch.countDown(); return
            }
            val ch = svc.getCharacteristic(BWM_DATA)
            if (ch == null) { BbLog.e(T, "BWM data char 0xAE88 not found"); setupLatch.countDown(); return }
            BbLog.d(T, "BWM service+char found, enabling notifications")
            dataChar = ch
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCC)
            if (d == null) {
                BbLog.d(T, "no CCC descriptor, assuming notifications enabled")
                connected = true; setupLatch.countDown(); return
            }
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            connected = status == BluetoothGatt.GATT_SUCCESS
            BbLog.d(T, "CCC descriptor write status=$status connected=$connected")
            setupLatch.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            BbLog.d(T, "BLE rx ${v.size}B: ${v.hex()}")
            pipe.feed(v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            BbLog.d(T, "BLE rx ${v.size}B: ${v.hex()}")
            pipe.feed(v)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            BbLog.d(T, "BLE tx done status=$status")
            writeLatch?.countDown()
        }
    }

    @Throws(Exception::class)
    fun connect(device: BluetoothDevice) {
        BbLog.d(T, "connect() to ${device.address}")
        setupLatch = CountDownLatch(1)
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        // On any failure, tear the GATT down before throwing. A leaked half-open connection keeps
        // the peripheral (BWM) from advertising again, which looks like the device "disappearing".
        if (!setupLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) { closeGatt(); throw IOException("BLE connect timed out") }
        if (!connected) { closeGatt(); throw IOException("BLE setup failed (BWM service 0xAE86 not found?)") }
        BbLog.d(T, "BLE connected, MTU=$mtu, triggering standalone mode")
        bleWrite(Pm3Link.CMD_STANDALONE_NG)
        BbLog.d(T, "CMD_STANDALONE sent, waiting for RunMod() init")
        Thread.sleep(500)

        val out = object : OutputStream() {
            override fun write(b: Int) = bleWrite(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) = bleWrite(b.copyOfRange(off, off + len))
        }
        codec = CardhopperCodec(pipe, out)
        BbLog.d(T, "CardhopperCodec ready")
    }

    @Suppress("DEPRECATION")
    private fun bleWrite(data: ByteArray) {
        BbLog.d(T, "BLE tx ${data.size}B: ${data.hex()}")
        val g = gatt ?: throw IOException("BLE disconnected")
        val c = dataChar ?: throw IOException("no data characteristic")
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < data.size) {
            val end = (off + chunk).coerceAtMost(data.size)
            c.value = data.copyOfRange(off, end)
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeLatch = CountDownLatch(1)
            if (!g.writeCharacteristic(c)) throw IOException("BLE write rejected")
            if (!writeLatch!!.await(TIMEOUT_SEC, TimeUnit.SECONDS)) throw IOException("BLE write timed out")
            off = end
        }
    }

    override fun isConnected(): Boolean = connected
    override fun sendFrame(payload: ByteArray) = (codec ?: error("BLE not connected")).sendFrame(payload)
    override fun recvFrame(): ByteArray = (codec ?: error("BLE not connected")).recvFrame()
    override fun drainInput() { codec?.drain() }

    // reset() uses the Pm3Link default (sends the cardhopper RESTART frame).

    /** Tear down just the GATT client (used on a failed connect so nothing leaks). */
    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; dataChar = null
    }

    override fun close() {
        connected = false
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        pipe.shutdown()
        gatt = null; dataChar = null; codec = null
    }
}
