package com.pahlevi.levi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Penyimpanan rahasia (misal sandi aplikasi Gmail), dienkripsi dengan kunci
 * di Android Keystore. Kunci tidak bisa dikeluarkan dari HP.
 */
class SecureStore(context: Context) {
    private val sp = context.getSharedPreferences("levi_secure", Context.MODE_PRIVATE)
    private val alias = "levi_secret_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            sp.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        sp.edit().putString(name, packed).apply()
    }

    fun get(name: String): String? {
        val packed = sp.getString(name, null) ?: return null
        return runCatching {
            val (ivB64, dataB64) = packed.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
}

/** Akun Gmail yang dihubungkan (alamat + sandi aplikasi). */
class MailAccountStore(context: Context, private val secure: SecureStore) {
    private val sp = context.getSharedPreferences("levi", Context.MODE_PRIVATE)

    val email: String? get() = sp.getString("mail_address", null)
    val password: String? get() = secure.get("mail_app_password")
    val isConnected: Boolean get() = !email.isNullOrBlank() && !password.isNullOrBlank()

    fun save(email: String, appPassword: String) {
        sp.edit().putString("mail_address", email.trim()).apply()
        secure.put("mail_app_password", appPassword.replace(" ", ""))
    }

    fun clear() {
        sp.edit().remove("mail_address").apply()
        secure.put("mail_app_password", null)
    }
}
