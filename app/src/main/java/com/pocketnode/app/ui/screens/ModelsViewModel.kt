package com.pocketnode.app.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.RemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    object Importing : DownloadState()
    object Done : DownloadState()
    data class Error(val msg: String) : DownloadState()
}

class ModelsViewModel(private val modelManager: ModelManager) : ViewModel() {

    val models: StateFlow<List<LocalModel>> = modelManager.getModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val activeDownloadIds = mutableMapOf<Long, String>() // downloadId -> modelName

    fun downloadModel(context: Context, remoteModel: RemoteModel) {
        val appContext = context.applicationContext
        val modelBaseName = normalizedModelName(remoteModel.name)

        if (_downloadStates.value[remoteModel.name] is DownloadState.Downloading ||
            activeDownloadIds.containsValue(remoteModel.name)
        ) {
            return
        }

        if (models.value.any { normalizedModelName(it.name) == modelBaseName && File(it.path).exists() }) {
            setDownloadState(remoteModel.name, DownloadState.Done)
            return
        }

        // Storage Validation: Require at least 2GB of free space for demo
        val dataDir = Environment.getDataDirectory()
        if (dataDir.usableSpace < 2L * 1024 * 1024 * 1024) {
            setDownloadState(remoteModel.name, DownloadState.Error("Not enough storage space (2GB req)"))
            return
        }

        val destFile = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "${remoteModel.name}.gguf"
        )

