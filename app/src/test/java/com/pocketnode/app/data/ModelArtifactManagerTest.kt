package com.pocketnode.app.data

import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

class ModelArtifactManagerTest {

    @Test
    fun ggufInspectorParsesStructuredMetadataWithinBoundedHeader() {
        val bytes = buildGgufHeader(
            generalName = "Smollm2 135M 8k Lc100K Mix1 Ep2",
            generalArchitecture = "llama"
        )

        val inspection = GgufInspector.inspectBytes(bytes)

        assertTrue(inspection.ggufMagicValid)
        assertEquals("structured", inspection.metadataConfidence)
        assertEquals("Smollm2 135M 8k Lc100K Mix1 Ep2", inspection.generalName)
        assertEquals("llama", inspection.generalArchitecture)
        assertTrue(inspection.draftSignatureDetected)
    }

    @Test
    fun ggufInspectorReadsOnlyBoundedPrefixFromLargeFile() {
        val tempDir = createTempDirectory("gguf-inspect").toFile()
        val file = File(tempDir, "large.gguf")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(buildGgufHeader("Large Test Model", "llama"))
            raf.setLength((GgufInspector.MAX_HEADER_BYTES + 8_192).toLong())
        }

        val inspection = GgufInspector.inspect(file)

        assertEquals(GgufInspector.MAX_HEADER_BYTES, inspection.bytesInspected)
    }

    @Test
    fun importFromStreamRejectsNonGgufFile() {
        val tempDir = createTempDirectory("gguf-import").toFile()
        val destination = File(tempDir, "bad.gguf")

        val error = runCatching {
            ModelArtifactManager.importFromStream(
                input = ByteArrayInputStream("not-a-gguf".toByteArray()),
                destinationFile = destination,
                intent = ImportIntent(displayName = "BadModel", intendedRole = ModelRole.MAIN.name),
                sourceLabel = "unit-test"
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message!!.contains("not a valid GGUF artifact"))
    }

    @Test
    fun importFromStreamRejectsTruncatedPrimaryOperatorArtifact() {
        val tempDir = createTempDirectory("gguf-small-operator").toFile()
        val destination = File(tempDir, "PocketNode_Operator_Q4_0.gguf")
        val input = SyntheticGgufInputStream(
            prefix = buildGgufHeader("PocketNode Operator", "llama"),
            totalBytes = 12L * 1024L * 1024L
        )

        val error = runCatching {
            ModelArtifactManager.importFromStream(
                input = input,
                destinationFile = destination,
                intent = ImportIntent(
                    displayName = "PocketNode_Operator_Q4_0",
                    intendedRole = ModelRole.MAIN.name
                ),
                sourceLabel = "unit-test"
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message!!.contains("too small"))
    }

    @Test
    fun importFromStreamRejectsDraftArtifactForPrimaryOperator() {
        val tempDir = createTempDirectory("gguf-draft-operator").toFile()
        val destination = File(tempDir, "PocketNode_Operator_Q4_0.gguf")
        val input = SyntheticGgufInputStream(
            prefix = buildGgufHeader("Smollm2 135M 8k Lc100K Mix1 Ep2", "llama"),
            totalBytes = ModelArtifactManager.MIN_PRIMARY_OPERATOR_BYTES + 4096L
        )

        val error = runCatching {
            ModelArtifactManager.importFromStream(
                input = input,
                destinationFile = destination,
                intent = ImportIntent(
                    displayName = "PocketNode_Operator_Q4_0",
                    intendedRole = ModelRole.MAIN.name
                ),
                sourceLabel = "unit-test"
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message!!.contains("draft-family GGUF"))
    }

    @Test
    fun importFromStreamHashesDuringSinglePassCopy() {
        val tempDir = createTempDirectory("gguf-stream-hash").toFile()
        val destination = File(tempDir, "custom.gguf")
        val countingInput = CountingInputStream(
            SyntheticGgufInputStream(
                prefix = buildGgufHeader("Custom Main Model", "llama"),
                totalBytes = 12L * 1024L * 1024L
            )
        )

        val imported = ModelArtifactManager.importFromStream(
            input = countingInput,
            destinationFile = destination,
            intent = ImportIntent(displayName = "Custom Main Model", intendedRole = ModelRole.MAIN.name),
            sourceLabel = "unit-test"
        )

        assertEquals(imported.bytesCopied, countingInput.bytesRead)
        assertTrue(destination.exists())
        assertEquals(VerificationStatus.UNKNOWN_HASH, imported.verificationStatus)
    }

    @Test
    fun importFromStreamAllowsDefaultMainModelWithoutOperatorSpecificSha() {
        val tempDir = createTempDirectory("gguf-default-main").toFile()
        val destination = File(tempDir, "Llama-3.2-3B-Instruct-Q4.gguf")
        val imported = ModelArtifactManager.importFromStream(
            input = SyntheticGgufInputStream(
                prefix = buildGgufHeader("Llama 3.2 3B Instruct", "llama"),
                totalBytes = 64L * 1024L * 1024L
            ),
            destinationFile = destination,
            intent = ImportIntent(
                displayName = "Llama 3.2 3B Instruct Q4",
                intendedRole = ModelRole.MAIN.name
            ),
            sourceLabel = "unit-test"
        )

        assertTrue(imported.file.exists())
        assertTrue(imported.inspection.ggufMagicValid)
        assertEquals("structured", imported.inspection.metadataConfidence)
        assertEquals("Llama 3.2 3B Instruct", imported.inspection.generalName)
        assertEquals(VerificationStatus.UNKNOWN_HASH, imported.verificationStatus)
    }

    @Test
    fun operatorSpecificExpectedShaCheckOnlyAppliesToPrimaryOperator() {
        val tempDir = createTempDirectory("gguf-non-operator-sha").toFile()
        val destination = File(tempDir, "Phi-3-Mini.gguf")
        val imported = ModelArtifactManager.importFromStream(
            input = SyntheticGgufInputStream(
                prefix = buildGgufHeader("Phi-3 Mini Instruct", "phi"),
                totalBytes = 48L * 1024L * 1024L
            ),
            destinationFile = destination,
            intent = ImportIntent(
                displayName = "Phi-3 Mini",
                intendedRole = ModelRole.MAIN.name,
                expectedSha256 = "deadbeef"
            ),
            sourceLabel = "unit-test"
        )

        assertTrue(imported.file.exists())
        assertEquals(VerificationStatus.FAILED, imported.verificationStatus)
    }

    @Test
    fun cleanupFailedPrimaryDeletesOnlyFailedPrimaryArtifact() {
        val rootDir = createTempDirectory("cleanup-models").toFile()
        val appModelsDir = File(rootDir, "models").also { it.mkdirs() }
        val failedPrimaryFile = File(appModelsDir, "PocketNode_Operator_Q4_0.gguf").apply { writeText("bad") }
        val draftFile = File(appModelsDir, "SmolLM2-135M-Instruct-Q4_0.gguf").apply { writeText("draft") }

        val failedPrimary = LocalModel(
            id = "failed-main",
            name = "PocketNode_Operator_Q4_0",
            path = failedPrimaryFile.absolutePath,
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            verificationStatus = VerificationStatus.FAILED
        )
        val draft = LocalModel(
            id = "draft-model",
            name = "SmolLM2 135M Draft (Q4_0)",
            path = draftFile.absolutePath,
            contextLength = 4096,
            role = ModelRole.DRAFT.name,
            verificationStatus = VerificationStatus.FAILED
        )

        val primaryResult = ModelArtifactManager.cleanupFailedPrimaryArtifact(failedPrimary, appModelsDir)
        val draftResult = ModelArtifactManager.cleanupFailedPrimaryArtifact(draft, appModelsDir)

        assertTrue(primaryResult.deletedFile)
        assertFalse(failedPrimaryFile.exists())
        assertEquals("draft_model", draftResult.skippedReason)
        assertTrue(draftFile.exists())
    }

    @Test
    fun cleanupFailedPrimaryDoesNotDeleteNonAppOwnedFile() {
        val rootDir = createTempDirectory("cleanup-external").toFile()
        val appModelsDir = File(rootDir, "models").also { it.mkdirs() }
        val externalFile = File(rootDir, "Downloads/operator.gguf").apply {
            parentFile?.mkdirs()
            writeText("external")
        }
        val failedPrimary = LocalModel(
            id = "failed-main",
            name = "PocketNode_Operator_Q4_0",
            path = externalFile.absolutePath,
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            verificationStatus = VerificationStatus.FAILED
        )

        val result = ModelArtifactManager.cleanupFailedPrimaryArtifact(failedPrimary, appModelsDir)

        assertEquals("not_app_owned", result.skippedReason)
        assertTrue(externalFile.exists())
    }

    @Test
    fun auditLogDetectsDuplicatePathAndHash() {
        val tempDir = createTempDirectory("gguf-audit").toFile()
        val sharedFile = File(tempDir, "shared.gguf").apply { writeText("shared") }
        val draft = LocalModel(
            id = "draft-id",
            name = "SmolLM2 Draft",
            path = sharedFile.absolutePath,
            contextLength = 4096,
            role = ModelRole.DRAFT.name,
            sha256 = "abc123abc123abc123",
            verificationStatus = VerificationStatus.NOT_CHECKED
        )
        val main = LocalModel(
            id = "main-id",
            name = "Baseline Main",
            path = sharedFile.absolutePath,
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            sha256 = "abc123abc123abc123",
            verificationStatus = VerificationStatus.UNKNOWN_HASH
        )

        val records = listOf(draft, main).map(ModelArtifactManager::createAuditRecord)

        assertEquals(2, records.size)
        assertEquals(records[0].resolvedPath, records[1].resolvedPath)
        assertEquals(records[0].sha256Prefix, records[1].sha256Prefix)
        assertNotNull(records[0].sha256Prefix)
    }

    private fun buildGgufHeader(
        generalName: String,
        generalArchitecture: String
    ): ByteArray {
        val entries = listOf(
            "general.name" to generalName,
            "general.architecture" to generalArchitecture
        )
        return ByteArrayOutputStream().use { output ->
            output.write("GGUF".toByteArray())
            output.write(le32(3))
            output.write(le64(0))
            output.write(le64(entries.size.toLong()))
            entries.forEach { (key, value) ->
                val keyBytes = key.toByteArray()
                val valueBytes = value.toByteArray()
                output.write(le64(keyBytes.size.toLong()))
                output.write(keyBytes)
                output.write(le32(8))
                output.write(le64(valueBytes.size.toLong()))
                output.write(valueBytes)
            }
            output.toByteArray()
        }
    }

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte()
        )

    private fun le64(value: Long): ByteArray =
        ByteArray(8) { index -> ((value ushr (8 * index)) and 0xff).toByte() }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead += 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) bytesRead += count
            return count
        }
    }

    private class SyntheticGgufInputStream(
        private val prefix: ByteArray,
        private val totalBytes: Long
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            if (position >= totalBytes) return -1
            val value = if (position < prefix.size) prefix[position.toInt()].toInt() and 0xff else 0
            position += 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= totalBytes) return -1
            val remaining = (totalBytes - position).coerceAtMost(len.toLong()).toInt()
            for (index in 0 until remaining) {
                val absolute = position + index
                b[off + index] = if (absolute < prefix.size) prefix[absolute.toInt()] else 0
            }
            position += remaining
            return remaining
        }
    }
}
