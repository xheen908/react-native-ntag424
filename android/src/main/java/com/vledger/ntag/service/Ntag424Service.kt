package com.vledger.ntag.service

import android.util.Log
import com.vledger.ntag.nfc.NfcManager
import net.bplearning.ntag424.DnaCommunicator
import net.bplearning.ntag424.command.ChangeFileSettings
import net.bplearning.ntag424.command.ChangeKey
import net.bplearning.ntag424.command.FileSettings
import net.bplearning.ntag424.command.GetCardUid
import net.bplearning.ntag424.command.WriteData
import net.bplearning.ntag424.constants.Ntag424
import net.bplearning.ntag424.constants.Permissions
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode
import net.bplearning.ntag424.sdm.NdefTemplateMaster
import net.bplearning.ntag424.sdm.SDMSettings
import net.bplearning.ntag424.util.ByteUtil
import java.nio.charset.StandardCharsets

class Ntag424Service(private val nfc: NfcManager) {

    companion object {
        private const val TAG = "Ntag424Service"
    }

    private val communicator = DnaCommunicator()

    init {
        // Verbinde die Library mit unserem NfcManager
        communicator.setTransceiver { bytesToSend ->
            nfc.transceive(bytesToSend)
        }
        communicator.setLogger { message ->
            Log.d(TAG, "Communicator: $message")
        }
    }

