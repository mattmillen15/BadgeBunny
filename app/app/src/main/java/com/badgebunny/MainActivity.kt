package com.badgebunny

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.badgebunny.bt.Pm3Ble
import com.badgebunny.bt.Pm3Bluetooth
import com.badgebunny.log.BbLog
import com.badgebunny.net.RelayLink
import com.badgebunny.relay.RelayEngine
import com.badgebunny.relay.Role
import com.badgebunny.transport.Pm3Link
import com.badgebunny.transport.Pm3Usb
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var link: Pm3Link? = null
    @Volatile private var engine: RelayEngine? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var net: RelayLink? = null
    @Volatile private var relayRunning = false
    @Volatile private var connecting = false   // automation: keep trying to connect the PM3 until it appears
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var usbDrivers: List<UsbSerialDriver> = emptyList()
    private var pendingUsb: (() -> Unit)? = null
    private val ACTION_USB = "com.badgebunny.USB_PERMISSION"

    // Automation hook (adb-drivable, for hands-free bench testing over Bluetooth):
    //   am start -n com.badgebunny/.MainActivity --es bb_cmd start --es bb_role card|reader \
    //            --es bb_transport ble|spp --es bb_mac AA:BB:.. [--es bb_peer 100.x.x.x]
    //   am start -n com.badgebunny/.MainActivity --es bb_cmd stop
    //   am start -n com.badgebunny/.MainActivity --es bb_cmd scan   (BLE scan → log devices)
    @Volatile private var autoPeerIp: String? = null
    @Volatile private var autoTransport: String? = null  // remembered so a mid-relay drop can reconnect
    @Volatile private var autoMac: String? = null

    // Which Proxmark transport the UI is currently targeting.
    private enum class Transport { USB, BLE, SPP }
    private var transport = Transport.USB
    private var btDevices: List<BluetoothDevice> = emptyList()

    // Max phone-to-phone round-trip we'll relay over. Below the card's ~155 ms frame-waiting time,
    // with margin; ~40 ms links succeed, ~196 ms DERP links fail. Slower than this and we refuse to arm.
    private val MAX_RTT_MS = 120L

    data class Peer(val label: String, val ip: String)
    private val peers = mutableListOf<Peer>()
    private val PREFS = "bb_prefs"
    private val KEY_SAVED_PEERS = "saved_peers"

    private lateinit var spinnerDevices: Spinner
    private lateinit var spinnerTransport: Spinner
    private lateinit var spinnerPeers: Spinner
    private lateinit var editPeer: EditText
    private lateinit var txtPm3: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtMyIps: TextView
    private lateinit var txtPeerStatus: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnStart: Button
    private lateinit var pm3StatusDot: View
    private lateinit var peerStatusDot: View
    private lateinit var btnDiscoverPeers: Button

    private fun usbManager() = getSystemService(Context.USB_SERVICE) as UsbManager
    private fun isCardSide() = findViewById<RadioButton>(R.id.radReader).isChecked

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

        BbLog.init(File(getExternalFilesDir(null), "logs"))

        spinnerDevices = findViewById(R.id.spinnerDevices)
        spinnerTransport = findViewById(R.id.spinnerTransport)
        spinnerPeers = findViewById(R.id.spinnerPeers)
        editPeer = findViewById(R.id.editPeer)
        txtPm3 = findViewById(R.id.txtPm3)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        txtMyIps = findViewById(R.id.txtMyIps)
        txtPeerStatus = findViewById(R.id.txtPeerStatus)
        logScroll = findViewById(R.id.logScroll)
        btnStart = findViewById(R.id.btnStart)
        btnDiscoverPeers = findViewById(R.id.btnDiscoverPeers)
        pm3StatusDot = findViewById(R.id.pm3StatusDot)
        peerStatusDot = findViewById(R.id.peerStatusDot)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { scanDevices() }
        findViewById<Button>(R.id.btnConnectPm3).setOnClickListener { connectSelected() }
        btnStart.setOnClickListener { toggleRelay() }
        btnDiscoverPeers.setOnClickListener { discoverPeers() }

        spinnerTransport.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("USB (OTG)", "Bluetooth LE — PM5", "Bluetooth SPP — RDV4"))
        spinnerTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                transport = Transport.values()[pos]
                onTransportChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerPeers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                editPeer.visibility = if (pos == peers.size) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<RadioGroup>(R.id.roleGroup).setOnCheckedChangeListener { _, _ -> onRoleChanged() }

        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB), ContextCompat.RECEIVER_NOT_EXPORTED)

        onRoleChanged()
        refreshUsb()
        loadSavedPeers()
        log("log: ${BbLog.logFile?.absolutePath}")
        handleAutomation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomation(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { link?.close() } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { sppReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { btAdapter()?.let { if (it.isDiscovering) it.cancelDiscovery() } } catch (_: Exception) {}
        releaseLocks()
        BbLog.close()
    }

    private fun log(s: String) {
        BbLog.d("BB", s)
        main.post {
            txtLog.append(s + "\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
    private fun status(s: String) = main.post { txtStatus.text = s }
    private fun peer(s: String) = main.post { txtPeerStatus.text = "peer: $s" }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setDotColor(dot: View, color: Int) = main.post {
        val bg = dot.background
        if (bg is android.graphics.drawable.GradientDrawable) bg.setColor(color)
    }

    private val COL_GREEN = 0xFF2FD3A6.toInt()
    private val COL_RED   = 0xFFFF5C6C.toInt()
    private val COL_AMBER = 0xFFFFA726.toInt()
    private val COL_GREY  = 0xFF4A5568.toInt()

    private fun pm3Dot(color: Int) = setDotColor(pm3StatusDot, color)
    private fun peerDot(color: Int) = setDotColor(peerStatusDot, color)

    private fun setBtnRelay(running: Boolean) = main.post {
        btnStart.text = if (running) "STOP RELAY" else "START RELAY"
        btnStart.setBackgroundResource(if (running) R.drawable.btn_stop_bg else R.drawable.btn_start_bg)
    }

    private fun onRoleChanged() {
        val card = isCardSide()
        spinnerPeers.visibility = if (card) View.GONE else View.VISIBLE
        btnDiscoverPeers.visibility = if (card) View.GONE else View.VISIBLE
        txtPeerStatus.visibility = if (card) View.GONE else View.VISIBLE
        editPeer.visibility = View.GONE
        showMyIps()
    }

    // ---------- local IPs ----------
    // The phone-to-phone link runs over Tailscale only. (LAN/hotspot was tried and made no
    // difference to the real failure, so it was removed to keep the path unambiguous.) The
    // preflight probe still verifies the real RTT before arming.
    private fun ipv4s(): List<String> {
        val out = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (!h.contains(':')) out.add(h)
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun tailscaleIp(): String? = ipv4s().firstOrNull { it.startsWith("100.") }

    /** Address a peer can dial us on (Tailscale only). */
    private fun listenHint(): String = tailscaleIp()?.let { "$it (Tailscale)" } ?: "(no Tailscale IP)"

    private fun showMyIps() {
        val ts = tailscaleIp()
        val here = if (isCardSide()) " (peer connects here)" else ""
        val txt = if (ts != null) "Your Tailscale IP: $ts$here"
                  else "No Tailscale — start Tailscale, then reopen"
        main.post { txtMyIps.text = txt }
    }

    // ---------- Peer discovery ----------
    private fun loadSavedPeers() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_SAVED_PEERS, emptySet()) ?: emptySet()
        peers.clear()
        for (ip in saved.sorted()) peers.add(Peer(ip, ip))
        updatePeerSpinner()
    }

    private fun savePeer(ip: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = (prefs.getStringSet(KEY_SAVED_PEERS, emptySet()) ?: emptySet()).toMutableSet()
        saved.add(ip)
        prefs.edit().putStringSet(KEY_SAVED_PEERS, saved).apply()
    }

    private fun updatePeerSpinner() {
        val labels = peers.map { it.label }.toMutableList()
        labels.add("Enter IP manually…")
        spinnerPeers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (peers.isNotEmpty()) spinnerPeers.setSelection(0)
    }

    private fun discoverPeers() {
        log("probing saved peers…")
        btnDiscoverPeers.isEnabled = false
        Thread {
            val found = mutableListOf<Peer>()
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val saved = prefs.getStringSet(KEY_SAVED_PEERS, emptySet()) ?: emptySet()
            for (ip in saved) {
                try {
                    val s = Socket()
                    s.connect(java.net.InetSocketAddress(ip, 8099), 1000)
                    s.close()
                    found.add(Peer("$ip (listening)", ip))
                    log("$ip:8099 — listening")
                } catch (_: Exception) {
                    found.add(Peer("$ip (saved)", ip))
                }
            }
            main.post {
                peers.clear()
                peers.addAll(found)
                updatePeerSpinner()
                btnDiscoverPeers.isEnabled = true
                log("${peers.size} peer(s)")
            }
        }.start()
    }

    private fun selectedPeerIp(): String? {
        val pos = spinnerPeers.selectedItemPosition
        return if (pos < peers.size) {
            peers[pos].ip
        } else {
            editPeer.text.toString().trim().ifEmpty { null }
        }
    }

    // ---------- USB ----------
    private fun customProber(): UsbSerialProber {
        val t = UsbSerialProber.getDefaultProbeTable()
        t.addProduct(0x9ac4, 0x4b8f, CdcAcmSerialDriver::class.java)
        return UsbSerialProber(t)
    }

    private fun refreshUsb() {
        usbDrivers = customProber().findAllDrivers(usbManager())
        val names = usbDrivers.map { d ->
            val dev = d.device
            "${dev.productName ?: "USB"} [${String.format("%04x:%04x", dev.vendorId, dev.productId)}] ${d.ports.size}p"
        }
        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("(plug Proxmark via USB-OTG, then Scan)") else names)
        log("usb: ${usbDrivers.size} device(s)")
    }

    private fun connectUsb() {
        if (usbDrivers.isEmpty()) { toast("No USB device — plug Proxmark via OTG then Scan"); return }
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
        txtPm3.text = "connecting…"; pm3Dot(COL_AMBER)
        Thread {
            try {
                link?.close()
                link = null
                val portIndex = (driver.ports.size - 1).coerceAtLeast(0)
                val u = Pm3Usb(usbManager(), driver, portIndex)
                u.open()
                link = u
                main.post { txtPm3.text = "connected (USB p$portIndex)" }
                pm3Dot(COL_GREEN)
                log("PM3 connected (USB port $portIndex)")
            } catch (e: Exception) {
                main.post { txtPm3.text = "connect failed" }
                pm3Dot(COL_RED)
                log("USB error: ${e.message}")
            }
        }.start()
    }

    // ---------- transport selection (USB / Bluetooth) ----------
    private fun onTransportChanged() {
        val hint = when (transport) {
            Transport.USB -> "(tap Scan for USB devices)"
            Transport.BLE -> "(tap Scan for BLE devices — PM5)"
            Transport.SPP -> "(tap Scan — put the RDV4 in pairing mode)"
        }
        btDevices = emptyList()
        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(hint))
        val tag = when (transport) {
            Transport.USB -> "USB"
            Transport.BLE -> "BLE"
            Transport.SPP -> "SPP"
        }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .subtitle = "Seos relay · $tag"
    }

    private fun scanDevices() {
        when (transport) {
            Transport.USB -> refreshUsb()
            Transport.SPP -> listBondedSpp()
            Transport.BLE -> bleScanToSpinner()
        }
    }

    private fun connectSelected() {
        when (transport) {
            Transport.USB -> connectUsb()
            Transport.BLE, Transport.SPP -> connectBtSelected()
        }
    }

    // SPP (Bluetooth Classic) scan: list already-bonded devices AND run classic discovery so an
    // unpaired RDV4 sitting in pairing mode shows up. Connecting to an unbonded one pairs it
    // (PIN 1234) — see ensureBonded(). BLE devices never appear here; the RDV4 is Classic-only.
    private val sppFound = LinkedHashMap<String, BluetoothDevice>()
    private var sppReceiver: BroadcastReceiver? = null

    private fun publishSppSpinner() {
        btDevices = sppFound.values.toList()
        val names = btDevices.map {
            val paired = it.bondState == BluetoothDevice.BOND_BONDED
            "${(try { it.name } catch (_: Exception) { null }) ?: "?"} [${it.address}]" +
                if (paired) "  ✓ paired" else "  — tap Connect to pair"
        }
        spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("(scanning… put the RDV4 in pairing mode)") else names)
    }

    private fun listBondedSpp() {
        val a = btAdapter()
        if (a == null || !a.isEnabled) { toast("Turn on Bluetooth"); return }
        sppFound.clear()
        try { a.bondedDevices?.forEach { sppFound[it.address] = it } } catch (e: Exception) { log("bonded read failed: ${e.message}") }
        publishSppSpinner()
        if (sppReceiver == null) {
            sppReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                            if (sppFound.put(d.address, d) == null) log("bt: discovered ${(try { d.name } catch (_: Exception) { null }) ?: "?"} [${d.address}]")
                            publishSppSpinner()
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                            log("bt: SPP discovery done (${sppFound.size} device(s))")
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(this, sppReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        }
        try { if (a.isDiscovering) a.cancelDiscovery() } catch (_: Exception) {}
        toast("SPP scanning 12s…"); log("bt: SPP discovery started")
        try { a.startDiscovery() } catch (e: Exception) { log("bt: discovery failed ${e.message}") }
    }

    // Pair (bond) a Classic device if needed, auto-supplying the BlueShark PIN 1234, then return
    // once bonded. Safe to call on an already-bonded device (returns immediately).
    @Throws(Exception::class)
    private fun ensureBonded(dev: BluetoothDevice) {
        val a = btAdapter() ?: throw IllegalStateException("no Bluetooth adapter")
        try { if (a.isDiscovering) a.cancelDiscovery() } catch (_: Exception) {}
        if (dev.bondState == BluetoothDevice.BOND_BONDED) return
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.address != dev.address) return
                when (i.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> try {
                        val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        log("bt: pairing request (variant=$variant) → PIN 1234")
                        dev.setPin("1234".toByteArray())
                        try { dev.setPairingConfirmation(true) } catch (_: Exception) {}
                    } catch (e: Exception) { log("bt: setPin failed ${e.message}") }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> { ok.set(true); done.countDown() }
                            BluetoothDevice.BOND_NONE -> done.countDown()
                        }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)
        try {
            log("bt: bonding with ${dev.address}…")
            if (!dev.createBond()) log("bt: createBond() returned false")
            done.await(25, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            try { unregisterReceiver(rx) } catch (_: Exception) {}
        }
        if (!ok.get() && dev.bondState != BluetoothDevice.BOND_BONDED)
            throw IllegalStateException("pairing failed or timed out")
        log("bt: bonded ${dev.address}")
    }

    private fun bleScanToSpinner() {
        val a = btAdapter()
        if (a == null || !a.isEnabled) { toast("Turn on Bluetooth"); return }
        val scanner = a.bluetoothLeScanner ?: run { toast("No BLE scanner"); return }
        toast("BLE scanning 8s…"); log("bt: BLE scanning 8s…")
        val found = LinkedHashMap<String, BluetoothDevice>()
        val rssiMap = HashMap<String, Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                found[r.device.address] = r.device
                rssiMap[r.device.address] = r.rssi
            }
            override fun onScanFailed(errorCode: Int) { log("bt: BLE scan failed code=$errorCode") }
        }
        try { scanner.startScan(cb) } catch (e: Exception) { log("bt: ${e.message}"); return }
        main.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            // Sort: named devices first (PM5 = "Proxmark5"), then by RSSI descending
            btDevices = found.values.sortedWith(compareBy<BluetoothDevice> {
                val name = try { it.name } catch (_: Exception) { null }
                when {
                    name?.contains("Proxmark", ignoreCase = true) == true -> 0
                    name != null -> 1
                    else -> 2
                }
            }.thenByDescending { rssiMap[it.address] ?: -999 })
            val names = btDevices.map {
                val name = (try { it.name } catch (_: Exception) { null }) ?: "?"
                val rssi = rssiMap[it.address]?.let { r -> " (${r}dBm)" } ?: ""
                "$name [${it.address}]$rssi"
            }
            spinnerDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (names.isEmpty()) listOf("(no BLE devices — power-cycle the PM5)") else names)
            log("bt: ${btDevices.size} BLE device(s)")
        }, 8000)
    }

    private fun connectBtSelected() {
        val idx = spinnerDevices.selectedItemPosition
        if (idx !in btDevices.indices) { toast("Scan and pick a device"); return }
        val dev = btDevices[idx]
        val tName = if (transport == Transport.BLE) "ble" else "spp"
        val needsPair = transport == Transport.SPP && dev.bondState != BluetoothDevice.BOND_BONDED
        txtPm3.text = if (needsPair) "pairing…" else "connecting…"; pm3Dot(COL_AMBER)
        Thread {
            try {
                link?.close(); link = null
                if (transport == Transport.SPP) ensureBonded(dev)
                val pm3 = connectBt(tName, dev.address)
                link = pm3
                autoTransport = tName; autoMac = dev.address
                main.post { txtPm3.text = "connected ($tName)" }
                pm3Dot(COL_GREEN)
                log("PM3 connected over $tName (${dev.address})")
            } catch (e: Exception) {
                main.post { txtPm3.text = "connect failed" }
                pm3Dot(COL_RED)
                log("BT connect error: ${e.message}")
            }
        }.start()
    }

    // ---------- relay ----------
    private fun toggleRelay() {
        if (worker != null) { stopRelay(); return }
        var l = link
        if (l == null || !l.isConnected()) { toast("Connect the Proxmark first"); return }

        // The relay needs a reachable phone-to-phone path: a shared LAN/hotspot (fast, preferred) or
        // Tailscale. The listener (reader) must have an address for the peer to reach; without any
        // network at all, fail fast instead of hanging on "waiting for peer".
        if (isCardSide() && tailscaleIp() == null) {
            toast("No Tailscale — start Tailscale, then try again")
            log("cannot start relay: no Tailscale IP to listen on")
            return
        }

        val role = if (isCardSide()) Role.READER else Role.EMULATOR
        val listen = isCardSide()
        val port = 8099

        var peerIp: String? = null
        if (!listen) {
            peerIp = autoPeerIp ?: selectedPeerIp()
            if (peerIp.isNullOrEmpty()) { toast("Select or enter the peer's Tailscale IP"); return }
            savePeer(peerIp)
        }

        relayRunning = true
        setBtnRelay(true)
        val w = Thread {
            try {
                while (relayRunning) {
                    if (l == null || !(l?.isConnected() ?: false)) {
                        // BLE/SPP dropped mid-relay. Instead of dying (which needs a manual re-arm),
                        // reconnect the Proxmark and keep going — self-healing so the relay survives
                        // link blips without a force-stop (which would wedge the PM5 BWM).
                        log("PM3 link dropped — reconnecting…")
                        txtPm3Post("reconnecting…"); pm3Dot(COL_AMBER)
                        try { l?.close() } catch (_: Exception) {}
                        val tr = autoTransport; val mc = autoMac
                        if (tr == null || mc == null) { log("no reconnect params — stopping"); txtPm3Post("disconnected"); pm3Dot(COL_RED); break }
                        var rel: Pm3Link? = null
                        var tries = 0
                        while (relayRunning && rel == null) {
                            tries++
                            try { rel = connectAuto(tr, mc) }
                            catch (e: Exception) {
                                if (tries % 5 == 1) log("reconnect try $tries: ${e.message}")
                                var w = 0; while (relayRunning && w < 3000) { try { Thread.sleep(200) } catch (_: Exception) {}; w += 200 }
                            }
                        }
                        if (rel == null) break
                        l = rel; link = rel
                        txtPm3Post("connected ($tr)"); pm3Dot(COL_GREEN)
                        log("PM3 reconnected over $tr after $tries try(s)")
                        continue
                    }

                    val n = RelayLink()
                    net = n
                    try {
                        // 1) Open the phone-to-phone socket FIRST. Previously we reset the PM3 before
                        //    this; a slow/flaky USB reset (seen taking ~18 s) held the listener closed,
                        //    so the peer's connect() attempts failed the whole time and it looked like
                        //    the tunnel never formed. Bind immediately → the peer connects at once.
                        if (listen) {
                            n.bindListener(port)
                            status("listening :$port…")
                            log("listening on :$port (all interfaces) — reach me at ${listenHint()}")
                            peer("waiting…")
                            n.acceptPeer()
                        } else {
                            var ok = false
                            while (relayRunning && !ok) {
                                try {
                                    status("connecting $peerIp…")
                                    n.connect(peerIp!!, port, 4000)
                                    ok = true
                                } catch (e: Exception) {
                                    if (!relayRunning) throw InterruptedException("stopped")
                                    peer("waiting…")
                                    log("waiting for peer $peerIp")
                                    Thread.sleep(1500)
                                }
                            }
                            if (!ok) break
                        }
                        log("peer connected")
                        peer("connected"); peerDot(COL_GREEN)

                        // 2) Now reset the PM3 into its mode-select loop (RESTART frame) before we
                        //    send READ/CARD. Retry a few times so a single flaky USB write doesn't
                        //    drop the peer we just connected; only recycle the session if all fail.
                        // Robust reset. An aborted prior session can leave the firmware mid-loop
                        // (become_reader/become_card) or, worse, mid-prepare_emulation where a single
                        // RESTART is swallowed as UID/ATS data instead of breaking to mode-select. So:
                        //   1) drain leftover bytes (clears the desync residue), then
                        //   2) send RESTART until we land 3 clean ACKs — enough to fall all the way
                        //      through prepare_emulation's remaining reads into a loop that honours
                        //      RESTART, from ANY stuck state. Extra RESTARTs at mode-select are no-ops
                        //      ("already reset"). Retries also ride out a lossy BWM/BLE link.
                        status("resetting PM3…")
                        try { l!!.drainInput() } catch (_: Exception) {}
                        var cleanAcks = 0
                        for (attempt in 1..10) {
                            if (!relayRunning) break
                            try { l!!.reset(); cleanAcks++ }
                            catch (e: Exception) {
                                cleanAcks = 0
                                log("reset attempt $attempt: ${e.message}")
                                // Link died (e.g. USB write timeout rc=-1) — stop spinning; the outer
                                // loop reconnects the transport, which resets the endpoint.
                                if (l?.isConnected() != true) { log("link down during reset — reconnecting"); break }
                                try { l!!.drainInput() } catch (_: Exception) {}
                            }
                            if (cleanAcks >= 3) break
                        }
                        val didReset = cleanAcks >= 3
                        try { l!!.drainInput() } catch (_: Exception) {}

                        if (!didReset) {
                            if (!relayRunning) { log("stopped") }
                            else log("PM3 reset failed — recycling session")
                        } else {
                            // 3) Preflight: measure the real link RTT before we present the card. The
                            //    Seos card's own frame-waiting time is ~155 ms, so a link slower than
                            //    that drops the session mid-auth. Refuse to arm on a slow link and
                            //    re-arm; the operator switches to a direct path (LAN/hotspot / TS direct).
                            status("checking link…")
                            val rttMs = try {
                                if (listen) n.serveRtt() else n.measureRttMs()
                            } catch (e: Exception) {
                                if (!relayRunning) throw e
                                log("preflight failed: ${e.message}"); -1L
                            }
                            if (rttMs >= 0) log("link RTT ~${rttMs} ms")

                            if (rttMs < 0) {
                                log("peer lost during preflight — recycling")
                            } else if (rttMs > MAX_RTT_MS) {
                                log("LINK TOO SLOW (~${rttMs} ms > ${MAX_RTT_MS} ms). The card's frame-wait is ~155 ms, so it will drop mid-auth. Use a DIRECT path — same Wi-Fi / phone hotspot, or Tailscale direct (not a DERP relay) — then it re-arms automatically.")
                                status("link too slow: ${rttMs} ms")
                                peer("too slow ${rttMs} ms"); peerDot(COL_RED)
                            } else {
                                val rttNote = " · ${rttMs} ms"
                                peer("connected$rttNote")
                                status("relaying ($role)$rttNote")
                                val eng = RelayEngine(l!!, n, role, 12, 14, 0, ::log) { c ->
                                    status("relaying ($role)$rttNote | APDUs: $c")
                                }
                                engine = eng
                                eng.run()
                            }
                        }
                    } catch (e: Exception) {
                        if (!relayRunning) log("stopped")
                        else log("session: ${e.message}")
                    } finally {
                        try { n.close() } catch (_: Exception) {}
                        engine = null; net = null
                    }

                    if (relayRunning) {
                        log("session ended — ready for next relay")
                        Thread.sleep(1000)
                    }
                }
            } finally {
                relayRunning = false; engine = null; net = null; worker = null
                setBtnRelay(false)
                status("idle"); peer("—"); peerDot(COL_GREY)
            }
        }
        worker = w; w.start()
    }

    // ---------- automation (adb-drivable, hands-free bench testing over Bluetooth) ----------
    private fun txtPm3Post(s: String) = main.post { txtPm3.text = s }

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun handleAutomation(intent: Intent?) {
        val cmd = intent?.getStringExtra("bb_cmd")?.lowercase() ?: return
        log("automation: cmd=$cmd")
        when (cmd) {
            "stop" -> stopRelay()
            "scan" -> bleScan()
            "start" -> {
                val role = intent.getStringExtra("bb_role") ?: "card"
                val transport = intent.getStringExtra("bb_transport") ?: "ble"
                val mac = intent.getStringExtra("bb_mac")
                val peer = intent.getStringExtra("bb_peer")
                val isCard = role.lowercase().startsWith("c")
                if (transport.lowercase() != "usb" && mac.isNullOrBlank()) { log("automation: bb_mac required for $transport"); return }
                if (worker != null) { log("automation: stopping existing relay first…"); stopRelay() }
                log("automation: start role=$role transport=$transport mac=$mac peer=$peer")
                main.post { findViewById<RadioGroup>(R.id.roleGroup).check(if (isCard) R.id.radReader else R.id.radEmulator) }
                autoPeerIp = peer; autoTransport = transport; autoMac = mac
                Thread {
                    awaitWorkerDead()
                    acquireLocks()
                    connecting = true
                    var pm3: Pm3Link? = null
                    var tries = 0
                    while (connecting && pm3 == null) {
                        tries++
                        try {
                            txtPm3Post("connecting $transport… (try $tries)"); pm3Dot(COL_AMBER)
                            pm3 = connectAuto(transport, mac)
                        } catch (e: Exception) {
                            log("automation: connect try $tries failed: ${e.message}")
                            txtPm3Post("waiting for $transport… ($tries)"); pm3Dot(COL_AMBER)
                            var w = 0; while (connecting && w < 3000) { try { Thread.sleep(200) } catch (_: Exception) {}; w += 200 }
                        }
                    }
                    if (pm3 != null && connecting) {
                        link = pm3
                        txtPm3Post("connected ($transport)"); pm3Dot(COL_GREEN)
                        log("automation: PM3 connected over $transport ($mac) after $tries try(s)")
                        main.post { if (worker == null) toggleRelay() }
                    } else {
                        try { pm3?.close() } catch (_: Exception) {}
                    }
                    connecting = false
                }.start()
            }
            else -> log("automation: unknown cmd $cmd")
        }
    }

    @Throws(Exception::class)
    private fun connectBt(transport: String, mac: String): Pm3Link {
        val adapter = btAdapter() ?: throw IllegalStateException("no Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        try { if (adapter.isDiscovering) adapter.cancelDiscovery() } catch (_: Exception) {}
        val dev: BluetoothDevice = adapter.getRemoteDevice(mac)
        return when (transport.lowercase()) {
            "ble" -> Pm3Ble(applicationContext).also { it.connect(dev) }
            "spp", "classic", "bt" -> {
                ensureBonded(dev)
                Pm3Bluetooth().also { it.connect(dev) }
            }
            else -> throw IllegalArgumentException("unknown transport '$transport' (use ble|spp)")
        }
    }

    /** Transport dispatcher for the automation/reconnect paths: usb (OTG, CARDHOPPER_USB firmware —
     *  the reliable wired backup), or ble/spp over Bluetooth. */
    @Throws(Exception::class)
    private fun connectAuto(transport: String, mac: String?): Pm3Link =
        if (transport.lowercase() == "usb") connectUsbAuto()
        else connectBt(transport, mac ?: throw IllegalStateException("bb_mac required for $transport"))

    /** Find the first USB-OTG Proxmark, ensure permission (auto-granted via the USB_DEVICE_ATTACHED
     *  intent-filter, or requested here as a fallback), and open it. No UI needed — adb-drivable. */
    @Throws(Exception::class)
    private fun connectUsbAuto(): Pm3Link {
        val drivers = customProber().findAllDrivers(usbManager())
        usbDrivers = drivers
        if (drivers.isEmpty()) throw IllegalStateException("no USB device — plug the Proxmark via OTG")
        val driver = drivers[0]
        val mgr = usbManager()
        if (!mgr.hasPermission(driver.device)) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == ACTION_USB) latch.countDown() }
            }
            ContextCompat.registerReceiver(this, rx, IntentFilter(ACTION_USB), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName), flags)
                mgr.requestPermission(driver.device, pi)
                latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
            } finally { try { unregisterReceiver(rx) } catch (_: Exception) {} }
            if (!mgr.hasPermission(driver.device)) throw IllegalStateException("USB permission denied")
        }
        val portIndex = (driver.ports.size - 1).coerceAtLeast(0)   // PM5/clones expose 2 CDCs; use higher
        return Pm3Usb(mgr, driver, portIndex).also { it.open() }
    }

    private fun bleScan() {
        val a = btAdapter()
        if (a == null) { log("scan: no Bluetooth adapter"); return }
        // Classic bonded devices (RDV4 BlueShark SPP shows up here once paired)
        try {
            val bonded = a.bondedDevices ?: emptySet()
            log("scan: ${bonded.size} bonded (classic) device(s)")
            for (d in bonded) log("scan: bonded ${d.address}  ${d.name}")
        } catch (e: Exception) { log("scan: bonded read failed: ${e.message}") }
        // BLE advertisements (PM5 BWM shows up here)
        val scanner = a.bluetoothLeScanner
        if (scanner == null) { log("scan: no BLE scanner (Bluetooth off?)"); return }
        log("scan: BLE scanning 8s…")
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val d = r.device
                if (seen.add(d.address)) {
                    val name = (try { d.name } catch (_: Exception) { null }) ?: r.scanRecord?.deviceName
                    log("scan: BLE ${d.address}  rssi=${r.rssi}  name=${name ?: "?"}")
                }
            }
            override fun onScanFailed(errorCode: Int) { log("scan: BLE failed code=$errorCode") }
        }
        try {
            scanner.startScan(cb)
            main.postDelayed({
                try { scanner.stopScan(cb) } catch (_: Exception) {}
                log("scan: done (${seen.size} BLE device(s))")
            }, 8000)
        } catch (e: Exception) { log("scan: ${e.message}") }
    }

    // Hold the Wi-Fi radio in high-performance mode and keep the CPU awake for the duration of the
    // relay. Without this the phone parks the radio between beacons (RTT spikes to 300ms+ and TCP
    // connects intermittently fail) and dozes the CPU, which stalls the socket + BLE/SPP pumps.
    private fun acquireLocks() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "badgebunny:relay")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "badgebunny:relay")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(60 * 60 * 1000L)
            log("power: WifiLock(HIGH_PERF)+WakeLock held")
        } catch (e: Exception) { log("power: lock acquire failed: ${e.message}") }
    }
    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private fun stopRelay() {
        log("stopping…")
        relayRunning = false
        connecting = false
        engine?.stop()
        try { net?.close() } catch (_: Exception) {}
        try { link?.close() } catch (_: Exception) {}
        link = null
        worker?.interrupt()
        releaseLocks()
        pm3Dot(COL_GREY)
        setBtnRelay(false)
        status("idle"); peer("—"); peerDot(COL_GREY)
    }

    private fun awaitWorkerDead(timeoutMs: Long = 5000) {
        val w = worker ?: return
        try { w.join(timeoutMs) } catch (_: Exception) {}
        if (!w.isAlive) worker = null
    }
}