        val request = DownloadManager.Request(Uri.parse(remoteModel.huggingFaceUrl))
            .setTitle("Downloading ${remoteModel.name}")
            .setDescription("Downloading GGUF model from Hugging Face")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "${remoteModel.name}.gguf")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        cancelDuplicateDownloads(downloadManager, remoteModel.name)
        val downloadId = downloadManager.enqueue(request)

        activeDownloadIds[downloadId] = remoteModel.name
        setDownloadState(remoteModel.name, DownloadState.Downloading(0f))

        viewModelScope.launch {
            pollDownload(appContext, downloadManager, downloadId, remoteModel.name, destFile)
        }
    }

    fun downloadModelFromUrl(context: Context, url: String) {
        val name = url.substringAfterLast("/").substringBefore("?")
        val cleanName = if (name.endsWith(".gguf")) name.removeSuffix(".gguf") else "DownloadedModel"
        
        val remoteModel = RemoteModel(
            name = cleanName,
            description = "Custom downloaded model",
            size = "Unknown",
            huggingFaceUrl = url
        )
        downloadModel(context, remoteModel)
    }

    private suspend fun pollDownload(
        context: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
        modelName: String,
        destFile: File
    ) {
        while (true) {
            delay(500)
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))

            if (!cursor.moveToFirst()) {
                cursor.close()
                setDownloadState(modelName, DownloadState.Error("Download not found"))
                activeDownloadIds.remove(downloadId)
                return
            }

            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val localUriStr = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            } else {
                null
            }
            cursor.close()

            when (status) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                    setDownloadState(modelName, DownloadState.Downloading(progress))
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val actualFile = if (localUriStr != null) {
                        File(Uri.parse(localUriStr).path!!)
                    } else {
                        destFile
                    }
                    
                    setDownloadState(modelName, DownloadState.Importing)
                    activeDownloadIds.remove(downloadId)
                    importFromPath(context, actualFile, modelName, replaceExisting = true)
                    setDownloadState(modelName, DownloadState.Done)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    setDownloadState(modelName, DownloadState.Error("Download failed"))
                    activeDownloadIds.remove(downloadId)
                    return
                }
            }
        }
    }

    private suspend fun importFromPath(
        context: Context,
        sourceFile: File,
        modelName: String,
        replaceExisting: Boolean = false
    ) {
        val existing = models.value.firstOrNull {
            normalizedModelName(it.name) == normalizedModelName(modelName) && File(it.path).exists()
        }
        if (existing != null && !replaceExisting) return

        val appModelFile = if (existing != null && replaceExisting) {
            File(existing.path)
        } else {
            copyFileIntoModelDir(context, sourceFile, "$modelName.gguf")
        }
        if (existing != null && replaceExisting && sourceFile.canonicalPath != appModelFile.canonicalPath) {
            sourceFile.inputStream().use { input ->
                appModelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        modelManager.addModel(
            LocalModel(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = modelName,
                path = appModelFile.absolutePath,
                contextLength = 2048
            )
        )
    }

    private fun setDownloadState(modelName: String, state: DownloadState) {
        _downloadStates.update { current -> current + (modelName to state) }
    }

    fun resetDownloadState(modelName: String) {
        _downloadStates.update { current -> current - modelName }
    }

    fun importModel(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val documentName = DocumentFile.fromSingleUri(context, uri)?.name
                ?: "model_${UUID.randomUUID()}.gguf"
            val safeName = if (documentName.endsWith(".gguf", ignoreCase = true)) {
                documentName
            } else {
                "$documentName.gguf"
            }
            val modelName = safeName.removeSuffix(".gguf")
            setDownloadState(modelName, DownloadState.Importing)

            try {
                if (models.value.any { normalizedModelName(it.name) == normalizedModelName(modelName) && File(it.path).exists() }) {
                    setDownloadState(modelName, DownloadState.Done)
                    return@launch
                }
                val appModelFile = copyUriIntoModelDir(context, uri, safeName)
                modelManager.addModel(
                    LocalModel(
                        id = UUID.randomUUID().toString(),
                        name = modelName,
                        path = appModelFile.absolutePath,
                        contextLength = 2048
                    )
                )
                setDownloadState(modelName, DownloadState.Done)
            } catch (e: Exception) {
                setDownloadState(modelName, DownloadState.Error(e.message ?: "Import failed"))
            }
        }
    }

    fun importCompletedDownloads(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            cleanupDownloadManager(downloadManager)
            dedupeStoredModels()

            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@launch
            val downloadedModels = downloadDir
                .listFiles { file -> file.isFile && file.extension.equals("gguf", ignoreCase = true) }
                ?.toList()
                .orEmpty()
            val knownNames = models.value
                .filter { File(it.path).exists() }
                .map { normalizedModelName(it.name) }
                .toMutableSet()

            downloadedModels.forEach { file ->
                val modelName = file.nameWithoutExtension
                val normalizedName = normalizedModelName(modelName)
                if (normalizedName !in knownNames) {
                    try {
                        setDownloadState(modelName, DownloadState.Importing)
                        importFromPath(context, file, modelName)
                        setDownloadState(modelName, DownloadState.Done)
                        knownNames += normalizedName
                    } catch (e: Exception) {
                        setDownloadState(modelName, DownloadState.Error(e.message ?: "Import failed"))
                    }
                }
            }
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            val file = File(model.path)
            if (file.exists()) file.delete()
            modelManager.deleteModel(model)
        }
    }

    private suspend fun copyUriIntoModelDir(context: Context, uri: Uri, fileName: String): File =
        withContext(Dispatchers.IO) {
            val outputFile = uniqueModelFile(context, fileName)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected model file" }
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            outputFile
        }

    private suspend fun copyFileIntoModelDir(context: Context, sourceFile: File, fileName: String): File =
        withContext(Dispatchers.IO) {
            val outputFile = uniqueModelFile(context, fileName)
            if (sourceFile.canonicalPath != outputFile.canonicalPath) {
                sourceFile.inputStream().use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outputFile
        }

    private fun uniqueModelFile(context: Context, fileName: String): File {
        val modelDir = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
        val safeBaseName = fileName
            .replace(Regex("""[^\w .()_-]"""), "_")
            .ifBlank { "model.gguf" }
        val baseName = safeBaseName.removeSuffix(".gguf")
        var candidate = File(modelDir, safeBaseName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(modelDir, "${baseName}_$index.gguf")
            index++
        }
        return candidate
    }

    private fun cancelDuplicateDownloads(downloadManager: DownloadManager, modelName: String) {
        val duplicateIds = mutableListOf<Long>()
        val cursor = downloadManager.query(DownloadManager.Query())
        cursor.use {
            while (it.moveToNext()) {
                val title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: continue
                if (normalizedDownloadTitle(title) == normalizedModelName(modelName)) {
                    duplicateIds += it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        if (duplicateIds.isNotEmpty()) {
            downloadManager.remove(*duplicateIds.toLongArray())
        }
    }

    private fun cleanupDownloadManager(downloadManager: DownloadManager) {
        val removeIds = mutableListOf<Long>()
        val importedNames = models.value
            .filter { File(it.path).exists() }
            .map { normalizedModelName(it.name) }
            .toSet()
        val seenActiveNames = mutableSetOf<String>()

        val cursor = downloadManager.query(DownloadManager.Query())
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val modelName = normalizedDownloadTitle(title)

                if (title.startsWith("Pocket Node Update", ignoreCase = true)) {
                    removeIds += id
                } else if (modelName in importedNames) {
                    removeIds += id
                } else if (
                    status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PENDING
                ) {
                    if (!seenActiveNames.add(modelName)) removeIds += id
                }
            }
        }

        if (removeIds.isNotEmpty()) {
            downloadManager.remove(*removeIds.distinct().toLongArray())
        }
    }

    private suspend fun dedupeStoredModels() {
        val seen = mutableSetOf<String>()
        models.value.forEach { model ->
            val key = normalizedModelName(model.name)
            if (!seen.add(key)) {
                val file = File(model.path)
                if (file.exists()) file.delete()
                modelManager.deleteModel(model)
            }
        }
    }

    private fun normalizedDownloadTitle(title: String): String =
        normalizedModelName(title.removePrefix("Downloading ").removeSuffix(".gguf"))

    private fun normalizedModelName(name: String): String =
        name.removeSuffix(".gguf")
            .replace(Regex("""[-_]\d+$"""), "")
            .trim()
            .lowercase()
}
