package com.pocketnode.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class ModelAuditRecord(
    val modelId: String,
    val displayName: String,
    val role: String,
    val isPrimary: Boolean,
    val isDraft: Boolean,
    val verificationStatus: String,
    val resolvedPath: String,
    val resolvedFileName: String,
    val fileExists: Boolean,
    val fileSizeBytes: Long,
    val sha256Prefix: String?,
    val ggufMagicValid: Boolean,
    val metadataConfidence: String,
    val generalName: String?,
    val generalArchitecture: String?,
    val generalBasename: String?
)

data class ImportIntent(
    val displayName: String,
    val intendedRole: String,
    val familyHint: String? = null,
    val expectedSha256: String? = null
)

data class ImportedArtifact(
    val file: File,
    val bytesCopied: Long,
    val sha256: String,
    val verificationStatus: String,
    val inspection: GgufInspection,
    val family: String?,
    val quantization: String?
)

data class CleanupResult(
    val modelId: String,
    val deletedFile: Boolean,
    val deletedRecord: Boolean,
    val skippedReason: String? = null
)

object ModelArtifactManager {
    private const val TAG = "PocketNode"
    private const val PRIMARY_OPERATOR_NAME = "PocketNode_Operator_Q4_0"
    const val MIN_MODEL_BYTES = 10_000_000L
    const val MIN_PRIMARY_OPERATOR_BYTES = 200L * 1024L * 1024L

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun createAuditRecord(model: LocalModel): ModelAuditRecord {
        val file = File(model.path)
        val exists = file.exists() && file.isFile
        val inspection = if (exists) {
            runCatching { GgufInspector.inspect(file) }.getOrElse {
                GgufInspection(
                    ggufMagicValid = false,
                    metadataConfidence = "unknown",
                    metadataComplete = false,
                    bytesInspected = 0
                )
            }
        } else {
            GgufInspection(
                ggufMagicValid = false,
                metadataConfidence = "unknown",
                metadataComplete = false,
                bytesInspected = 0
            )
        }
        val shaPrefix = when {
            model.sha256 != null -> model.sha256.take(16)
            exists && file.length() <= 150L * 1024L * 1024L ->
                runCatching { HashUtils.sha256(file).take(16) }.getOrNull()
            else -> null
        }
        return ModelAuditRecord(
            modelId = model.id,
            displayName = model.name,
            role = model.role,
            isPrimary = model.role != ModelRole.DRAFT.name,
            isDraft = model.role == ModelRole.DRAFT.name,
            verificationStatus = model.verificationStatus,
            resolvedPath = model.path,
            resolvedFileName = file.name,
            fileExists = exists,
            fileSizeBytes = if (exists) file.length() else 0L,
            sha256Prefix = shaPrefix,
            ggufMagicValid = inspection.ggufMagicValid,
            metadataConfidence = inspection.metadataConfidence,
            generalName = inspection.generalName,
            generalArchitecture = inspection.generalArchitecture,
            generalBasename = inspection.generalBasename
        )
    }

    fun logAudit(records: List<ModelAuditRecord>) {
        records.forEach { record ->
            Log.i(
                TAG,
                "Model audit: id=${record.modelId} name=${record.displayName} role=${record.role} " +
                    "isPrimary=${record.isPrimary} isDraft=${record.isDraft} status=${record.verificationStatus} " +
                    "path=${record.resolvedPath} fileName=${record.resolvedFileName} exists=${record.fileExists} " +
                    "fileSizeBytes=${record.fileSizeBytes} sha256Prefix=${record.sha256Prefix ?: "unknown"} " +
                    "ggufMagicValid=${record.ggufMagicValid} metadataConfidence=${record.metadataConfidence} " +
                    "general.name=${record.generalName ?: "unknown"} " +
                    "general.architecture=${record.generalArchitecture ?: "unknown"} " +
                    "general.basename=${record.generalBasename ?: "unknown"}"
            )
        }

        records.groupBy { it.resolvedPath }
            .filter { (_, grouped) -> grouped.size > 1 }
            .forEach { (path, grouped) ->
                Log.i(
                    TAG,
                    "Model audit duplicate-path: path=$path modelIds=${grouped.joinToString(",") { it.modelId }} names=${grouped.joinToString(",") { it.displayName }}"
                )
            }

        records.filter { it.sha256Prefix != null }
            .groupBy { it.sha256Prefix }
            .filter { (_, grouped) -> grouped.size > 1 }
            .forEach { (shaPrefix, grouped) ->
                Log.i(
                    TAG,
                    "Model audit duplicate-hash: sha256Prefix=$shaPrefix modelIds=${grouped.joinToString(",") { it.modelId }} names=${grouped.joinToString(",") { it.displayName }}"
                )
            }
    }

