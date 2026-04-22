package com.pocketnode.app.licensing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Top-level DataStore delegate — one instance per process
val Context.licenseDataStore: DataStore<Preferences> by preferencesDataStore(name = "license")

// License key format: PN-[28 hex chars][4 hex serial]
// Keys are generated offline via keygen.sh using HMAC-SHA256.
// The secret below must match the one in keygen.sh.
private const val HMAC_SECRET = "pocketnode-pro-secret-2024"
private val KEY_LICENSE = stringPreferencesKey("license_key")

class LicenseManager(private val context: Context) {

    val savedKeyFlow: Flow<String> = context.licenseDataStore.data
        .map { prefs -> prefs[KEY_LICENSE] ?: "" }

    val isProFlow: Flow<Boolean> = savedKeyFlow.map { key -> isValidKey(key) }

    suspend fun validateAndSave(key: String): Boolean {
        val valid = isValidKey(key.trim())
        if (valid) {
            context.licenseDataStore.edit { prefs ->
                prefs[KEY_LICENSE] = key.trim().uppercase()
            }
        }
        return valid
    }

    suspend fun clearLicense() {
        context.licenseDataStore.edit { prefs -> prefs.remove(KEY_LICENSE) }
    }

    companion object {
        fun isValidKey(key: String): Boolean {
            val upper = key.trim().uppercase()
            if (!upper.startsWith("PN-")) return false
            val body = upper.removePrefix("PN-")
            if (body.length != 32) return false
            if (!body.all { it.isDigit() || it in 'A'..'F' }) return false
            val hmacPart = body.take(28)
            val serial = body.takeLast(4)
            return computeHmac(serial) == hmacPart
        }

        private fun computeHmac(serial: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(key)
            val hash = mac.doFinal(serial.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02X".format(it) }.take(28)
        }
    }
}
