package com.pocketnode.app.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.RemoteModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
            cursor.close()

            when (status) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                    setDownloadState(modelName, DownloadState.Downloading(progress))
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    setDownloadState(modelName, DownloadState.Importing)
                    activeDownloadIds.remove(downloadId)
                    importFromPath(context, destFile, modelName)
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

    private suspend fun importFromPath(context: Context, sourceFile: File, modelName: String) {
        modelManager.addModel(
            LocalModel(
                id = UUID.randomUUID().toString(),
                name = modelName,
                path = sourceFile.absolutePath,
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
        viewModelScope.launch {
            // Take persistable read permission so the URI survives app restarts
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val documentName = DocumentFile.fromSingleUri(context, uri)?.name
                ?: "model_${UUID.randomUUID()}.gguf"
            val safeName = if (documentName.endsWith(".gguf", ignoreCase = true))
                documentName else "$documentName.gguf"
            modelManager.addModel(
                LocalModel(
                    id = UUID.randomUUID().toString(),
                    name = safeName.removeSuffix(".gguf"),
                    path = uri.toString(),
                    contextLength = 2048
                )
            )
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            val file = File(model.path)
            if (file.exists()) file.delete()
            modelManager.deleteModel(model)
        }
    }
}