    fun cleanupFailedPrimaryArtifact(model: LocalModel, appModelsDir: File): CleanupResult {
        if (model.role == ModelRole.DRAFT.name) {
            return CleanupResult(model.id, deletedFile = false, deletedRecord = false, skippedReason = "draft_model")
        }
        if (model.verificationStatus != VerificationStatus.FAILED) {
            return CleanupResult(model.id, deletedFile = false, deletedRecord = false, skippedReason = "not_failed")
        }
        val file = File(model.path)
        if (!isAppOwnedModelFile(file, appModelsDir)) {
            return CleanupResult(model.id, deletedFile = false, deletedRecord = false, skippedReason = "not_app_owned")
        }
        if (!file.exists()) {
            return CleanupResult(model.id, deletedFile = false, deletedRecord = false)
        }
        val deleted = file.delete()
        return if (deleted) {
            CleanupResult(model.id, deletedFile = true, deletedRecord = false)
        } else {
            CleanupResult(model.id, deletedFile = false, deletedRecord = false, skippedReason = "physical_delete_failed")
        }
    }

    fun importFromUri(
        context: Context,
        uri: Uri,
        destinationName: String,
        intent: ImportIntent
    ): ImportedArtifact {
        val resolver = context.contentResolver
        val sourceName = uri.toString()
        return resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected model file" }
            val destinationFile = uniqueModelFile(modelsDir(context), destinationName)
            importFromStream(
                input = input,
                destinationFile = destinationFile,
                intent = intent,
                sourceLabel = sourceName
            )
        }
    }

    fun importFromFile(
        sourceFile: File,
        destinationFile: File,
        intent: ImportIntent
    ): ImportedArtifact {
        val samePath = runCatching {
            sourceFile.canonicalPath == destinationFile.canonicalPath
        }.getOrDefault(false)
        if (samePath) {
            return validateExistingFile(sourceFile, intent)
        }
        sourceFile.inputStream().use { input ->
            return importFromStream(
                input = input,
                destinationFile = destinationFile,
                intent = intent,
                sourceLabel = sourceFile.absolutePath
            )
        }
    }

    fun validateExistingFile(file: File, intent: ImportIntent): ImportedArtifact {
        val digest = MessageDigest.getInstance("SHA-256")
        val headerBytes = ByteArray(GgufInspector.MAX_HEADER_BYTES)
        var headerOffset = 0
        var bytesCopied = 0L
        val buffer = ByteArray(64 * 1024)

        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
                if (headerOffset < headerBytes.size) {
                    val remaining = headerBytes.size - headerOffset
                    val toCopy = minOf(read, remaining)
                    buffer.copyInto(headerBytes, headerOffset, 0, toCopy)
                    headerOffset += toCopy
                }
                bytesCopied += read
            }
        }

        val sha256 = digest.digest().toHex()
        val inspection = GgufInspector.inspectBytes(headerBytes.copyOf(headerOffset))
        validateImportedArtifact(intent, bytesCopied, inspection, sha256)
        return ImportedArtifact(
            file = file,
            bytesCopied = bytesCopied,
            sha256 = sha256,
            verificationStatus = verificationStatusFor(intent.displayName, sha256, intent.expectedSha256),
            inspection = inspection,
            family = inferFamily(intent, inspection),
            quantization = inferQuantization(file.name)
        )
    }

    fun importFromStream(
        input: InputStream,
        destinationFile: File,
        intent: ImportIntent,
        sourceLabel: String,
        onBytesCopied: ((Long) -> Unit)? = null
    ): ImportedArtifact {
        destinationFile.parentFile?.mkdirs()
        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.${UUID.randomUUID()}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        val headerBytes = ByteArray(GgufInspector.MAX_HEADER_BYTES)
        var headerOffset = 0
        var bytesCopied = 0L
        val buffer = ByteArray(64 * 1024)

        try {
            tempFile.outputStream().use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    if (headerOffset < headerBytes.size) {
                        val remaining = headerBytes.size - headerOffset
                        val toCopy = minOf(read, remaining)
                        buffer.copyInto(headerBytes, headerOffset, 0, toCopy)
                        headerOffset += toCopy
                    }
                    bytesCopied += read
                    onBytesCopied?.invoke(bytesCopied)
                }
            }

            val sha256 = digest.digest().toHex()
            val inspection = GgufInspector.inspectBytes(headerBytes.copyOf(headerOffset))
            validateImportedArtifact(intent, bytesCopied, inspection, sha256)

            if (destinationFile.exists() && !destinationFile.delete()) {
                throw IllegalStateException("Cannot replace existing model file: ${destinationFile.name}")
            }
            if (!tempFile.renameTo(destinationFile)) {
                throw IllegalStateException("Unable to finalize imported model file.")
            }

            return ImportedArtifact(
                file = destinationFile,
                bytesCopied = bytesCopied,
                sha256 = sha256,
                verificationStatus = verificationStatusFor(intent.displayName, sha256, intent.expectedSha256),
                inspection = inspection,
                family = inferFamily(intent, inspection),
                quantization = inferQuantization(destinationFile.name)
            )
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    fun logImport(
        sourceLabel: String,
        destinationFile: File,
        bytesCopied: Long,
        sha256: String,
        inspection: GgufInspection,
        intent: ImportIntent,
        roomRecordId: String,
        verificationStatus: String
    ) {
        Log.i(
            TAG,
            "Model import: source=$sourceLabel selectedDisplayName=${intent.displayName} intendedRole=${intent.intendedRole} " +
                "destination=${destinationFile.absolutePath} modelDisplayName=${destinationFile.nameWithoutExtension} " +
                "bytesCopied=$bytesCopied sha256Prefix=${sha256.take(16)} ggufMagicValid=${inspection.ggufMagicValid} " +
                "metadataConfidence=${inspection.metadataConfidence} general.name=${inspection.generalName ?: "unknown"} " +
                "general.architecture=${inspection.generalArchitecture ?: "unknown"} roomRecordId=$roomRecordId " +
                "verificationStatus=$verificationStatus"
        )
    }

    fun uniqueModelFile(modelsDir: File, fileName: String): File {
        val safeBaseName = fileName
            .replace(Regex("""[^\w .()_-]"""), "_")
            .ifBlank { "model.gguf" }
        val normalizedName = if (safeBaseName.endsWith(".gguf", ignoreCase = true)) {
            safeBaseName
        } else {
            "$safeBaseName.gguf"
        }
        val baseName = normalizedName.removeSuffix(".gguf")
        var candidate = File(modelsDir, normalizedName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(modelsDir, "$baseName ($index).gguf")
            index++
        }
        return candidate
    }

    fun isAppOwnedModelFile(file: File, appModelsDir: File): Boolean {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val canonicalRoot = runCatching { appModelsDir.canonicalFile }.getOrNull() ?: return false
        return canonicalFile.parentFile == canonicalRoot
    }

    private fun validateImportedArtifact(
        intent: ImportIntent,
        bytesCopied: Long,
        inspection: GgufInspection,
        sha256: String
    ) {
        require(inspection.ggufMagicValid) { "Import rejected: file is not a valid GGUF artifact." }
        require(bytesCopied >= MIN_MODEL_BYTES) { "Import rejected: model file is truncated or too small." }

        val operatorImport = isPrimaryOperatorIntent(intent)
        if (operatorImport) {
            require(bytesCopied >= MIN_PRIMARY_OPERATOR_BYTES) {
                "Import rejected: PocketNode Operator artifact is too small (${StorageUtils.formatBytes(bytesCopied)})."
            }
            require(!inspection.draftSignatureDetected) {
                "Import rejected: PocketNode Operator import resolved to a draft-family GGUF (${inspection.generalName ?: "unknown"})."
            }
            val expectedSha256 = intent.expectedSha256 ?: HashUtils.KNOWN_HASHES[PRIMARY_OPERATOR_NAME]
            if (expectedSha256 != null) {
                require(expectedSha256.equals(sha256, ignoreCase = true)) {
                    "Import rejected: PocketNode Operator SHA-256 does not match the expected artifact."
                }
            }
        }
        if (intent.intendedRole != ModelRole.DRAFT.name && inspection.draftSignatureDetected) {
            require(!operatorImport) {
                "Import rejected: draft artifact cannot be imported into the primary Operator slot."
            }
        }
    }

    private fun inferFamily(intent: ImportIntent, inspection: GgufInspection): String? {
        intent.familyHint?.let { return it }
        val combined = listOfNotNull(inspection.generalName, inspection.generalArchitecture, inspection.generalBasename)
            .joinToString(" ")
            .lowercase()
        return when {
            combined.contains("smollm2") -> "SmolLM2"
            combined.contains("llama3.2") -> "llama3.2"
            combined.contains("llama") -> "llama"
            combined.contains("phi") -> "phi"
            else -> null
        }
    }

    private fun inferQuantization(fileName: String): String? =
        Regex("(Q[1-8]_[A-Z0-9_]+)", RegexOption.IGNORE_CASE)
            .find(fileName)
            ?.value
            ?.uppercase()

    private fun isPrimaryOperatorIntent(intent: ImportIntent): Boolean =
        intent.displayName.equals(PRIMARY_OPERATOR_NAME, ignoreCase = true)

    private fun verificationStatusFor(displayName: String, sha256: String, expectedSha256: String?): String {
        val knownHash = expectedSha256 ?: HashUtils.KNOWN_HASHES[displayName]
        return when {
            knownHash == null -> VerificationStatus.UNKNOWN_HASH
            knownHash.equals(sha256, ignoreCase = true) -> VerificationStatus.VERIFIED
            else -> VerificationStatus.FAILED
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
