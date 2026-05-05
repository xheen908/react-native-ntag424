package com.vledger.ntag.nfc

import android.nfc.tech.IsoDep
import android.util.Log

class NfcManager(private val isoDep: IsoDep) {
    
    val tagId: ByteArray
        get() = isoDep.tag.id

    companion object {
        private const val TAG = "NfcManager"
        private const val DEFAULT_TIMEOUT = 5000 
    }

    init {
        try {
            isoDep.timeout = DEFAULT_TIMEOUT
        } catch (e: Exception) {
            Log.e(TAG, "Could not set timeout", e)
        }
    }

    fun connect() {
        if (!isoDep.isConnected) {
            isoDep.connect()
        }
    }

    fun close() {
        try {
            if (isoDep.isConnected) {
                isoDep.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing IsoDep", e)
        }
    }

    fun transceive(command: ByteArray): ByteArray {
        Log.d(TAG, "Sent: ${command.toHexString()}")
        val response = isoDep.transceive(command)
        Log.d(TAG, "Received: ${response.toHexString()}")
        return response
    }

    fun isSuccess(response: ByteArray, hasMac: Boolean = false): Boolean {
        if (response.size < 2) return false
        
        // In Secure Messaging (EV2), the Status Word (91XX) is followed by an 8-byte MAC.
        val trailerSize = if (hasMac) 10 else 2
        if (response.size < trailerSize) return false
        
        val sw1 = response[response.size - trailerSize]
        val sw2 = response[response.size - trailerSize + 1]
        
        return (sw1 == 0x91.toByte() && sw2 == 0x00.toByte()) || 
               (sw1 == 0x90.toByte() && sw2 == 0x00.toByte())
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02X".format(it) }
}
