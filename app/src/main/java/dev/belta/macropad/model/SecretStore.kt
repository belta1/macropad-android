package dev.belta.macropad.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values used as `{secret:name}` in typed text (logins, addresses). Kept apart from the layout so
 * they never reach backup or export files, and encrypted with a key that lives in the Android
 * Keystore (it can't be copied off the tablet, so a copied preferences file is useless).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    val names: Set<String> get() = prefs.all.keys

    operator fun get(name: String): String? = prefs.getString(name, null)?.let(::decrypt)

    operator fun set(name: String, value: String?) {
        if (value.isNullOrEmpty()) prefs.edit().remove(name).apply()
        else encrypt(value)?.let { prefs.edit().putString(name, it).apply() }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        b64(cipher.iv) + ":" + b64(data)
    } catch (e: Exception) {
        Log.w("SecretStore", "could not save a value: $e")
        null
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w("SecretStore", "could not read a value: $e")
        null
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "macropad-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
