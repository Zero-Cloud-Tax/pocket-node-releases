package com.pocketnode.app.licensing

import com.pocketnode.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LicenseManagerTest {

    @Test
    fun validKeyPassesValidation() {
        val serial = "1A2B"
        val hmac = computeHmac(serial)
        val key = "PN-$hmac$serial"
        assertTrue(LicenseManager.isValidKey(key))
    }

    @Test
    fun tamperedKeyFailsValidation() {
        val serial = "1A2B"
        val hmac = computeHmac(serial)
        val key = "PN-$hmac$serial"
        val tampered = key.replaceRange(6, 7, "F")
        assertFalse(LicenseManager.isValidKey(tampered))
    }

    @Test
    fun wrongOrEmptyKeysFailValidation() {
        assertFalse(LicenseManager.isValidKey(""))
        assertFalse(LicenseManager.isValidKey("PN-1234"))
        assertFalse(LicenseManager.isValidKey("PN-XYZXYZXYZXYZXYZXYZXYZXYZXYZX1234"))
    }

    private fun computeHmac(serial: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(BuildConfig.PRO_HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(key)
        val hash = mac.doFinal(serial.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02X".format(it) }.take(28)
    }
}
