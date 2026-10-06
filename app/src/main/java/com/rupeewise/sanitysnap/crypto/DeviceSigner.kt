package com.rupeewise.sanitysnap.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Device identity = an EC P-256 keypair. The deviceId is derived from the public key so a peer
 * cannot claim another device's id with a different key.
 */
interface DeviceSigner {
    val publicKeyB64: String
    val deviceId: String get() = CryptoUtil.deviceIdFor(publicKeyB64)
    fun sign(data: ByteArray): ByteArray
}

object CryptoUtil {
    const val SIG_ALG = "SHA256withECDSA"

    fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 16 hex chars of SHA-256(public key bytes). */
    fun deviceIdFor(publicKeyB64: String): String = sha256Hex(unb64(publicKeyB64)).take(16)

    fun publicKeyFrom(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(b64)))

    fun verify(publicKeyB64: String, data: ByteArray, signatureB64: String): Boolean = try {
        Signature.getInstance(SIG_ALG).run {
            initVerify(publicKeyFrom(publicKeyB64))
            update(data)
            verify(unb64(signatureB64))
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Real device key: generated inside the Android Keystore (hardware-backed where available).
 * The private key never leaves the Keystore, so it is NOT included in backups; a restored
 * install gets a new device key but keeps the same userId (documented in README).
 */
class KeystoreSigner(private val alias: String = DEFAULT_ALIAS) : DeviceSigner {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(alias)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            kpg.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair()
        }
    }

    override val publicKeyB64: String by lazy {
        CryptoUtil.b64(keyStore.getCertificate(alias).publicKey.encoded)
    }

    override fun sign(data: ByteArray): ByteArray {
        val pk = keyStore.getKey(alias, null) as PrivateKey
        return Signature.getInstance(CryptoUtil.SIG_ALG).run {
            initSign(pk)
            update(data)
            sign()
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "fairshare_device_key_v1"
    }
}

/**
 * Software key persisted in SharedPreferences. ONLY used for the in-app simulated peer device
 * that powers fake sync demos. Never use for the real user identity.
 */
class SoftwareSigner private constructor(private val keyPair: KeyPair) : DeviceSigner {
    override val publicKeyB64: String = CryptoUtil.b64(keyPair.public.encoded)

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance(CryptoUtil.SIG_ALG).run {
        initSign(keyPair.private)
        update(data)
        sign()
    }

    companion object {
        fun generate(): SoftwareSigner {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            return SoftwareSigner(kpg.generateKeyPair())
        }

        fun loadOrCreate(context: Context, prefsName: String): SoftwareSigner {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val pub = prefs.getString("pub", null)
            val priv = prefs.getString("priv", null)
            if (pub != null && priv != null) {
                val kf = KeyFactory.getInstance("EC")
                val kp = KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(CryptoUtil.unb64(pub))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(CryptoUtil.unb64(priv))),
                )
                return SoftwareSigner(kp)
            }
            val s = generate()
            prefs.edit()
                .putString("pub", CryptoUtil.b64(s.keyPair.public.encoded))
                .putString("priv", CryptoUtil.b64(s.keyPair.private.encoded))
                .apply()
            return s
        }

        fun reset(context: Context, prefsName: String) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
