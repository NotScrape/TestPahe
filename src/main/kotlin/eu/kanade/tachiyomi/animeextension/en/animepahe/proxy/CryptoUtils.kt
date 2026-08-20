package eu.kanade.tachiyomi.animeextension.en.animepahe.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    fun decryptAes128Cbc(bytes: ByteArray, key: ByteArray, iv: String): ByteArray {
        val ivClean = iv.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(ivClean.hexToByteArray())
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(bytes)
    }
}
