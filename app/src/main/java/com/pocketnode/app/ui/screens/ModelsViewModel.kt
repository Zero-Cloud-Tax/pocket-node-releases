package com.pocketnode.app.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.HashUtils
import com.pocketnode.app.data.ImportIntent
import com.pocketnode.app.data.ModelArtifactManager
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.data.StorageStats
import com.pocketnode.app.data.StorageUtils
import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import com.pocketnode.app.data.model.RECOMMENDED_MODELS
import com.pocketnode.app.data.model.RemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import com.pocketnode.app.data.ModelDownloadSpec

sealed class DownloadState {
    object Idle : DownloadState()
    object Queued : DownloadState()
    // bytesDownloaded/totalBytes default to 0 so existing RemoteModelCard usage is unaffected
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L
    ) : DownloadState()
    object Importing : DownloadState()
    object Verifying : DownloadState()
    object FileExists : DownloadState()  // final file already on disk — show Use Existing / Replace dialog
    data class Complete(val absolutePath: String) : DownloadState()
    object Done : DownloadState()        // kept for RECOMMENDED_MODELS DownloadManager path
    data class Error(val msg: String) : DownloadState()
    object Cancelled : DownloadState()
}

class ModelsViewModel(
    private val modelManager: ModelManager,
    private val app: MainApplication
) : ViewModel() {

    val models: StateFlow<List<LocalModel>> = modelManager.getModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _storageStats = MutableStateFlow<StorageStats?>(null)
    val storageStats: StateFlow<StorageStats?> = _storageStats.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val activeDownloadIds = mutableMapOf<Long, String>() // downloadId -> modelName

    private val _operatorDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val operatorDownloadState: StateFlow<DownloadState> = _operatorDownloadState.asStateFlow()
    private var operatorDownloadJob: Job? = null

    fun clearImportError() { _importError.value = null }
    fun clearStatusMessage() { _statusMessage.value = null }

    fun refreshStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _storageStats.value = StorageUtils.compute(app)
        }
    }

    // --- Operator streaming downloader ---

    fun downloadOperatorModel(spec: ModelDownloadSpec, onComplete: (() -> Unit)? = null) {
        if (operatorDownloadJob?.isActive == true) return
        val modelDir = ModelArtifactManager.modelsDir(app)
        if (File(modelDir, spec.filename).exists()) {
            _operatorDownloadState.value = DownloadState.FileExists
            return
        }
        startOperatorDownload(spec, onComplete)
    }

    fun useExistingOperatorModel(spec: ModelDownloadSpec, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _operatorDownloadState.value = DownloadState.Verifying
            try {
                val finalFile = File(ModelArtifactManager.modelsDir(app), spec.filename)
                if (!finalFile.exists()) { _operatorDownloadState.value = DownloadState.Idle; return@launch }
                val existing = models.value.firstOrNull { it.path == finalFile.absolutePath }
                val intent = ImportIntent(
                    displayName = finalFile.nameWithoutExtension,
                    intendedRole = ModelRole.MAIN.name,
                    expectedSha256 = spec.expectedSha256
                )
                val importedArtifact = ModelArtifactManager.importFromFile(finalFile, finalFile, intent)
                val modelId = existing?.id ?: UUID.randomUUID().toString()
                modelManager.addModel(
                    LocalModel(
                        id = modelId,
                        name = finalFile.nameWithoutExtension,
                        path = importedArtifact.file.absolutePath,
                        contextLength = existing?.contextLength ?: 4096,
                        role = ModelRole.MAIN.name,
                        family = importedArtifact.family ?: existing?.family,
                        quantization = importedArtifact.quantization ?: existing?.quantization,
                        tokenizerHash = existing?.tokenizerHash,
                        sizeBytes = importedArtifact.file.length(),
                        sha256 = importedArtifact.sha256,
                        lastModified = importedArtifact.file.lastModified(),
                        verificationStatus = importedArtifact.verificationStatus,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
                ModelArtifactManager.logImport(
                    sourceLabel = finalFile.absolutePath,
                    destinationFile = importedArtifact.file,
                    bytesCopied = importedArtifact.bytesCopied,
                    sha256 = importedArtifact.sha256,
                    inspection = importedArtifact.inspection,
                    intent = intent,
                    roomRecordId = modelId,
                    verificationStatus = importedArtifact.verificationStatus
                )
                _storageStats.value = StorageUtils.compute(app)
                _operatorDownloadState.value = DownloadState.Complete(finalFile.absolutePath)
                onComplete?.invoke()
            } catch (e: Exception) {
                _operatorDownloadState.value = DownloadState.Error(e.message ?: "Verification failed")
                _importError.value = e.message ?: "Verification failed"
            }
        }
    }

    fun replaceOperatorModel(spec: ModelDownloadSpec, onComplete: (() -> Unit)? = null) {
        operatorDownloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val finalFile = File(ModelArtifactManager.modelsDir(app), spec.filename)
            if (finalFile.exists()) finalFile.delete()
            models.value.firstOrNull { it.path == finalFile.absolutePath }
                ?.let { modelManager.deleteModel(it) }
            startOperatorDownload(spec, onComplete)
        }
    }

    fun cancelOperatorDownload() {
        operatorDownloadJob?.cancel()
        // State is set to Cancelled inside the CancellationException handler in startOperatorDownload
    }

    fun resetOperatorDownloadState() {
        _operatorDownloadState.value = DownloadState.Idle
    }

    private fun startOperatorDownload(spec: ModelDownloadSpec, onComplete: (() -> Unit)? = null) {
        operatorDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            val modelDir = ModelArtifactManager.modelsDir(app)
            val finalFile = File(modelDir, spec.filename)

            // Storage preflight — if size known, require size + 512 MB buffer
            if (spec.sizeBytes != null) {
                val required = spec.sizeBytes + 512L * 1024 * 1024
                if (modelDir.freeSpace < required) {
                    _operatorDownloadState.value = DownloadState.Error(
                        "Not enough storage. Need at least ${StorageUtils.formatBytes(required)} free."
                    )
                    return@launch
                }
            }

            _operatorDownloadState.value = DownloadState.Queued

            try {
                val conn = URL(spec.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "PocketNode/1.0 Android")
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    _operatorDownloadState.value =
                        DownloadState.Error("Download failed (HTTP ${conn.responseCode})")
                    return@launch
                }

                val totalBytes = conn.contentLengthLong
                val intent = ImportIntent(
                    displayName = finalFile.nameWithoutExtension,
                    intendedRole = ModelRole.MAIN.name,
                    expectedSha256 = spec.expectedSha256
                )
                val importedArtifact = conn.inputStream.use { input ->
                    ModelArtifactManager.importFromStream(
                        input = input,
                        destinationFile = finalFile,
                        intent = intent,
                        sourceLabel = spec.url
                    ) { bytesDownloaded ->
                        val pct = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                        _operatorDownloadState.value =
                            DownloadState.Downloading(pct, bytesDownloaded, totalBytes)
                    }
                }

                _operatorDownloadState.value = DownloadState.Verifying
                val modelId = UUID.randomUUID().toString()
                val model = LocalModel(
                    id = modelId,
                    name = importedArtifact.file.nameWithoutExtension,
                    path = importedArtifact.file.absolutePath,
                    contextLength = 4096,
                    role = ModelRole.MAIN.name,
                    family = importedArtifact.family,
                    quantization = importedArtifact.quantization,
                    sizeBytes = importedArtifact.file.length(),
                    sha256 = importedArtifact.sha256,
                    lastModified = importedArtifact.file.lastModified(),
                    verificationStatus = importedArtifact.verificationStatus,
                    lastCheckedAt = System.currentTimeMillis()
                )
                modelManager.addModel(model)
                ModelArtifactManager.logImport(
                    sourceLabel = spec.url,
                    destinationFile = importedArtifact.file,
                    bytesCopied = importedArtifact.bytesCopied,
                    sha256 = importedArtifact.sha256,
                    inspection = importedArtifact.inspection,
                    intent = intent,
                    roomRecordId = modelId,
                    verificationStatus = importedArtifact.verificationStatus
                )
                _storageStats.value = StorageUtils.compute(app)
                _operatorDownloadState.value = DownloadState.Complete(finalFile.absolutePath)
                onComplete?.invoke()

            } catch (e: CancellationException) {
                _operatorDownloadState.value = DownloadState.Cancelled
                throw e
            } catch (_: UnknownHostException) {
                _operatorDownloadState.value = DownloadState.Error("No internet connection")
            } catch (_: IOException) {
                _operatorDownloadState.value = DownloadState.Error("Download failed. Check your connection.")
            } catch (e: Exception) {
                _operatorDownloadState.value = DownloadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // --- End operator streaming downloader ---

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
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .addRequestHeader("Accept", "*/*")

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        cancelDuplicateDownloads(downloadManager, remoteModel.name)
        val downloadId = downloadManager.enqueue(request)

        activeDownloadIds[downloadId] = remoteModel.name
        setDownloadState(remoteModel.name, DownloadState.Downloading(0f))

        viewModelScope.launch {
            pollDownload(
                appContext, downloadManager, downloadId, remoteModel.name, destFile,
                role = remoteModel.defaultRole.name,
                family = remoteModel.family
            )
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
        destFile: File,
        role: String = ModelRole.MAIN.name,
        family: String? = null
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
                    importFromPath(context, actualFile, modelName, replaceExisting = true, role = role, family = family)
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
        replaceExisting: Boolean = false,
        role: String = ModelRole.MAIN.name,
        family: String? = null
    ) {
        val existing = models.value.firstOrNull {
            normalizedModelName(it.name) == normalizedModelName(modelName) && File(it.path).exists()
        }
        if (existing != null && !replaceExisting) return

        val destinationFile = if (existing != null && replaceExisting) {
            File(existing.path)
        } else {
            ModelArtifactManager.uniqueModelFile(ModelArtifactManager.modelsDir(context), "$modelName.gguf")
        }
        val intent = ImportIntent(
            displayName = modelName,
            intendedRole = role,
            familyHint = family
        )
        val importedArtifact = ModelArtifactManager.importFromFile(sourceFile, destinationFile, intent)
        val modelId = existing?.id ?: UUID.randomUUID().toString()
        modelManager.addModel(
            LocalModel(
                id = modelId,
                name = modelName,
                path = importedArtifact.file.absolutePath,
                contextLength = existing?.contextLength ?: 4096,
                role = role,
                family = importedArtifact.family ?: existing?.family,
                quantization = importedArtifact.quantization ?: existing?.quantization,
                tokenizerHash = existing?.tokenizerHash,
                sizeBytes = importedArtifact.file.length(),
                sha256 = importedArtifact.sha256,
                lastModified = importedArtifact.file.lastModified(),
                verificationStatus = importedArtifact.verificationStatus,
                lastCheckedAt = System.currentTimeMillis()
            )
        )
        ModelArtifactManager.logImport(
            sourceLabel = sourceFile.absolutePath,
            destinationFile = importedArtifact.file,
            bytesCopied = importedArtifact.bytesCopied,
            sha256 = importedArtifact.sha256,
            inspection = importedArtifact.inspection,
            intent = intent,
            roomRecordId = modelId,
            verificationStatus = importedArtifact.verificationStatus
        )
        _storageStats.value = StorageUtils.compute(app)
    }

    private fun setDownloadState(modelName: String, state: DownloadState) {
        _downloadStates.update { current -> current + (modelName to state) }
    }

    fun resetDownloadState(modelName: String) {
        _downloadStates.update { current -> current - modelName }
    }

    fun importModel(context: Context, uri: Uri, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val documentName = DocumentFile.fromSingleUri(context, uri)?.name
                ?: "model_${UUID.randomUUID()}.gguf"

            if (!documentName.endsWith(".gguf", ignoreCase = true)) {
                _importError.value = "Only GGUF files (.gguf) can be imported. Selected: \"$documentName\""
                return@launch
            }

            val modelName = documentName.removeSuffix(".gguf").removeSuffix(".GGUF")
            setDownloadState(modelName, DownloadState.Importing)

            try {
                val matchedRemote = RECOMMENDED_MODELS.firstOrNull {
                    normalizedModelName(it.name) == normalizedModelName(modelName)
                }
                val intent = ImportIntent(
                    displayName = modelName,
                    intendedRole = matchedRemote?.defaultRole?.name ?: ModelRole.MAIN.name,
                    familyHint = matchedRemote?.family
                )
                val importedArtifact = ModelArtifactManager.importFromUri(
                    context = context,
                    uri = uri,
                    destinationName = "$modelName.gguf",
                    intent = intent
                )
                val displayName = importedArtifact.file.nameWithoutExtension
                val modelId = UUID.randomUUID().toString()

                modelManager.addModel(
                    LocalModel(
                        id = modelId,
                        name = displayName,
                        path = importedArtifact.file.absolutePath,
                        contextLength = 4096,
                        role = intent.intendedRole,
                        family = importedArtifact.family,
                        quantization = importedArtifact.quantization,
                        sizeBytes = importedArtifact.file.length(),
                        sha256 = importedArtifact.sha256,
                        lastModified = importedArtifact.file.lastModified(),
                        verificationStatus = importedArtifact.verificationStatus,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
                ModelArtifactManager.logImport(
                    sourceLabel = uri.toString(),
                    destinationFile = importedArtifact.file,
                    bytesCopied = importedArtifact.bytesCopied,
                    sha256 = importedArtifact.sha256,
                    inspection = importedArtifact.inspection,
                    intent = intent,
                    roomRecordId = modelId,
                    verificationStatus = importedArtifact.verificationStatus
                )
                setDownloadState(displayName, DownloadState.Done)
                _storageStats.value = StorageUtils.compute(app)

                onComplete?.invoke()
            } catch (e: Exception) {
                setDownloadState(modelName, DownloadState.Error(e.message ?: "Import failed"))
                _importError.value = e.message ?: "Import failed"
            }
        }
    }

    fun importCompletedDownloads(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            cleanupDownloadManager(downloadManager)
            dedupeStoredModels()

            // Scan both the app-private downloads dir and the public Downloads folder
            val privateDlDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val publicDlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val scanDirs = listOfNotNull(privateDlDir, publicDlDir)
            val downloadedModels = scanDirs.flatMap { dir ->
                dir.listFiles { file -> file.isFile && file.extension.equals("gguf", ignoreCase = true) }
                    ?.toList().orEmpty()
            }.distinctBy { it.canonicalPath }
            val knownNames = models.value
                .filter { File(it.path).exists() }
                .map { normalizedModelName(it.name) }
                .toMutableSet()

            downloadedModels.forEach { file ->
                val modelName = file.nameWithoutExtension
                val normalizedName = normalizedModelName(modelName)
                if (normalizedName !in knownNames) {
                    try {
                        // Resolve role/family from RECOMMENDED_MODELS if filename matches
                        val matchedRemote = RECOMMENDED_MODELS.firstOrNull { remote ->
                            normalizedModelName(remote.name) == normalizedName ||
                            normalizedModelName(remote.huggingFaceUrl.substringAfterLast("/").substringBefore("?")) == normalizedName
                        }
                        val role = matchedRemote?.defaultRole?.name ?: ModelRole.MAIN.name
                        val family = matchedRemote?.family

                        setDownloadState(modelName, DownloadState.Importing)
                        importFromPath(context, file, modelName, role = role, family = family)
                        setDownloadState(modelName, DownloadState.Done)
                        knownNames += normalizedName
                    } catch (e: Exception) {
                        setDownloadState(modelName, DownloadState.Error(e.message ?: "Import failed"))
                    }
                }
            }
        }
    }

    fun setModelRole(model: LocalModel, role: String) {
        viewModelScope.launch {
            modelManager.addModel(model.copy(role = role))
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(model.path)
            if (file.exists()) file.delete()
            modelManager.deleteModel(model)
            _storageStats.value = StorageUtils.compute(app)
        }
    }

    fun auditInstalledModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val records = modelManager.getModelsSnapshot().map(ModelArtifactManager::createAuditRecord)
            ModelArtifactManager.logAudit(records)
            _statusMessage.value = "Model audit logged for ${records.size} record(s)."
        }
    }

    fun cleanupFailedPrimaryModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val appModelsDir = ModelArtifactManager.modelsDir(app)
            val targets = modelManager.getModelsSnapshot()
                .filter { it.role != ModelRole.DRAFT.name && it.verificationStatus == VerificationStatus.FAILED }

            if (targets.isEmpty()) {
                _statusMessage.value = "No failed primary models to clean up."
                return@launch
            }

            var removed = 0
            var skipped = 0
            targets.forEach { model ->
                Log.i(
                    "PocketNode",
                    "Failed primary cleanup[start]: id=${model.id} name=${model.name} path=${model.path} status=${model.verificationStatus}"
                )
                val result = ModelArtifactManager.cleanupFailedPrimaryArtifact(model, appModelsDir)
                when {
                    result.deletedFile -> {
                        modelManager.deleteModel(model)
                        removed += 1
                        Log.i("PocketNode", "Failed primary cleanup[success]: id=${model.id} name=${model.name}")
                    }
                    !File(model.path).exists() && result.skippedReason == null -> {
                        modelManager.deleteModel(model)
                        removed += 1
                        Log.i("PocketNode", "Failed primary cleanup[stale-record-removed]: id=${model.id} name=${model.name}")
                    }
                    else -> {
                        skipped += 1
                        Log.i(
                            "PocketNode",
                            "Failed primary cleanup[skipped]: id=${model.id} name=${model.name} reason=${result.skippedReason ?: "unknown"}"
                        )
                    }
                }
            }

            _storageStats.value = StorageUtils.compute(app)
            _statusMessage.value = "Failed primary cleanup removed $removed model(s), skipped $skipped."
        }
    }

    /**
     * Recomputes SHA-256 and re-inspects the on-disk GGUF for a single model,
     * without re-downloading or re-importing it, and persists the refreshed
     * verification status. Reuses the same validation primitive
     * [ModelArtifactManager.validateExistingFile] the import path already uses —
     * no new hashing/verification logic. P29 RC3.4.
     */
    fun reverifyModel(model: LocalModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(model.path)
            if (!file.exists()) {
                modelManager.addModel(
                    model.copy(
                        verificationStatus = VerificationStatus.FAILED,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
                _statusMessage.value = "Reverify failed for ${model.name}: file not found."
                return@launch
            }

            val intent = ImportIntent(
                displayName = model.name,
                intendedRole = model.role,
                expectedSha256 = model.sha256
            )
            val result = runCatching { ModelArtifactManager.validateExistingFile(file, intent) }
            result.onSuccess { artifact ->
                modelManager.addModel(
                    model.copy(
                        sha256 = artifact.sha256,
                        sizeBytes = artifact.bytesCopied,
                        verificationStatus = artifact.verificationStatus,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
                _statusMessage.value = "Reverified ${model.name}: ${artifact.verificationStatus}"
            }.onFailure { e ->
                modelManager.addModel(
                    model.copy(
                        verificationStatus = VerificationStatus.FAILED,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
                _statusMessage.value = "Reverify failed for ${model.name}: ${e.message}"
            }
        }
    }

    /**
     * Writes a read-only JSON summary of every installed model's audit record
     * (same fields [ModelArtifactManager.createAuditRecord] already computes for
     * logging) to the app's external files "Download" directory — already
     * exposed via the existing FileProvider `file_paths.xml` `downloads` entry,
     * the same one AppUpdater already uses — and hands the resulting content://
     * Uri to [onReady] so the caller can launch a share/copy intent. No new
     * FileProvider path or permission was added. P29 RC3.4.
     */
    fun exportInventory(context: Context, onReady: (Uri) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val records = modelManager.getModelsSnapshot().map(ModelArtifactManager::createAuditRecord)
            val json = buildString {
                append("[\n")
                records.forEachIndexed { index, r ->
                    append("  {\n")
                    append("    \"modelId\": ${jsonString(r.modelId)},\n")
                    append("    \"displayName\": ${jsonString(r.displayName)},\n")
                    append("    \"role\": ${jsonString(r.role)},\n")
                    append("    \"verificationStatus\": ${jsonString(r.verificationStatus)},\n")
                    append("    \"resolvedPath\": ${jsonString(r.resolvedPath)},\n")
                    append("    \"fileExists\": ${r.fileExists},\n")
                    append("    \"fileSizeBytes\": ${r.fileSizeBytes},\n")
                    append("    \"sha256Prefix\": ${r.sha256Prefix?.let { jsonString(it) } ?: "null"},\n")
                    append("    \"ggufMagicValid\": ${r.ggufMagicValid},\n")
                    append("    \"generalArchitecture\": ${r.generalArchitecture?.let { jsonString(it) } ?: "null"}\n")
                    append("  }")
                    append(if (index < records.size - 1) ",\n" else "\n")
                }
                append("]\n")
            }

            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return@launch
            downloadsDir.mkdirs()
            val exportFile = File(downloadsDir, "pocketnode_model_inventory.json")
            exportFile.writeText(json)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", exportFile
            )
            withContext(Dispatchers.Main) { onReady(uri) }
        }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun rescanModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelsDir = File(app.getExternalFilesDir(null), "models").also { it.mkdirs() }

            // Delete stale .part files when no operator download is active.
            // A .part older than 12 hours is an abandoned download — safe to remove.
            if (operatorDownloadJob?.isActive != true) {
                val staleCutoff = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
                modelsDir.listFiles { f -> f.name.endsWith(".part", ignoreCase = true) }
                    ?.filter { it.lastModified() < staleCutoff }
                    ?.forEach { it.delete() }
            }

            // Only consider proper .gguf files — .part files are never registered as models.
            val diskFiles = modelsDir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }
                ?.associateBy { it.absolutePath } ?: emptyMap()
            val dbModels = models.value

            // Remove DB records for files no longer on disk
            dbModels.filter { !File(it.path).exists() }
                .forEach { modelManager.deleteModel(it) }

            // Add DB records for new disk files
            val dbPaths = dbModels.map { it.path }.toSet()
            diskFiles.values.filter { it.absolutePath !in dbPaths }.forEach { file ->
                val matched = RECOMMENDED_MODELS.firstOrNull { remote ->
                    normalizedModelName(remote.name) == normalizedModelName(file.nameWithoutExtension)
                }
                modelManager.addModel(LocalModel(
                    id = UUID.randomUUID().toString(),
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    contextLength = 4096,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    role = matched?.defaultRole?.name ?: ModelRole.MAIN.name,
                    family = matched?.family,
                    verificationStatus = VerificationStatus.NOT_CHECKED
                ))
            }

            // Hash files that are new or whose content has changed
            models.value.forEach { model -> hashModelIfNeeded(model) }

            _storageStats.value = StorageUtils.compute(app)
        }
    }

    private suspend fun hashModelIfNeeded(model: LocalModel) {
        val file = File(model.path)
        if (!file.exists()) return
        if (model.verificationStatus == VerificationStatus.HASHING) return

        val currentSize = file.length()
        val currentMtime = file.lastModified()

        // Skip if already hashed and file is unchanged.
        // Exception: if status is still UNKNOWN_HASH, re-check the stored hash against the
        // registry in case new entries were added since the model was first imported.
        // This is a fast path (no file I/O) — just a map lookup + string compare.
        if (model.sha256 != null
            && model.sizeBytes == currentSize
            && model.lastModified == currentMtime
        ) {
            if (model.verificationStatus == VerificationStatus.UNKNOWN_HASH) {
                val knownHash = HashUtils.KNOWN_HASHES[file.nameWithoutExtension]
                if (knownHash != null) {
                    val upgraded = if (knownHash.equals(model.sha256, ignoreCase = true))
                        VerificationStatus.VERIFIED else VerificationStatus.FAILED
                    Log.i("PocketNode", "hashModelIfNeeded: ${file.nameWithoutExtension} upgraded ${model.verificationStatus} -> $upgraded (registry match)")
                    modelManager.addModel(model.copy(verificationStatus = upgraded))
                }
            }
            return
        }

        modelManager.addModel(model.copy(
            verificationStatus = VerificationStatus.HASHING,
            sizeBytes = currentSize,
            lastModified = currentMtime
        ))

        try {
            val hash = HashUtils.sha256(file)
            val baseName = file.nameWithoutExtension
            val knownHash = HashUtils.KNOWN_HASHES[baseName]
            val status = when {
                knownHash == null -> VerificationStatus.UNKNOWN_HASH
                knownHash.equals(hash, ignoreCase = true) -> VerificationStatus.VERIFIED
                else -> VerificationStatus.FAILED
            }
            modelManager.addModel(model.copy(
                sha256 = hash,
                sizeBytes = currentSize,
                lastModified = currentMtime,
                verificationStatus = status,
                lastCheckedAt = System.currentTimeMillis()
            ))
        } catch (_: Exception) {
            modelManager.addModel(model.copy(
                verificationStatus = VerificationStatus.FAILED,
                lastCheckedAt = System.currentTimeMillis()
            ))
        }
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