    suspend fun getSunProof(): Result<Map<String, String>> {
        return try {
            nfc.connect()
            communicator.beginCommunication()

            // Read NDEF file
            val ndefData = net.bplearning.ntag424.command.ReadData.run(communicator, Ntag424.NDEF_FILE_NUMBER, 0, 0)
            if (ndefData == null || ndefData.isEmpty()) {
                throw Exception("Could not read NDEF data")
            }

            // The NDEF file on NTAG 424 begins with a 2-byte length field.
            // We'll convert the whole thing to a string and then look for our markers.
            val urlString = String(ndefData, StandardCharsets.UTF_8)
            Log.d(TAG, "Raw NDEF content: $urlString")

            // Robust parsing for p and c (ignoring surrounding characters)
            val piccData = if (urlString.contains("p=")) {
                urlString.substringAfter("p=").substringBefore("&").substringBefore("\u0000").trim()
            } else ""

            val mac = if (urlString.contains("c=")) {
                urlString.substringAfter("c=").substringBefore("&").substringBefore("\u0000").trim()
            } else ""
            
            // Extract the base path (everything before the first SDM parameter)
            val urlPath = urlString.substringBefore("?p=").substringAfter("https://").let { "https://$it" }

            if (piccData.isEmpty() || mac.isEmpty()) {
                throw Exception("SDM Parameter nicht gefunden. Inhalt: ${urlString.take(50)}...")
            }

            Result.success(mapOf(
                "uid" to nfc.tagId.toHexString().uppercase(),
                "piccData" to piccData,
                "cmac" to mac,
                "urlPath" to urlPath
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SUN proof: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { nfc.close() } catch (_: Exception) {}
        }
    }

    suspend fun resetTag(masterKey: ByteArray): Result<String> {
        return try {
            nfc.connect()
            Log.d(TAG, "Beginning communication for reset...")
            communicator.beginCommunication()

            Log.d(TAG, "Authenticating with Master Key for reset...")
            val authSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
            if (!authSuccess) {
                throw Exception("Authentication failed - wrong Master Key?")
            }

            val cardUid = GetCardUid.run(communicator)
            val uidHex = ByteUtil.byteToHex(cardUid)
            Log.d(TAG, "Card UID: $uidHex")

            // 1. SDM Deaktivieren & Datei-Einstellungen zurücksetzen
            Log.d(TAG, "Resetting File Settings...")
            val defaultSettings = FileSettings()
            defaultSettings.commMode = net.bplearning.ntag424.CommunicationMode.PLAIN
            defaultSettings.readPerm = Permissions.ACCESS_EVERYONE
            defaultSettings.writePerm = Permissions.ACCESS_EVERYONE
            defaultSettings.readWritePerm = Permissions.ACCESS_EVERYONE
            defaultSettings.changePerm = Permissions.ACCESS_KEY0
            defaultSettings.sdmSettings = null // SDM aus
            
            ChangeFileSettings.run(communicator, Ntag424.NDEF_FILE_NUMBER, defaultSettings)

            // 2. Alle Keys (1-4) auf Werkseinstellung (00...00)
            val factoryKey = ByteArray(16) { 0 }
            val factoryKeyVersion = ByteArray(1) { 0 }
            Log.d(TAG, "Resetting Keys 1-4...")
            for (i in 1..4) {
                ChangeKey.run(communicator, i, factoryKey, factoryKeyVersion, 0)
            }

            // 3. Master Key (0) auf Werkseinstellung (00...00)
            Log.d(TAG, "Resetting Master Key (0)...")
            ChangeKey.run(communicator, 0, factoryKey, factoryKeyVersion, 0)

            Log.d(TAG, "✅ Tag successfully formatted to factory defaults")
            Result.success(uidHex)
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { nfc.close() } catch (_: Exception) {}
        }
    }

    suspend fun initializeTag(masterKey: ByteArray, baseUrl: String): Result<String> {
        return try {
            nfc.connect()

            Log.d(TAG, "Beginning communication with NTAG 424 DNA...")
            communicator.beginCommunication() // Sends ISO SELECT NDEF AID

            Log.d(TAG, "Authenticating with Master Key...")
            val authSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
            if (!authSuccess) {
                throw Exception("Authentication failed")
            }
            Log.d(TAG, "Auth successful")

            val cardUid = GetCardUid.run(communicator)
            val uidHex = ByteUtil.byteToHex(cardUid)
            Log.d(TAG, "Card UID: $uidHex")

            val fullUrl = buildFullUrl(baseUrl, uidHex)
            writeAndConfigureSdm(fullUrl)

            Result.success(uidHex)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { nfc.close() } catch (_: Exception) {}
        }
    }

    private fun writeAndConfigureSdm(fullUrl: String) {
        // SDM Einstellungen vorbereiten
        val sdmSettings = SDMSettings()
        sdmSettings.sdmMetaReadPerm = Permissions.ACCESS_KEY2 // Key 2 für verschlüsselte PICC Daten
        sdmSettings.sdmFileReadPerm = Permissions.ACCESS_KEY3 // Key 3 für MAC/File-Verschlüsselung
        sdmSettings.sdmOptionUid = true
        sdmSettings.sdmOptionReadCounter = true
        sdmSettings.sdmOptionUseAscii = true

        // NDEF Template Master erstellt den Record mit Platzhaltern
        val master = NdefTemplateMaster()
        master.usesLRP = false
        
        // Template generieren (PICC/FILE/MAC Platzhalter werden von der Library verwaltet)
        val ndefRecord = master.generateNdefTemplateFromUrlString(fullUrl, null, sdmSettings)
        
        Log.d(TAG, "Writing NDEF Record...")
        WriteData.run(communicator, Ntag424.NDEF_FILE_NUMBER, ndefRecord)
        Log.d(TAG, "NDEF Written successfully")

        // Jetzt die Datei-Einstellungen ändern, um SDM zu aktivieren
        Log.d(TAG, "Changing File Settings to enable SDM...")
        val ndefFileSettings = FileSettings()
        ndefFileSettings.commMode = net.bplearning.ntag424.CommunicationMode.PLAIN
        ndefFileSettings.readPerm = Permissions.ACCESS_EVERYONE
        ndefFileSettings.writePerm = Permissions.ACCESS_KEY0
        ndefFileSettings.readWritePerm = Permissions.ACCESS_KEY3
        ndefFileSettings.changePerm = Permissions.ACCESS_KEY0
        ndefFileSettings.sdmSettings = sdmSettings
        
        ChangeFileSettings.run(communicator, Ntag424.NDEF_FILE_NUMBER, ndefFileSettings)
        Log.d(TAG, "✅ SDM Configuration completed successfully")
    }

    private fun buildFullUrl(baseUrl: String, uidHex: String): String {
        val clean = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        // Wir setzen die UID in den Pfad und nutzen die Library-Platzhalter {PICC} und {MAC}
        return "$clean/$uidHex?p={PICC}&c={MAC}"
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02X".format(it) }
}