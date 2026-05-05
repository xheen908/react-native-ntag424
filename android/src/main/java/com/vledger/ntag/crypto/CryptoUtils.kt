package com.vledger.ntag.crypto

import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private const val AES_BLOCK_SIZE = 16

    fun getRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Derives a diversified key from a master key and tag UID using AES-CMAC.
     */
    fun diversifyKey(masterKey: ByteArray, uid: ByteArray): ByteArray {
        return calculateCmac(masterKey, uid)
    }

    /**
     * AES-128 CBC Encryption
     */
    fun encryptAes(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }

    /**
     * AES-128 CBC Decryption
     */
    fun decryptAes(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }

    /**
     * AES-CMAC algorithm according to NIST SP 800-38B
     */
    fun calculateCmac(key: ByteArray, data: ByteArray, iv: ByteArray? = null): ByteArray {
        val keySpec = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        // Step 1: Generate subkeys K1 and K2
        val L = cipher.doFinal(ByteArray(AES_BLOCK_SIZE))
        val K1 = generateSubkey(L)
        val K2 = generateSubkey(K1)

        // Step 2: Prepare blocks
        val n = (data.size + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE
        val blocks = if (n == 0) 1 else n
        val lastBlockIndex = blocks - 1

        var x = iv ?: ByteArray(AES_BLOCK_SIZE)
        var y: ByteArray

        for (i in 0 until lastBlockIndex) {
            y = xor(x, getBlock(data, i))
            x = cipher.doFinal(y)
        }

        // Last block handling
        val lastBlock = getBlock(data, lastBlockIndex)
        val flag = data.size > 0 && data.size % AES_BLOCK_SIZE == 0
        val paddedLastBlock = if (flag) {
            xor(lastBlock, K1)
        } else {
            xor(pad(lastBlock, data.size % AES_BLOCK_SIZE), K2)
        }

        y = xor(x, paddedLastBlock)
        return cipher.doFinal(y)
    }

    private fun generateSubkey(L: ByteArray): ByteArray {
        val res = shiftLeft(L)
        if ((L[0].toInt() and 0x80) != 0) {
            res[AES_BLOCK_SIZE - 1] = (res[AES_BLOCK_SIZE - 1].toInt() xor 0x87).toByte()
        }
        return res
    }

    private fun shiftLeft(data: ByteArray): ByteArray {
        val res = ByteArray(data.size)
        var bit = 0
        for (i in data.size - 1 downTo 0) {
            val resBit = (data[i].toInt() and 0x80) shr 7
            res[i] = ((data[i].toInt() shl 1) or bit).toByte()
            bit = resBit
        }
        return res
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val res = ByteArray(a.size)
        for (i in a.indices) {
            res[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return res
    }

    private fun getBlock(data: ByteArray, index: Int): ByteArray {
        val start = index * AES_BLOCK_SIZE
        var end = start + AES_BLOCK_SIZE
        if (end > data.size) end = data.size
        val block = ByteArray(AES_BLOCK_SIZE)
        System.arraycopy(data, start, block, 0, end - start)
        return block
    }

    fun rotateLeft(data: ByteArray): ByteArray {
        val res = ByteArray(data.size)
        System.arraycopy(data, 1, res, 0, data.size - 1)
        res[data.size - 1] = data[0]
        return res
    }

    fun rotateRight(data: ByteArray): ByteArray {
        val res = ByteArray(data.size)
        System.arraycopy(data, 0, res, 1, data.size - 1)
        res[0] = data[data.size - 1]
        return res
    }

    private fun pad(data: ByteArray, length: Int): ByteArray {
        val res = ByteArray(AES_BLOCK_SIZE)
        System.arraycopy(data, 0, res, 0, length)
        res[length] = 0x80.toByte()
        return res
    }

    fun truncateMac(mac: ByteArray): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            result[i] = mac[1 + i * 2]
        }
        return result
    }

    fun padEv2(data: ByteArray): ByteArray {
        val len = data.size
        val paddingLen = 16 - (len % 16)
        val padded = ByteArray(len + paddingLen)
        System.arraycopy(data, 0, padded, 0, len)
        padded[len] = 0x80.toByte()
        // Rest is already 0x00
        return padded
    }
}
