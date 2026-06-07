package com.pocketnode.app.data

import java.io.File

data class GgufInspection(
    val ggufMagicValid: Boolean,
    val metadataConfidence: String,
    val metadataComplete: Boolean,
    val bytesInspected: Int,
    val version: Int? = null,
    val generalName: String? = null,
    val generalArchitecture: String? = null,
    val generalBasename: String? = null
) {
    val draftSignatureDetected: Boolean
        get() {
            val combined = listOfNotNull(generalName, generalArchitecture, generalBasename)
                .joinToString(" ")
                .lowercase()
            return combined.contains("draft") ||
                combined.contains("smollm2 135m") ||
                combined.contains("smollm2-135m")
        }

    fun summary(): String = buildString {
        append("ggufMagicValid=").append(ggufMagicValid)
        append(" metadataConfidence=").append(metadataConfidence)
        append(" metadataComplete=").append(metadataComplete)
        append(" general.name=").append(generalName ?: "unknown")
        append(" general.architecture=").append(generalArchitecture ?: "unknown")
        append(" general.basename=").append(generalBasename ?: "unknown")
    }
}

object GgufInspector {
    const val MAX_HEADER_BYTES = 64 * 1024

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    fun inspect(file: File, maxBytes: Int = MAX_HEADER_BYTES): GgufInspection {
        val boundedBytes = file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
        return inspectBytes(boundedBytes)
    }

    fun inspectBytes(bytes: ByteArray): GgufInspection {
        if (bytes.size < 4) {
            return GgufInspection(
                ggufMagicValid = false,
                metadataConfidence = "unknown",
                metadataComplete = false,
                bytesInspected = bytes.size
            )
        }
        if (!(bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'G'.code.toByte() &&
                bytes[2] == 'U'.code.toByte() &&
                bytes[3] == 'F'.code.toByte())
        ) {
            return GgufInspection(
                ggufMagicValid = false,
                metadataConfidence = "unknown",
                metadataComplete = false,
                bytesInspected = bytes.size
            )
        }

        val reader = ByteArrayReader(bytes)
        reader.skip(4)
        val version = reader.readUInt32()?.toInt()
        if (version == null) {
            return GgufInspection(
                ggufMagicValid = true,
                metadataConfidence = "unknown",
                metadataComplete = false,
                bytesInspected = bytes.size
            )
        }

        val tensorCount = if (version == 1) reader.readUInt32()?.toLong() else reader.readUInt64()
        val metadataCount = if (version == 1) reader.readUInt32()?.toLong() else reader.readUInt64()
        if (tensorCount == null || metadataCount == null) {
            return GgufInspection(
                ggufMagicValid = true,
                metadataConfidence = "unknown",
                metadataComplete = false,
                bytesInspected = bytes.size,
                version = version
            )
        }

        var generalName: String? = null
        var generalArchitecture: String? = null
        var generalBasename: String? = null
        var metadataComplete = true

        for (index in 0 until metadataCount) {
            val key = reader.readSizedString()
            if (key == null) {
                metadataComplete = false
                break
            }
            val valueType = reader.readUInt32()?.toInt()
            if (valueType == null) {
                metadataComplete = false
                break
            }

            val parsedString = if (
                key == "general.name" ||
                key == "general.architecture" ||
                key == "general.basename"
            ) {
                reader.readTypedValueAsString(valueType)
            } else {
                if (!reader.skipTypedValue(valueType)) {
                    metadataComplete = false
                    break
                }
                null
            }

            when (key) {
                "general.name" -> generalName = parsedString
                "general.architecture" -> generalArchitecture = parsedString
                "general.basename" -> generalBasename = parsedString
            }

            if (generalName != null && generalArchitecture != null && generalBasename != null) {
                break
            }
        }

        return GgufInspection(
            ggufMagicValid = true,
            metadataConfidence = if (generalName != null || generalArchitecture != null || generalBasename != null) {
                "structured"
            } else {
                "unknown"
            },
            metadataComplete = metadataComplete,
            bytesInspected = bytes.size,
            version = version,
            generalName = generalName,
            generalArchitecture = generalArchitecture,
            generalBasename = generalBasename
        )
    }

    private class ByteArrayReader(private val bytes: ByteArray) {
        private var offset = 0

        fun skip(count: Int): Boolean {
            if (offset + count > bytes.size) return false
            offset += count
            return true
        }

        fun readUInt32(): Long? {
            if (offset + 4 > bytes.size) return null
            val value =
                (bytes[offset].toLong() and 0xff) or
                    ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                    ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                    ((bytes[offset + 3].toLong() and 0xff) shl 24)
            offset += 4
            return value
        }

        fun readUInt64(): Long? {
            if (offset + 8 > bytes.size) return null
            var value = 0L
            repeat(8) { index ->
                value = value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
            }
            offset += 8
            return value
        }

        fun readSizedString(): String? {
            val length = readUInt64() ?: return null
            if (length < 0 || length > Int.MAX_VALUE) return null
            val intLength = length.toInt()
            if (offset + intLength > bytes.size) return null
            val value = bytes.copyOfRange(offset, offset + intLength).toString(Charsets.UTF_8)
            offset += intLength
            return value
        }

        fun readTypedValueAsString(valueType: Int): String? {
            return when (valueType) {
                TYPE_STRING -> readSizedString()
                TYPE_BOOL -> readUInt8()?.let { if (it == 0) "false" else "true" }
                TYPE_UINT8, TYPE_INT8 -> readUInt8()?.toString()
                TYPE_UINT16, TYPE_INT16 -> readUInt16()?.toString()
                TYPE_UINT32, TYPE_INT32 -> readUInt32()?.toString()
                TYPE_UINT64, TYPE_INT64 -> readUInt64()?.toString()
                TYPE_FLOAT32 -> readUInt32()?.let { Float.fromBits(it.toInt()).toString() }
                TYPE_FLOAT64 -> readUInt64()?.let { Double.fromBits(it).toString() }
                else -> {
                    if (skipTypedValue(valueType)) null else null
                }
            }
        }

        fun skipTypedValue(valueType: Int): Boolean {
            return when (valueType) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skip(1)
                TYPE_UINT16, TYPE_INT16 -> skip(2)
                TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skip(4)
                TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skip(8)
                TYPE_STRING -> {
                    val length = readUInt64() ?: return false
                    if (length < 0 || length > Int.MAX_VALUE) return false
                    skip(length.toInt())
                }
                TYPE_ARRAY -> {
                    val itemType = readUInt32()?.toInt() ?: return false
                    val count = readUInt64() ?: return false
                    if (count < 0 || count > Int.MAX_VALUE) return false
                    repeat(count.toInt()) {
                        if (!skipTypedValue(itemType)) return false
                    }
                    true
                }
                else -> false
            }
        }

        private fun readUInt8(): Int? {
            if (offset + 1 > bytes.size) return null
            return (bytes[offset++].toInt() and 0xff)
        }

        private fun readUInt16(): Int? {
            if (offset + 2 > bytes.size) return null
            val value =
                (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8)
            offset += 2
            return value
        }
    }
}
