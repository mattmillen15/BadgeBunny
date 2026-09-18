package com.badgebunny.transport

/** A transport to a Proxmark running hf_cardhopper: Bluetooth (RDV4/BlueShark) or USB-CDC (PM5). */
interface Pm3Link {
    fun sendFrame(payload: ByteArray)
    fun recvFrame(): ByteArray
    fun isConnected(): Boolean
    fun close()
}
