package com.badgebunny

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.badgebunny.bt.Pm3Bluetooth
import com.badgebunny.net.RelayLink
import com.badgebunny.relay.RelayEngine
import com.badgebunny.relay.Role
import com.badgebunny.transport.Pm3Link
import com.badgebunny.transport.Pm3Usb
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val pm3Bt = Pm3Bluetooth()
    @Volatile private var link: Pm3Link? = null
    @Volatile private var engine: RelayEngine? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var net: RelayLink? = null
    @Volatile private var relayRunning = false

    private var btDevices: List<BluetoothDevice> = emptyList()
    private var usbDrivers: List<UsbSerialDriver> = emptyList()
    private val REQ = 42
    private var pendingPerm: (() -> Unit)? = null
    private var pendingUsb: (() -> Unit)? = null
    private val ACTION_USB = "com.badgebunny.USB_PERMISSION"

    private lateinit var spinnerDevices: Spinner
    private lateinit var spinnerCard: Spinner
    private lateinit var txtPm3: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtMyIps: TextView
    private lateinit var txtPeerStatus: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnStart: Button

    private fun usbManager() = getSystemService(Context.USB_SERVICE) as UsbManager
    private fun isUsb() = findViewById<RadioButton>(R.id.radUsb).isChecked
    private fun tailscaleSelected() = findViewById<RadioButton>(R.id.radNetTs).isChecked

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_USB) {
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val a = pendingUsb; pendingUsb = null
                if (granted) a?.invoke() else toast("USB permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        spinnerCard = findViewById(R.id.spinnerCard)
        txtPm3 = findViewById(R.id.txtPm3)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        txtMyIps = findViewById(R.id.txtMyIps)
        txtPeerStatus = findViewById(R.id.txtPeerStatus)
        logScroll = findViewById(R.id.logScroll)
        btnStart = findViewById(R.id.btnStart)

        spinnerCard.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("SEOS (tagType 12)", "DESFire EV1 (tagType 3)"))

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.btnConnectPm3).setOnClickListener { connectPm3() }
        btnStart.setOnClickListener { toggleRelay() }
        findViewById<RadioButton>(R.id.radBt).setOnCheckedChangeListener { _, c -> if (c) refreshDevices() }
        findViewById<RadioButton>(R.id.radUsb).setOnCheckedChangeListener { _, c -> if (c) refreshDevices() }
        findViewById<RadioButton>(R.id.radNetTs).setOnCheckedChangeListener { _, c -> if (c) showMyIps() }
        findViewById<RadioButton>(R.id.radNetWifi).setOnCheckedChangeListener { _, c -> if (c) showMyIps() }

        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB), ContextCompat.RECEIVER_NOT_EXPORTED)

        showMyIps()
        ensureBtPermsThen { refreshDevices() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    private fun log(s: String) = main.post {
        txtLog.append(s + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun status(s: String) = main.post { txtStatus.text = s }
    private fun peer(s: String) = main.post { txtPeerStatus.text = "peer: $s" }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- device discovery ----------
    private fun refreshDevices() {
        if (isUsb()) refreshUsb() else ensureBtPermsThen { refreshBt() }
    }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun refreshBt() {
        val a = adapter() ?: run { toast("No Bluetooth"); return }
        try { btDevices = a.bondedDevices?.toList() ?: emptyList() }
        catch (e: SecurityException) { toast("Bluetooth permission denied"); return }
        val names = btDevices.map { try { "${it.name} [${it.address}]" } catch (e: SecurityException) { it.address } }
        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("(pair PM3_RDV4.0 in Bluetooth settings)") else names)
        log("bluetooth: ${btDevices.size} paired device(s)")
    }

    private fun customProber(): UsbSerialProber {
        val t = UsbSerialProber.getDefaultProbeTable()
        t.addProduct(0x9ac4, 0x4b8f, CdcAcmSerialDriver::class.java) // Proxmark3 RDV4 / PM5
        return UsbSerialProber(t)
    }

    private fun refreshUsb() {
        usbDrivers = customProber().findAllDrivers(usbManager())
        val names = usbDrivers.map { d ->
            val dev = d.device
            "${dev.productName ?: "USB"} [${String.format("%04x:%04x", dev.vendorId, dev.productId)}] ${d.ports.size}p"
        }
        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("(no USB serial — plug the PM5 via OTG, then Refresh)") else names)
        log("usb: ${usbDrivers.size} serial device(s)")
    }

    // ---------- connect ----------
    private fun connectPm3() { if (isUsb()) connectUsb() else connectBt() }

    private fun connectBt() {
        if (btDevices.isEmpty()) { toast("Pair the Proxmark (PM3_RDV4.0, PIN 1234) first"); return }
        val idx = spinnerDevices.selectedItemPosition
        if (idx !in btDevices.indices) { toast("Pick a device"); return }
        val dev = btDevices[idx]
        txtPm3.text = "connecting (BT)…"
        Thread {
            try {
                try { adapter()?.cancelDiscovery() } catch (_: SecurityException) {}
                pm3Bt.connect(dev)
                link = pm3Bt
                main.post { txtPm3.text = "connected (BT)" }
                log("PM3 connected over Bluetooth")
            } catch (e: Exception) {
                main.post { txtPm3.text = "connect failed" }
                log("BT connect error: ${e.message}")
            }
        }.start()
    }

    private fun connectUsb() {
        if (usbDrivers.isEmpty()) { toast("No USB device — Refresh with the PM5 plugged in"); return }
        val idx = spinnerDevices.selectedItemPosition
        if (idx !in usbDrivers.indices) { toast("Pick a device"); return }
        val driver = usbDrivers[idx]
        val mgr = usbManager()
        val doOpen = { openUsb(driver) }
        if (mgr.hasPermission(driver.device)) doOpen()
        else {
            pendingUsb = doOpen
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName), flags)
            mgr.requestPermission(driver.device, pi)
        }
    }

    private fun openUsb(driver: UsbSerialDriver) {
        txtPm3.text = "connecting (USB)…"
        Thread {
            try {
                val portIndex = (driver.ports.size - 1).coerceAtLeast(0) // PM5 comms is the higher CDC
                val u = Pm3Usb(usbManager(), driver, portIndex)
                u.open()
                link = u
                main.post { txtPm3.text = "connected (USB p$portIndex)" }
                log("PM3 connected over USB (port $portIndex of ${driver.ports.size})")
            } catch (e: Exception) {
                main.post { txtPm3.text = "connect failed" }
                log("USB connect error: ${e.message}")
            }
        }.start()
    }

    // ---------- relay ----------
    private fun toggleRelay() {
        if (worker != null) { stopRelay(); return }
        val l = link
        if (l == null || !l.isConnected()) { toast("Connect the Proxmark first"); return }
        val role = if (findViewById<RadioButton>(R.id.radReader).isChecked) Role.READER else Role.EMULATOR
        val listen = findViewById<CompoundButton>(R.id.chkListen).isChecked
        val port = findViewById<EditText>(R.id.editPort).text.toString().trim().toIntOrNull() ?: 8099
        val peerStr = findViewById<EditText>(R.id.editPeer).text.toString().trim()
        val fwi = findViewById<EditText>(R.id.editFwi).text.toString().trim().toIntOrNull() ?: 14
        val sfgi = findViewById<EditText>(R.id.editSfgi).text.toString().trim().toIntOrNull() ?: 0
        val tagType = if (spinnerCard.selectedItemPosition == 1) 3 else 12

        val n = RelayLink()
        net = n
        relayRunning = true
        btnStart.text = "STOP"
        val w = Thread {
            try {
                if (listen) {
                    status("listening on $port…"); log("net: listening on $port (waiting for peer)"); peer("waiting…")
                    n.listen(port)
                } else {
                    if (!peerStr.contains(":")) throw IllegalArgumentException("peer must be host:port")
                    val h = peerStr.substringBeforeLast(":")
                    val p = peerStr.substringAfterLast(":").toIntOrNull() ?: port
                    // auto-reconnect: keep trying until the peer's listener is up
                    var ok = false
                    while (relayRunning && !ok) {
                        try { status("connecting to $h:$p…"); n.connect(h, p, 4000); ok = true }
                        catch (e: Exception) { peer("reconnecting…"); log("net: waiting for peer $h:$p"); Thread.sleep(1500) }
                    }
                    if (!ok) throw InterruptedException("stopped")
                }
                log("net: peer connected"); peer("connected"); status("relaying ($role)")
                val eng = RelayEngine(l, n, role, tagType, fwi, sfgi, ::log) { c -> status("relaying ($role) | APDUs: $c") }
                engine = eng
                eng.run()
            } catch (e: Exception) {
                log("relay ended: ${e.message}")
            } finally {
                try { n.close() } catch (_: Exception) {}
                relayRunning = false; engine = null; net = null; worker = null
                main.post { btnStart.text = "START RELAY" }; status("idle"); peer("—")
            }
        }
        worker = w; w.start()
    }

    private fun stopRelay() {
        log("stopping…")
        relayRunning = false
        engine?.stop()
        try { net?.close() } catch (_: Exception) {}
    }

    // ---------- helpers ----------
    private data class IfIp(val iface: String, val ip: String, val tailscale: Boolean)

    private fun listIps(): List<IfIp> {
        val out = mutableListOf<IfIp>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (a.isLoopbackAddress || h.contains(':')) continue // IPv4 only
                    val ts = nif.name.contains("tailscale", true) || h.startsWith("100.")
                    out.add(IfIp(nif.name, h, ts))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun showMyIps() {
        val ips = listIps()
        val txt = if (tailscaleSelected()) {
            val ts = ips.firstOrNull { it.tailscale }?.ip
            if (ts != null) "this device (Tailscale): $ts  →  give this to the peer"
            else "Tailscale not up — open the Tailscale app and sign in"
        } else {
            val wifi = ips.firstOrNull { !it.tailscale && it.iface.startsWith("wlan") }?.ip
                ?: ips.firstOrNull { !it.tailscale }?.ip
            if (wifi != null) "this device (Wi-Fi): $wifi  →  give this to the peer"
            else "no Wi-Fi IP — join a network first"
        }
        main.post { txtMyIps.text = txt }
    }

    private fun ensureBtPermsThen(action: () -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action()
        else { pendingPerm = action; ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ) }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == REQ) {
            val a = pendingPerm; pendingPerm = null
            if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) a?.invoke()
            else toast("Bluetooth permission required")
        }
    }
}
