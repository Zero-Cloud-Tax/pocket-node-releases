package com.pocketnode.app.data

import android.content.Context
import java.io.File

data class StorageStats(
    val usedBytes: Long,
    val freeBytes: Long,
    val modelCount: Int,
    val modelsDirPath: String
)

object StorageUtils {
    fun compute(context: Context): StorageStats {
        val dir = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
        val files = dir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }
            ?: emptyArray()
        return StorageStats(
            usedBytes = files.sumOf { it.length() },
            freeBytes = dir.freeSpace,
            modelCount = files.size,
            modelsDirPath = dir.absolutePath
        )
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L         -> "%.0f KB".format(bytes / 1_000.0)
        else                    -> "$bytes B"
    }
}
