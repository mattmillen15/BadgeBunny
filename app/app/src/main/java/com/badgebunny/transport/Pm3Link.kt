package com.badgebunny.transport

/** A transport to a Proxmark running hf_cardhopper: Bluetooth (RDV4/BlueShark) or USB-CDC (PM5). */
interface Pm3Link {
    companion object {
        // PacketCommandNG for CMD_STANDALONE (0x0115), no data, CRC = magic placeholder.
        // Triggers hf_cardhopper standalone on the Proxmark before cardhopper framing starts.
        // Wire: [magic:4 LE][len=0,ng=1:2 LE][cmd:2 LE][crc=POSTAMBLE_MAGIC:2 LE]
        val CMD_STANDALONE_NG = byteArrayOf(
            0x50, 0x4D, 0x33, 0x61,  // COMMANDNG_PREAMBLE_MAGIC (PM3a)
            0x00, 0x80.toByte(),      // length=0, ng=true (bit 15)
            0x15, 0x01,               // CMD_STANDALONE = 0x0115
            0x61, 0x33                // COMMANDNG_POSTAMBLE_MAGIC (placeholder, firmware accepts it)
        )

        // Cardhopper "RESTART" mode frame (magicRSRT in hf_cardhopper.c). Breaks the firmware
        // out of its become_reader/become_card relay loop back to the mode-select loop so the
        // next READ/CARD starts a fresh session — exactly how the reference hopper.py resets
        // the PM3 for reuse without a power cycle. Sent as a normal cardhopper frame.
        val CMD_RESTART = byteArrayOf(0x52, 0x45, 0x53, 0x54, 0x41, 0x52, 0x54) // "RESTART"
    }

    fun sendFrame(payload: ByteArray)
    fun recvFrame(): ByteArray
    fun isConnected(): Boolean
    fun close()

    /** Discard any buffered PM3 output (leftover from an aborted session) so the next reset's
     *  framing starts clean. Default no-op; transports back it with CardhopperCodec.drain(). */
    fun drainInput() {}

    /** Reset the firmware's mode loop for a fresh session. Transport-agnostic: every transport
     *  frames this through CardhopperCodec, so USB, BLE and Bluetooth all reset identically. */
    fun reset() { sendFrame(CMD_RESTART) }
}
