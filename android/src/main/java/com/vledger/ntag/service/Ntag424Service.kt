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

    private fun forceResetChipState() {
        try {
            // ISO Select Application (NDEF AID: D2760000850101)
            val selectAppApdu = byteArrayOf(
                0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(),
                0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte(),
                0x00.toByte()
            )
            nfc.transceive(selectAppApdu)
        } catch (e: Exception) {
            Log.w(TAG, "forceResetChipState failed", e)
        }
        communicator.beginCommunication()
    }

    suspend fun resetTag(masterKey: ByteArray): Result<String> {
        return try {
            nfc.connect()
            Log.d(TAG, "Beginning communication for reset...")
            communicator.beginCommunication()

            val factoryKey = ByteArray(16) { 0 }
            Log.d(TAG, "Authenticating with Master Key for reset...")
            var isAlreadyFactory = false
            var authSuccess = false
            try {
                authSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
            } catch (e: Exception) {}

            if (!authSuccess) {
                Log.d(TAG, "Master Key auth failed. Trying Factory Key...")
                try {
                    forceResetChipState()
                    authSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, factoryKey)
                    if (authSuccess) {
                        isAlreadyFactory = true
                    }
                } catch (e: Exception) {}
            }

            if (!authSuccess) {
                throw Exception("Authentication failed - neither Brand Key nor Factory Key works.")
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
            val sdmSettings = SDMSettings()
            sdmSettings.sdmEnabled = false
            defaultSettings.sdmSettings = sdmSettings
            
            ChangeFileSettings.run(communicator, Ntag424.NDEF_FILE_NUMBER, defaultSettings)

            // 1b. NDEF-Inhalt komplett löschen (Länge 0 schreiben)
            Log.d(TAG, "Clearing NDEF URL/content...")
            val emptyNdef = byteArrayOf(0x00, 0x00)
            WriteData.run(communicator, Ntag424.NDEF_FILE_NUMBER, emptyNdef)

            if (!isAlreadyFactory) {
                // 2. Alle Keys (1-4) auf Werkseinstellung (00...00)
                Log.d(TAG, "Resetting Keys 1-4...")
                for (i in 1..4) {
                    try {
                        ChangeKey.run(communicator, i, masterKey, factoryKey, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not reset Key $i with masterKey as oldKey. Trying factoryKey...", e)
                        try {
                            forceResetChipState()
                            AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
                            ChangeKey.run(communicator, i, factoryKey, factoryKey, 0)
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to reset Key $i completely", e2)
                            forceResetChipState()
                            AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
                        }
                    }
                }

                // 3. Master Key (0) auf Werkseinstellung (00...00)
                Log.d(TAG, "Resetting Master Key (0)...")
                ChangeKey.run(communicator, 0, masterKey, factoryKey, 0)
            } else {
                Log.d(TAG, "Chip was already factory reset, skipped key changes.")
            }

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

            val factoryKey = ByteArray(16) { 0 }
            var isFactoryChip = false
            var authSuccess = false

            Log.d(TAG, "Attempting authentication...")
            // Try factory key first (most common for fresh/formatted tags)
            Log.d(TAG, "Trying factory default key...")
            try {
                authSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, factoryKey)
                if (authSuccess) {
                    isFactoryChip = true
                    Log.d(TAG, "Factory key auth successful!")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Factory key auth threw error, will try custom key.")
            }

            if (!authSuccess) {
                Log.d(TAG, "Factory key failed. Resetting communication to try custom Master Key...")
                try {
                    forceResetChipState()
                } catch (_: Exception) {}

                Log.d(TAG, "Trying custom Master Key...")
                try {
                    authSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
                } catch (e: Exception) {
                    Log.d(TAG, "Custom master key auth threw error.")
                }

                if (authSuccess) {
                    Log.d(TAG, "Custom key auth successful!")
                } else {
                    // Try fallback keys from previous configurations to migrate the tag
                    val fallbackEmails = listOf(
                        "staff_1782764710219@tenantnull.local",
                        "admin@easyfisk.local",
                        "cheffe.nix",
                        "admin@openpos.de",
                        "xheen908",
                        "user1",
                        "user1@openpos.de",
                        "admin"
                    )
                    for (email in fallbackEmails) {
                        try {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            val fallbackKey = digest.digest(email.toByteArray(java.nio.charset.StandardCharsets.UTF_8)).sliceArray(0 until 16)
                            Log.d(TAG, "Trying fallback key derived from: $email (${fallbackKey.toHexString()})")
                            
                            // Only reset state if the tag is still physically connected
                            try {
                                forceResetChipState()
                            } catch (e: Exception) {
                                Log.w(TAG, "forceResetChipState failed during fallback loop for $email", e)
                            }
                            
                            val fallbackAuthSuccess = AESEncryptionMode.authenticateEV2(communicator, 0, fallbackKey)
                            if (fallbackAuthSuccess) {
                                Log.d(TAG, "Fallback key auth successful! Overwriting tag keys with new Master Key...")
                                
                                // Change Keys 1-4 from fallback key to new master key
                                for (i in 1..4) {
                                    var keyChanged = false
                                    // Try all fallback keys as the old key for Key i
                                    for (oldEmail in fallbackEmails) {
                                        try {
                                            val oldKeyDigest = java.security.MessageDigest.getInstance("SHA-256")
                                            val oldKey = oldKeyDigest.digest(oldEmail.toByteArray(java.nio.charset.StandardCharsets.UTF_8)).sliceArray(0 until 16)
                                            ChangeKey.run(communicator, i, oldKey, masterKey, 0)
                                            keyChanged = true
                                            Log.d(TAG, "Key $i successfully changed using old key from: $oldEmail")
                                            break
                                        } catch (e: Exception) {
                                            // Try next old key candidate
                                        }
                                    }
                                    if (!keyChanged) {
                                        // Try factory key (zeros) as fallback old key
                                        try {
                                            ChangeKey.run(communicator, i, ByteArray(16) { 0 }, masterKey, 0)
                                            Log.d(TAG, "Key $i successfully changed using factory key (zeros)")
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Could not change Key $i with any fallback or factory key", e)
                                        }
                                    }
                                }
                                // Change Master Key (0) from fallback key to new master key
                                ChangeKey.run(communicator, 0, fallbackKey, masterKey, 0)
                                Log.d(TAG, "Keys successfully updated from fallback to new Master Key!")
                                
                                // Re-authenticate with new master key
                                try {
                                    forceResetChipState()
                                } catch (_: Exception) {}
                                val reAuth = AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
                                if (reAuth) {
                                    authSuccess = true
                                    break
                                }
                            }
                        } catch (err: Exception) {
                            Log.d(TAG, "Fallback key auth failed for: $email - Error: ${err.message}")
                        }
                    }
                }

                if (!authSuccess) {
                    throw Exception("Authentication failed - neither custom, fallback nor factory key works")
                }
            }

            if (isFactoryChip) {
                Log.d(TAG, "Changing keys to custom Master Key...")
                // Change Keys 1-4 to custom key (trying factoryKey first, then masterKey if already set)
                for (i in 1..4) {
                    try {
                        ChangeKey.run(communicator, i, factoryKey, masterKey, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not change Key $i with factoryKey as oldKey. Trying masterKey...", e)
                        try {
                            forceResetChipState()
                            AESEncryptionMode.authenticateEV2(communicator, 0, factoryKey)
                            ChangeKey.run(communicator, i, masterKey, masterKey, 0)
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to change Key $i", e2)
                            forceResetChipState()
                            AESEncryptionMode.authenticateEV2(communicator, 0, factoryKey)
                        }
                    }
                }
                // Change Master Key (0) to custom key (using factoryKey as oldKey)
                ChangeKey.run(communicator, 0, factoryKey, masterKey, 0)
                Log.d(TAG, "Keys successfully updated to custom Brand Key!")
                
                // Re-authenticate because changing Key 0 drops the EV2 session
                Log.d(TAG, "Re-authenticating with new Master Key...")
                forceResetChipState()
                val reAuth = AESEncryptionMode.authenticateEV2(communicator, 0, masterKey)
                if (!reAuth) {
                    throw Exception("Failed to re-authenticate with new Master Key")
                }
            }

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