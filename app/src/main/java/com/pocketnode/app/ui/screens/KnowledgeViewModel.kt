package com.pocketnode.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.KnowledgeRepository
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.data.model.KnowledgeSource
import com.pocketnode.app.knowledge.LocalKnowledgeChunker
import com.pocketnode.app.knowledge.LocalKnowledgeParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class KnowledgeViewModel(
    private val app: MainApplication,
    private val repository: KnowledgeRepository
) : ViewModel() {

    sealed class ImportState {
        object Idle : ImportState()
        object Loading : ImportState()
        data class FolderIndexing(val currentFile: String, val indexed: Int, val total: Int) : ImportState()
        data class Done(val sourceId: Long, val chunkCount: Int, val title: String) : ImportState()
        data class FolderDone(
            val sourceId: Long,
            val indexed: Int,
            val skipped: Int,
            val failed: Int,
            val totalChunks: Int
        ) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    private data class IndexResult(val indexed: Int, val skipped: Int, val failed: Int, val totalChunks: Int)

    companion object {
        private const val MAX_FILES_PER_FOLDER = 500
    }

    val sources: StateFlow<List<KnowledgeSource>> = repository.getAllSources()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val importState = mutableStateOf<ImportState>(ImportState.Idle)
    val searchQuery = mutableStateOf("")
    val searchResults = mutableStateOf<List<KnowledgeChunk>>(emptyList())
    val isSearching = mutableStateOf(false)

    private var importJob: Job? = null

    // ── Single file import ────────────────────────────────────────────────────

    fun importDocument(context: Context, uri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { importState.value = ImportState.Loading }

            try {
                var displayName = "document"
                var sizeBytes = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameCol >= 0) displayName = cursor.getString(nameCol) ?: displayName
                        if (sizeCol >= 0) sizeBytes = cursor.getLong(sizeCol)
                    }
                }

                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (ext != "md" && ext != "txt") {
                    withContext(Dispatchers.Main) {
                        importState.value = ImportState.Error(
                            "Unsupported file type .$ext. Only .md and .txt files are supported."
                        )
                    }
                    return@launch
                }

                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: run {
                    withContext(Dispatchers.Main) {
                        importState.value = ImportState.Error("Could not read file.")
                    }
                    return@launch
                }

                val parseResult = LocalKnowledgeParser.parse(displayName, content)
                if (parseResult is LocalKnowledgeParser.ParseResult.Error) {
                    withContext(Dispatchers.Main) {
                        importState.value = ImportState.Error(parseResult.message)
                    }
                    return@launch
                }
                val parsed = parseResult as LocalKnowledgeParser.ParseResult.Success
                val hash = sha256(parsed.text)

                val chunks = buildKnowledgeChunks(
                    LocalKnowledgeChunker.chunk(parsed.text, parsed.title)
                )

                val sourceId = repository.indexDocument(
                    sourceDisplayName = parsed.title,
                    sourceUri = uri.toString(),
                    docDisplayName = displayName,
                    docUri = uri.toString(),
                    sizeBytes = sizeBytes,
                    modifiedAt = System.currentTimeMillis(),
                    textHash = hash,
                    chunks = chunks
                )

                withContext(Dispatchers.Main) {
                    importState.value = ImportState.Done(sourceId, chunks.size, parsed.title)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { importState.value = ImportState.Idle }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    importState.value = ImportState.Error("Import failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    // ── Folder / vault import ─────────────────────────────────────────────────

    fun importFolder(context: Context, treeUri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                importState.value = ImportState.FolderIndexing("Scanning…", 0, 0)
            }
            try {
                val files = scanFolderFiles(context, treeUri)
                if (files.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        importState.value = ImportState.Error("No .md or .txt files found in selected folder.")
                    }
                    return@launch
                }

                val folderName = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Vault"
                val sourceId = repository.createSource(folderName, treeUri.toString(), isFolder = true)

                val result = indexFilesToSource(context, sourceId, files)
                repository.refreshSourceCounts(sourceId)

                withContext(Dispatchers.Main) {
                    importState.value = ImportState.FolderDone(
                        sourceId, result.indexed, result.skipped, result.failed, result.totalChunks
                    )
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { importState.value = ImportState.Idle }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    importState.value = ImportState.Error("Folder import failed: ${e.message ?: "unknown"}")
                }
            }
        }
    }

    // ── Reindex ───────────────────────────────────────────────────────────────

    fun reindexSource(context: Context, source: KnowledgeSource) {
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                importState.value = ImportState.FolderIndexing("Scanning…", 0, 0)
            }
            try {
                if (source.isFolder) {
                    reindexFolderSource(context, source)
                } else {
                    reindexFileSource(context, source)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { importState.value = ImportState.Idle }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    importState.value = ImportState.Error("Reindex failed: ${e.message ?: "unknown"}")
                }
            }
        }
    }

    private suspend fun reindexFolderSource(context: Context, source: KnowledgeSource) {
        val treeUri = Uri.parse(source.uriPath)
        val files = scanFolderFiles(context, treeUri)
        val existingDocs = repository.getDocumentsBySource(source.id)
        val scannedUris = mutableSetOf<String>()

        val result = indexFilesToSource(context, source.id, files, existingDocs, scannedUris)

        // Remove docs whose files no longer exist in the folder.
        for (doc in existingDocs) {
            if (doc.uriPath !in scannedUris) {
                repository.deleteDocument(doc.id)
            }
        }

        repository.refreshSourceCounts(source.id)
        withContext(Dispatchers.Main) {
            importState.value = ImportState.FolderDone(
                source.id, result.indexed, result.skipped, result.failed, result.totalChunks
            )
        }
    }

    private suspend fun reindexFileSource(context: Context, source: KnowledgeSource) {
        withContext(Dispatchers.Main) { importState.value = ImportState.Loading }

        val fileUri = Uri.parse(source.uriPath)
        val existingDocs = repository.getDocumentsBySource(source.id)
        val existingDoc = existingDocs.firstOrNull()

        var displayName = source.displayName
        var sizeBytes = 0L
        context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameCol >= 0) displayName = cursor.getString(nameCol) ?: displayName
                if (sizeCol >= 0) sizeBytes = cursor.getLong(sizeCol)
            }
        }

        val content = context.contentResolver.openInputStream(fileUri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: run {
            withContext(Dispatchers.Main) { importState.value = ImportState.Error("Could not read file.") }
            return
        }

        val parseResult = LocalKnowledgeParser.parse(displayName, content)
        if (parseResult is LocalKnowledgeParser.ParseResult.Error) {
            withContext(Dispatchers.Main) { importState.value = ImportState.Error(parseResult.message) }
            return
        }
        val parsed = parseResult as LocalKnowledgeParser.ParseResult.Success
        val hash = sha256(parsed.text)

        if (existingDoc != null && hash == existingDoc.textHash) {
            withContext(Dispatchers.Main) {
                importState.value = ImportState.FolderDone(source.id, 0, 1, 0, source.chunkCount)
            }
            return
        }

        existingDoc?.let { repository.deleteDocument(it.id) }

        val chunks = buildKnowledgeChunks(LocalKnowledgeChunker.chunk(parsed.text, parsed.title))
        repository.addDocumentToSource(
            source.id, displayName, source.uriPath, sizeBytes, System.currentTimeMillis(), hash, chunks
        )
        repository.refreshSourceCounts(source.id)

        withContext(Dispatchers.Main) {
            importState.value = ImportState.FolderDone(source.id, 1, 0, 0, chunks.size)
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importState.value = ImportState.Idle
    }

    // ── Shared indexing logic ─────────────────────────────────────────────────

    private suspend fun indexFilesToSource(
        context: Context,
        sourceId: Long,
        files: List<DocumentFile>,
        existingDocs: List<com.pocketnode.app.data.model.KnowledgeDocument> = emptyList(),
        scannedUris: MutableSet<String>? = null
    ): IndexResult {
        val existingByUri = existingDocs.associateBy { it.uriPath }
        var indexed = 0
        var skipped = 0
        var failed = 0
        var totalChunks = 0

        for ((idx, file) in files.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            val fileUri = file.uri.toString()
            scannedUris?.add(fileUri)
            val fileName = file.name ?: "document"

            withContext(Dispatchers.Main) {
                importState.value = ImportState.FolderIndexing(fileName, idx, files.size)
            }

            try {
                val existing = existingByUri[fileUri]
                if (existing != null) {
                    // Quick metadata check before reading content.
                    val fileModified = file.lastModified()
                    val fileSize = file.length()
                    if (fileModified == existing.modifiedAt && fileSize == existing.sizeBytes) {
                        skipped++
                        continue
                    }
                    // Metadata changed — read and hash-check.
                    val contentBytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    if (contentBytes == null) { failed++; continue }
                    val content = contentBytes.toString(Charsets.UTF_8)

                    val parseResult = LocalKnowledgeParser.parse(fileName, content)
                    if (parseResult is LocalKnowledgeParser.ParseResult.Error) { failed++; continue }
                    val parsed = parseResult as LocalKnowledgeParser.ParseResult.Success
                    val hash = sha256(parsed.text)

                    if (hash == existing.textHash) { skipped++; continue }

                    // Content changed — replace transactionally.
                    repository.deleteDocument(existing.id)
                    val chunks = buildKnowledgeChunks(
                        LocalKnowledgeChunker.chunk(parsed.text, parsed.title)
                    )
                    repository.addDocumentToSource(
                        sourceId, fileName, fileUri, fileSize, fileModified, hash, chunks
                    )
                    totalChunks += chunks.size
                    indexed++
                } else {
                    // New file.
                    val contentBytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    if (contentBytes == null) { failed++; continue }
                    val content = contentBytes.toString(Charsets.UTF_8)

                    val parseResult = LocalKnowledgeParser.parse(fileName, content)
                    if (parseResult is LocalKnowledgeParser.ParseResult.Error) { failed++; continue }
                    val parsed = parseResult as LocalKnowledgeParser.ParseResult.Success
                    val hash = sha256(parsed.text)
                    val fileSize = file.length()
                    val fileModified = file.lastModified()

                    val chunks = buildKnowledgeChunks(
                        LocalKnowledgeChunker.chunk(parsed.text, parsed.title)
                    )
                    repository.addDocumentToSource(
                        sourceId, fileName, fileUri, fileSize, fileModified, hash, chunks
                    )
                    totalChunks += chunks.size
                    indexed++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }

        return IndexResult(indexed, skipped, failed, totalChunks)
    }

    private fun scanFolderFiles(context: Context, treeUri: Uri): List<DocumentFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<DocumentFile>()
        scanRecursive(root, result)
        return result
    }

    private fun scanRecursive(dir: DocumentFile, result: MutableList<DocumentFile>) {
        if (result.size >= MAX_FILES_PER_FOLDER) return
        try {
            for (child in dir.listFiles()) {
                if (result.size >= MAX_FILES_PER_FOLDER) break
                when {
                    child.isDirectory -> scanRecursive(child, result)
                    else -> {
                        val name = child.name ?: continue
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext == "md" || ext == "txt") result.add(child)
                    }
                }
            }
        } catch (_: Exception) { /* permission or IO error on a sub-directory — skip silently */ }
    }

    private fun buildKnowledgeChunks(dataList: List<LocalKnowledgeChunker.ChunkData>): List<KnowledgeChunk> =
        dataList.map { c ->
            KnowledgeChunk(
                documentId = 0,
                sourceId = 0,
                chunkIndex = c.chunkIndex,
                documentTitle = c.documentTitle,
                text = c.text,
                tokenEstimate = c.tokenEstimate,
                charStart = c.charStart,
                charEnd = c.charEnd
            )
        }

    // ── Search ────────────────────────────────────────────────────────────────

    fun search(query: String) {
        searchQuery.value = query
        if (query.isBlank()) {
            searchResults.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isSearching.value = true }
            val results = repository.searchChunks(query)
            withContext(Dispatchers.Main) {
                searchResults.value = results
                isSearching.value = false
            }
        }
    }

    fun deleteSource(sourceId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSource(sourceId)
        }
    }

    fun dismissImport() {
        importState.value = ImportState.Idle
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
