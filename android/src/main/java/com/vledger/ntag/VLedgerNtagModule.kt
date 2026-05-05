package com.vledger.ntag

import android.os.Vibrator
import android.os.VibrationEffect
import android.content.Context
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.vledger.ntag.nfc.NfcManager
import com.vledger.ntag.service.Ntag424Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import expo.modules.kotlin.Promise

class VLedgerNtagModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("VLedgerNtag")

    Events("onFlashResult")

    Function("startAutoFlash") { masterKeyHex: String, baseUrl: String ->
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      if (adapter == null || !adapter.isEnabled) {
        throw Exception("NFC_DISABLED")
      }

      val masterKey = masterKeyHex.decodeHex()
      
      adapter.enableReaderMode(
        appContext.currentActivity,
        { tag ->
          val isoDep = IsoDep.get(tag)
          if (isoDep != null) {
            val nfcManager = NfcManager(isoDep)
            val service = Ntag424Service(nfcManager)
            
            CoroutineScope(Dispatchers.IO).launch {
              val result = service.initializeTag(masterKey, baseUrl)
              
              if (result.isSuccess) {
                vibrate(100)
                this@VLedgerNtagModule.sendEvent("onFlashResult", mapOf(
                  "success" to true,
                  "uid" to result.getOrNull()
                ))
              } else {
                this@VLedgerNtagModule.sendEvent("onFlashResult", mapOf(
                  "success" to false,
                  "message" to (result.exceptionOrNull()?.message ?: "Unknown error")
                ))
              }
            }
          }
        },
        NfcAdapter.FLAG_READER_NFC_A or 
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or 
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        null
      )
    }

    Function("startAutoReset") { masterKeyHex: String ->
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      if (adapter == null || !adapter.isEnabled) {
        throw Exception("NFC_DISABLED")
      }

      val masterKey = masterKeyHex.decodeHex()
      
      adapter.enableReaderMode(
        appContext.currentActivity,
        { tag ->
          val isoDep = IsoDep.get(tag)
          if (isoDep != null) {
            val nfcManager = NfcManager(isoDep)
            val service = Ntag424Service(nfcManager)
            
            CoroutineScope(Dispatchers.IO).launch {
              val result = service.resetTag(masterKey)
              
              if (result.isSuccess) {
                vibrate(200)
                this@VLedgerNtagModule.sendEvent("onFlashResult", mapOf(
                  "success" to true,
                  "reset" to true,
                  "uid" to result.getOrNull()
                ))
              } else {
                this@VLedgerNtagModule.sendEvent("onFlashResult", mapOf(
                  "success" to false,
                  "message" to (result.exceptionOrNull()?.message ?: "Reset failed")
                ))
              }
            }
          }
        },
        NfcAdapter.FLAG_READER_NFC_A or 
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or 
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        null
      )
    }

    Function("stopAutoReset") {
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      adapter?.disableReaderMode(appContext.currentActivity)
    }

    Function("stopAutoFlash") {
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      adapter?.disableReaderMode(appContext.currentActivity)
    }

    AsyncFunction("initializeTag") { masterKeyHex: String, baseUrl: String, promise: Promise ->
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      if (adapter == null || !adapter.isEnabled) {
        promise.reject("NFC_DISABLED", "NFC is not enabled", null)
        return@AsyncFunction
      }

      val masterKey = masterKeyHex.decodeHex()
      
      adapter.enableReaderMode(
        appContext.currentActivity,
        { tag ->
          val isoDep = IsoDep.get(tag)
          if (isoDep != null) {
            val nfcManager = NfcManager(isoDep)
            val service = Ntag424Service(nfcManager)
            
            CoroutineScope(Dispatchers.IO).launch {
              val result = service.initializeTag(masterKey, baseUrl)
              adapter.disableReaderMode(appContext.currentActivity)
              
              if (result.isSuccess) {
                vibrate(100)
                promise.resolve(mapOf(
                  "success" to true,
                  "uid" to result.getOrNull()
                ))
              } else {
                promise.reject("INIT_FAILED", result.exceptionOrNull()?.message, null)
              }
            }
          }
        },
        NfcAdapter.FLAG_READER_NFC_A or 
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or 
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        null
      )
    }

    AsyncFunction("getSunProof") { promise: Promise ->
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      if (adapter == null || !adapter.isEnabled) {
        promise.reject("NFC_DISABLED", "NFC is not enabled", null)
        return@AsyncFunction
      }

      adapter.enableReaderMode(
        appContext.currentActivity,
        { tag ->
          val isoDep = IsoDep.get(tag)
          if (isoDep != null) {
            val nfcManager = NfcManager(isoDep)
            val service = Ntag424Service(nfcManager)
            
            CoroutineScope(Dispatchers.IO).launch {
              val result = service.getSunProof()
              // WICHTIG: Wir entfernen disableReaderMode hier!
              // Der Reader bleibt aktiv, damit Android nicht sofort den Browser öffnet.
              
              if (result.isSuccess) {
                vibrate(100)
                promise.resolve(result.getOrNull())
              } else {
                // Bei Fehlern können wir ihn ggf. schließen oder offen lassen
                promise.reject("GET_PROOF_FAILED", result.exceptionOrNull()?.message, null)
              }
            }
          }
        },
        NfcAdapter.FLAG_READER_NFC_A or 
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or 
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        null
      )
    }

    Function("stopNfcReader") {
      val adapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      adapter?.disableReaderMode(appContext.currentActivity)
    }
  }

  private fun vibrate(duration: Long) {
    val vibrator = appContext.reactContext?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
  }

  private fun String.decodeHex(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
  }
}
