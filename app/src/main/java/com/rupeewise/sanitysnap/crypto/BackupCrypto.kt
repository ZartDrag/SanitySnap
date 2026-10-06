package com.rupeewise.sanitysnap.crypto

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for the manual backup file:
 * PBKDF2-HMAC-SHA256 (210k iterations) -> AES-256-GCM. All primitives exist on API 26+.
 */
object BackupCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    @Serializable
    data class Envelope(
        val format: String = "fairshare-backup",
        val version: Int = 1,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int = ITERATIONS,
        val cipher: String = "AES/GCM/NoPadding",
        val salt: String,
        val iv: String,
        val ciphertext: String,
    )

    fun encrypt(plaintext: ByteArray, password: CharArray): Envelope {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val key = derive(password, salt, ITERATIONS)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        c.updateAAD("fairshare-backup-v1".toByteArray())
        return Envelope(
            salt = CryptoUtil.b64(salt),
            iv = CryptoUtil.b64(iv),
            ciphertext = CryptoUtil.b64(c.doFinal(plaintext)),
        )
    }

    /** Throws on wrong password / tampering (GCM tag mismatch). */
    fun decrypt(env: Envelope, password: CharArray): ByteArray {
        require(env.format == "fairshare-backup") { "Not a SanitySnap backup file" }
        val key = derive(password, CryptoUtil.unb64(env.salt), env.iterations)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, CryptoUtil.unb64(env.iv)))
        c.updateAAD("fairshare-backup-v1".toByteArray())
        return c.doFinal(CryptoUtil.unb64(env.ciphertext))
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(password, salt, iterations, KEY_BITS)).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
