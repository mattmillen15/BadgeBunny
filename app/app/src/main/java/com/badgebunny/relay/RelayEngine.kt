package com.badgebunny.relay

import com.badgebunny.bt.Pm3Bluetooth
import com.badgebunny.net.RelayLink

enum class Role { READER, EMULATOR }

/**
 * Wires the Proxmark (hf_cardhopper) to the network relay for a chosen role.
 *
 *  READER   : Proxmark next to the real card. Sends "READ", captures UID+ATS, ships them to the
 *             peer, then relays each APDU it receives to the real card and returns the response.
 *  EMULATOR : Proxmark at the target reader. Sends "CARD" + tagType + [FWI,SFGI] (forges the ATS
 *             timing locally), receives UID+ATS from the peer, then feeds the reader's APDUs across
 *             the link and replies with what comes back.
 *
 * Mirrors Loudmouth's CardHopper host flow. RATS-aware on the emulator side so it works whether the
 * flashed firmware forwards RATS to the host (current Iceman master) or answers it locally.
 */
class RelayEngine(
    private val pm3: com.badgebunny.transport.Pm3Link,
    private val net: RelayLink,
    private val role: Role,
    private val tagType: Int, // emulator only: SEOS=12, DESFire EV1=3
    private val fwi: Int,     // emulator only: 0..14
    private val sfgi: Int,    // emulator only: 0..14
    private val log: (String) -> Unit,
    private val onCount: (Int) -> Unit
) {
    @Volatile private var running = false
    private var count = 0

    fun stop() { running = false }

    private fun isRats(f: ByteArray) = f.size == 2 && (f[0].toInt() and 0xFF) == 0xE0
    private fun clamp(v: Int) = if (v > 14) 14 else if (v < 0) 0 else v

    @Throws(Exception::class)
    fun run() {
        running = true
        try {
            when (role) {
                Role.READER -> runReader()
                Role.EMULATOR -> runEmulator()
            }
        } finally {
            net.sendErr()
        }
    }

    private fun runReader() {
        log("READER: sending READ — present the card to the Proxmark…")
        pm3.sendFrame("READ".toByteArray(Charsets.US_ASCII))
        val uid = pm3.recvFrame()
        val ats = pm3.recvFrame()
        log("READER: UID = ${uid.hex()}")
        log("READER: ATS = ${ats.hex()}")
        net.sendMsg(RelayLink.UID, uid)
        net.sendMsg(RelayLink.ATS, ats)
        log("READER: relaying APDUs to the card…")
        while (running) {
            val m = net.recv()
            if (m.type == RelayLink.ERR) { log("peer signalled end"); break }
            pm3.sendFrame(m.payload)
            val resp = pm3.recvFrame()
            net.sendMsg(RelayLink.APDU, resp)
            count++; onCount(count)
            log("→ card ${m.payload.hex()}")
            log("← card ${resp.hex()}")
        }
    }

    private fun runEmulator() {
        pm3.sendFrame("CARD".toByteArray(Charsets.US_ASCII))
        pm3.sendFrame(byteArrayOf(tagType.toByte()))
        pm3.sendFrame(byteArrayOf(clamp(fwi).toByte(), clamp(sfgi).toByte()))
        log("EMULATOR: waiting for UID/ATS from peer…")
        val uidMsg = net.recv(); if (uidMsg.type == RelayLink.ERR) { log("peer signalled end"); return }
        pm3.sendFrame(uidMsg.payload)
        log("EMULATOR: UID = ${uidMsg.payload.hex()}")
        val atsMsg = net.recv(); if (atsMsg.type == RelayLink.ERR) { log("peer signalled end"); return }
        pm3.sendFrame(atsMsg.payload)
        log("EMULATOR: ATS = ${atsMsg.payload.hex()}  (FWI=${clamp(fwi)} SFGI=${clamp(sfgi)} tagType=$tagType)")
        log("EMULATOR: present the Proxmark to the reader…")
        while (running) {
            val apdu = pm3.recvFrame()            // command from the real reader
            net.sendMsg(RelayLink.APDU, apdu)
            if (isRats(apdu)) {
                // Firmware already answered the reader with the cooked ATS and won't read a reply;
                // drop the real ATS the peer sends back so the streams stay in sync.
                val d = net.recv()
                if (d.type == RelayLink.ERR) break
                log("RATS relayed (${apdu.hex()}); peer ATS dropped")
            } else {
                val resp = net.recv()
                if (resp.type == RelayLink.ERR) { log("peer signalled end"); break }
                pm3.sendFrame(resp.payload)
                count++; onCount(count)
                log("← reader ${apdu.hex()}")
                log("→ reader ${resp.payload.hex()}")
            }
        }
    }
}

fun ByteArray.hex(): String = if (isEmpty()) "(empty)" else joinToString(" ") { "%02X".format(it) }
