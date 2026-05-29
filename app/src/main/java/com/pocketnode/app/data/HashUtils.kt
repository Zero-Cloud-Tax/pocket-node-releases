package com.pocketnode.app.data

import java.io.File
import java.security.MessageDigest

object HashUtils {

    // Verified SHA-256 hashes for known-good model files.
    // Only entries here can show "Verified" badge — all others show "Unknown Hash".
    val KNOWN_HASHES: Map<String, String> = mapOf(
        "PocketNode_Operator_Q4_0" to
            "b1de55dff5815fc0dd898491295b064e7fea07368d603c82740288f8d3bb50ba"
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { stream ->
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
