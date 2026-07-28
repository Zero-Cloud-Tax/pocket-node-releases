package com.pocketnode.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketnode.app.data.AppDatabase
import com.pocketnode.app.data.KnowledgeRepository
import com.pocketnode.app.ui.screens.KnowledgeScreen
import com.pocketnode.app.ui.screens.KnowledgeViewModel
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.inference.ApiServer
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.GenerationService
import com.pocketnode.app.data.OPERATOR_SPEC
import com.pocketnode.app.setup.FirstRunState
import com.pocketnode.app.setup.FirstRunViewModel
import com.pocketnode.app.ui.ViewModelFactory
import com.pocketnode.app.ui.screens.ChatScreen
import com.pocketnode.app.ui.screens.ConversationListScreen
import com.pocketnode.app.ui.screens.ModelsScreen
import com.pocketnode.app.ui.screens.ModelsViewModel
import com.pocketnode.app.ui.screens.RecommendedProfileScreen
import com.pocketnode.app.ui.screens.SetupRequiredScreen
import com.pocketnode.app.diagnostics.DiagnosticsViewModel
import com.pocketnode.app.ui.screens.DiagnosticsScreen
import com.pocketnode.app.ui.screens.SettingsScreen
import com.pocketnode.app.ui.screens.SettingsViewModel
import com.pocketnode.app.ui.screens.UpgradeScreen
import com.pocketnode.app.ui.screens.GalleryScreen
import com.pocketnode.app.ui.screens.PromptLabScreen
import com.pocketnode.app.ui.screens.AskImageScreen
import com.pocketnode.app.ui.screens.settingsDataStore
import com.pocketnode.app.ui.theme.PocketNodeTheme
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pocketnode.app.ui.ChatNodeEntryResolver

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MainApplication

        val db = AppDatabase.getInstance(applicationContext)

        val modelManager = ModelManager(this)
        val chatRepository = ChatRepository(db.chatDao())
        val capturedSettingsDataStore = this.settingsDataStore

        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by rememberSaveable { mutableStateOf(systemDark) }

            PocketNodeTheme(darkTheme = isDarkTheme) {
                var showUpdateDialog by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<com.pocketnode.app.updater.AppUpdater.UpdateInfo?>(null) }
                var showContaminatedDialog by remember { mutableStateOf(false) }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!isDebuggable) {
                        val info = com.pocketnode.app.updater.AppUpdater.checkForUpdate(context)
                        if (info != null) {
                            updateInfo = info
                            showUpdateDialog = true
                        }
                    }
                }

                if (showUpdateDialog && updateInfo != null) {
                    AlertDialog(
                        onDismissRequest = { showUpdateDialog = false },
                        title = { Text("Update Available") },
                        text = { Text("Version ${updateInfo!!.version} of Pocket Node is now available. Would you like to download and install it?") },
                        confirmButton = {
                            TextButton(onClick = {
                                com.pocketnode.app.updater.AppUpdater.downloadAndInstall(context, updateInfo!!)
                                showUpdateDialog = false
                            }) {
                                Text("Update")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                com.pocketnode.app.updater.AppUpdater.dismissVersion(context, updateInfo!!.version)
                                showUpdateDialog = false
                            }) {
                                Text("Later")
                            }
                        }
                    )
                }

                if (showContaminatedDialog) {
                    AlertDialog(
                        onDismissRequest = { showContaminatedDialog = false },
                        title = { Text("Protected Shutdown") },
                        text = {
                            Text(
                                "Pocket Node entered a protected shutdown after inference did " +
                                "not stop cleanly. To prevent a native crash, the Edge API " +
                                "will stay disabled until the app starts in a fresh process.\n\n" +
                                "Close the app, then reopen it to restart Pocket Node safely."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { finishAffinity() }) {
                                Text("Close App")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showContaminatedDialog = false }) {
                                Text("Dismiss")
                            }
                        }
                    )
                }

                val navController = rememberNavController()

                val factory = ViewModelFactory(app, modelManager, chatRepository, capturedSettingsDataStore)
                val chatVm: ChatViewModel = viewModel(factory = factory)

                val settingsVm: SettingsViewModel = viewModel(factory = factory)
                val edgeApiEnabled by settingsVm.edgeApiEnabled.collectAsState()
                val firstRunComplete by settingsVm.firstRunComplete.collectAsState()

                // Only redirect when DataStore has loaded and explicitly says first run is NOT complete.
                // firstRunComplete == null means DataStore is still loading — do not redirect yet.
                LaunchedEffect(firstRunComplete) {
                    if (firstRunComplete == false) {
                        navController.navigate("setup") {
                            popUpTo("gallery") { inclusive = true }
                        }
                    }
                }

                val firstRunFactory = remember(settingsVm) {
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            FirstRunViewModel(app, settingsVm) as T
                    }
                }
                val firstRunVm: FirstRunViewModel = viewModel(factory = firstRunFactory)

                val isPro by app.licenseManager.isProFlow.collectAsState(initial = false)

                // Launcher must be declared unconditionally — composable hook rules prohibit conditional calls.
                // The actual launch is gated on Build.VERSION.SDK_INT inside the LaunchedEffect below.
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* best-effort; service start is attempted either way */ }

                // Start/stop GenerationService when the Edge API toggle changes
                LaunchedEffect(edgeApiEnabled, isPro) {
                    if (edgeApiEnabled && isPro) {

                        // Refuse to restart inside a process that had a drain timeout.
                        // isStopping stays true permanently after a missed drain, meaning
                        // the process may hold leaked native allocations. Only a fresh
                        // process (i.e. user closes and reopens the app) is safe.
                        if (ApiServer.isContaminated) {
                            showContaminatedDialog = true
                            return@LaunchedEffect
                        }

                        // Request POST_NOTIFICATIONS on Android 13+ before starting the foreground service.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        // Request battery optimization exemption if not already granted.
                        // PARTIAL_WAKE_LOCK keeps the CPU awake but does NOT prevent Doze from
                        // blocking network. Without this exemption, TCP connections to port 11434
                        // are suspended during Doze idle windows (~30 min of device inactivity).
                        val pm = getSystemService(PowerManager::class.java)
                        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                            try {
                                startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$packageName")
                                    }
                                )
                            } catch (_: Exception) {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }

                        startForegroundService(
                            Intent(this@MainActivity, GenerationService::class.java)
                        )
                    } else {
                        stopService(Intent(this@MainActivity, GenerationService::class.java))
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: ""
                val setupState by firstRunVm.state
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                val title = when {
                    currentRoute.startsWith("chat") -> "AI Chat"
                    currentRoute.startsWith("ask_image") -> "Ask Image"
                    currentRoute.startsWith("prompt_lab") -> "Prompt Lab"
                    currentRoute.startsWith("models") -> "Model Hub"
                    currentRoute.startsWith("conversations") -> "Conversations"
                    currentRoute == "settings" -> "Settings"
                    currentRoute == "upgrade" -> "Go Pro"
                    currentRoute == "setup" -> "Welcome"
                    currentRoute == "diagnostics" -> "Diagnostics"
                    currentRoute == "knowledge" -> "Local Knowledge"
                    else -> "Pocket Node"
                }
                // Show back on setup only when the user reached RecommendedProfileScreen via import
                val showBack = currentRoute != "gallery" &&
                    !(currentRoute == "setup" && setupState !is FirstRunState.ModelFound)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                if (showBack) {
                                    IconButton(onClick = {
                                        if (currentRoute == "setup") {
                                            firstRunVm.resetToMissing()
                                        } else {
                                            navController.popBackStack()
                                        }
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NavHost(navController = navController, startDestination = "gallery") {
                            
                            composable("gallery") {
                                GalleryScreen(
                                    onNavigate = { route ->
                                        scope.launch {
                                            val targetRoute = if (route == "models/chat") {
                                                val decision = ChatNodeEntryResolver.resolve(
                                                    models = modelManager.getModelsSnapshot()
                                                )
                                                Log.i(
                                                    "PocketNode",
                                                    "Chat node entry: activeMainModelId=${decision.activeMainModelId ?: "none"} " +
                                                        "selectedDefaultMainModelId=${decision.selectedDefaultMainModelId ?: "none"} " +
                                                        "selectedDefaultMainModelName=${decision.selectedDefaultMainModelName ?: "none"} " +
                                                        "verificationStatus=${decision.verificationStatus ?: "none"} " +
                                                        "role=${decision.role ?: "none"} " +
                                                        "isPrimary=${decision.isPrimary} isDraft=${decision.isDraft} " +
                                                        "fileExists=${decision.fileExists} reason=${decision.reason} " +
                                                        "targetRoute=${decision.modelPathToOpen ?: "models/chat"}"
                                                )
                                                if (decision.redirectsToModelHub) {
                                                    snackbarHostState.showSnackbar(
                                                        decision.userMessage ?: "Choose a chat model in Model Hub."
                                                    )
                                                }
                                                decision.modelPathToOpen?.let {
                                                    "chat/${Uri.encode(it)}/${ChatViewModel.DEFAULT_CONVERSATION_ID}"
                                                } ?: "models/chat"
                                            } else {
                                                route
                                            }
                                            navController.navigate(targetRoute)
                                        }
                                    }
                                )
                            }

                            composable("setup") {
                                // System back gesture: only meaningful when on RecommendedProfileScreen
                                BackHandler(enabled = setupState is FirstRunState.ModelFound) {
                                    firstRunVm.resetToMissing()
                                }
                                val modelsVm: ModelsViewModel = viewModel(factory = factory)
                                val scope = rememberCoroutineScope()
                                when (val s = setupState) {
                                    is FirstRunState.Loading -> Box(Modifier.fillMaxSize()) {
                                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                                    }
                                    is FirstRunState.ModelMissing -> {
                                        val context = LocalContext.current
                                        val operatorDownloadState by modelsVm.operatorDownloadState.collectAsState()
                                        SetupRequiredScreen(
                                            onNavigateToModels = { navController.navigate("models/manage") },
                                            onImportModel = { uri ->
                                                modelsVm.importModel(context, uri) {
                                                    firstRunVm.rescan()
                                                }
                                            },
                                            operatorSpec = OPERATOR_SPEC,
                                            operatorDownloadState = operatorDownloadState,
                                            onDownloadOperator = {
                                                OPERATOR_SPEC?.let { spec ->
                                                    modelsVm.downloadOperatorModel(spec) {
                                                        firstRunVm.scanEnvironment()
                                                    }
                                                }
                                            },
                                            onCancelOperatorDownload = {
                                                modelsVm.cancelOperatorDownload()
                                            },
                                            onUseExistingOperator = {
                                                OPERATOR_SPEC?.let { spec ->
                                                    modelsVm.useExistingOperatorModel(spec) {
                                                        firstRunVm.scanEnvironment()
                                                    }
                                                }
                                            },
                                            onReplaceOperator = {
                                                OPERATOR_SPEC?.let { spec ->
                                                    modelsVm.replaceOperatorModel(spec) {
                                                        firstRunVm.scanEnvironment()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    is FirstRunState.ModelFound -> RecommendedProfileScreen(
                                        profile = s.profile,
                                        onApply = {
                                            scope.launch {
                                                firstRunVm.applyRecommendedProfile(s.profile)
                                                navController.navigate("chat/${Uri.encode(s.modelPath)}/1") {
                                                    popUpTo("setup") { inclusive = true }
                                                }
                                            }
                                        },
                                        onCustomize = {
                                            settingsVm.setFirstRunComplete(true)
                                            navController.navigate("settings") {
                                                popUpTo("setup") { inclusive = true }
                                            }
                                        }
                                    )
                                }
                            }

                            composable("models/{mode}") { backStackEntry ->
                                val mode = backStackEntry.arguments?.getString("mode") ?: "manage"
                                val vm: ModelsViewModel = viewModel(factory = factory)
                                val benchmarkMode by settingsVm.benchmarkMode.collectAsState()
                                val lastStats = chatVm.lastInferenceStats.value
                                ModelsScreen(
                                    viewModel = vm,
                                    isPro = isPro,
                                    onModelSelected = { model ->
                                        when (mode) {
                                            "chat" -> navController.navigate("chat/${Uri.encode(model.path)}/${ChatViewModel.DEFAULT_CONVERSATION_ID}")
                                            "ask_image" -> navController.navigate("ask_image/${Uri.encode(model.path)}")
                                            "prompt_lab" -> navController.navigate("prompt_lab/${Uri.encode(model.path)}")
                                            else -> navController.navigate("chat/${Uri.encode(model.path)}/${ChatViewModel.DEFAULT_CONVERSATION_ID}")
                                        }
                                    },
                                    onNavigateToSettings = { navController.navigate("settings") },
                                    onNavigateToUpgrade = { navController.navigate("upgrade") },
                                    benchmarkMode = benchmarkMode,
                                    lastInferenceStats = lastStats
                                )
                            }

                            composable("chat/{modelPath}/{conversationId}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments
                                    ?.getString("modelPath")
                                    ?.let { Uri.decode(it) }
                                val conversationId = backStackEntry.arguments
                                    ?.getString("conversationId")
                                    ?.toLongOrNull()
                                    ?: ChatViewModel.DEFAULT_CONVERSATION_ID

                                val temperature by settingsVm.temperature.collectAsState()
                                val topP by settingsVm.topP.collectAsState()
                                val topK by settingsVm.topK.collectAsState()
                                val maxTokens by settingsVm.maxTokens.collectAsState()
                                val contextSize by settingsVm.contextSize.collectAsState()
                                val threadCount by settingsVm.threadCount.collectAsState()
                                val gpuLayers by settingsVm.gpuLayers.collectAsState()
                                val systemPrompt by settingsVm.systemPrompt.collectAsState()
                                val template by settingsVm.selectedTemplate.collectAsState()
                                val speculativeEnabled by settingsVm.speculativeEnabled.collectAsState()
                                val draftModelId by settingsVm.draftModelId.collectAsState()
                                val speculativeDraftCount by settingsVm.speculativeDraftCount.collectAsState()
                                val batchSize by settingsVm.batchSize.collectAsState()
                                val ubatchSize by settingsVm.ubatchSize.collectAsState()
                                val benchmarkMode by settingsVm.benchmarkMode.collectAsState()
                                
                                val compatibilityStatus by chatVm.draftCompatibilityStatus
                                val mainMeta by chatVm.mainModelMetadata
                                val draftMeta by chatVm.draftModelMetadata
                                val warningString = if (speculativeEnabled && compatibilityStatus == com.pocketnode.app.inference.CompatibilityStatus.BAD) {
                                    "Compatibility: BAD (Expect low acceptance)\n" +
                                    "Main: ${mainMeta?.architecture ?: "Unknown"} (${mainMeta?.vocabSize ?: 0})\n" +
                                    "Draft: ${draftMeta?.architecture ?: "Unknown"} (${draftMeta?.vocabSize ?: 0})"
                                } else if (speculativeEnabled && compatibilityStatus == com.pocketnode.app.inference.CompatibilityStatus.GOOD) {
                                    "Compatibility: GOOD"
                                } else null

                                LaunchedEffect(modelPath, conversationId, contextSize, threadCount, gpuLayers) {
                                    chatVm.bindConversation(conversationId)
                                    modelPath?.let {
                                        chatVm.loadModel(it, contextSize, threadCount, gpuLayers)
                                    }
                                }

                                // Load/unload draft model when speculative settings change
                                LaunchedEffect(speculativeEnabled, draftModelId, contextSize, threadCount, gpuLayers) {
                                    if (speculativeEnabled && draftModelId.isNotBlank()) {
                                        val draftValidationError = chatVm.validateModelFile(draftModelId)
                                        if (draftValidationError != null) {
                                            android.util.Log.i(
                                                "PocketNode",
                                                "Draft model path invalid; disabling speculative decoding and clearing stale draft selection: $draftValidationError"
                                            )
                                            settingsVm.setSpeculativeEnabled(false)
                                            settingsVm.setDraftModelId("")
                                            chatVm.unloadDraftModel()
                                        } else {
                                            chatVm.loadDraftModel(
                                                draftModelPath = draftModelId,
                                                mainContextSize = contextSize,
                                                threadCount = threadCount,
                                                nGpuLayers = 0 // Force draft to CPU (KleidiAI) for max batch-1 speed
                                            )
                                        }
                                    } else {
                                        chatVm.unloadDraftModel()
                                    }
                                }

                                ChatScreen(
                                    messages = chatVm.messages,
                                    currentAssistantMessage = chatVm.visibleAssistantMessage.value,
                                    isGenerating = chatVm.visibleIsGenerating.value,
                                    isStopping = chatVm.isStopping.value,
                                    isLoadingModel = chatVm.isLoadingModel.value,
                                    isModelReady = chatVm.isModelReady.value,
                                    modelName = chatVm.modelName.value,
                                    modelError = chatVm.modelError.value,
                                    backendName = chatVm.backendName.value,
                                    selectedModelPath = chatVm.selectedModelPath.value,
                                    verificationStatus = chatVm.selectedModelVerificationStatus.value,
                                    isDraftModel = chatVm.selectedModelIsDraft.value,
                                    isPrimaryModel = chatVm.selectedModelIsPrimary.value,
                                    lastInferenceAtMillis = chatVm.lastSuccessfulInferenceAtMillis.value,
                                    onSendMessage = { text, imageBytes, _, _, _ ->
                                        chatVm.sendMessage(
                                            text = text,
                                            imageBytes = imageBytes,
                                            conversationId = conversationId,
                                            clearConversationFirst = false,
                                            temp = temperature,
                                            topP = topP,
                                            topK = topK,
                                            maxTokens = maxTokens,
                                            systemPrompt = systemPrompt,
                                            template = template,
                                            speculativeEnabled = speculativeEnabled,
                                            nDraft = speculativeDraftCount,
                                            batchSize = batchSize,
                                            ubatchSize = ubatchSize,
                                            benchmarkMode = benchmarkMode
                                        )
                                    },
                                    onClearChat = { chatVm.clearChat(conversationId) },
                                    onStopGeneration = { chatVm.stopGeneration() },
                                    onDismissError = { chatVm.dismissError() },
                                    onNavigateToSettings = { navController.navigate("settings") },
                                    onNavigateToModels = { navController.navigate("models/manage") },
                                    onOpenConversations = { navController.navigate("conversations/${Uri.encode(modelPath ?: "")}") },
                                    benchmarkMode = benchmarkMode,
                                    lastInferenceStats = chatVm.lastInferenceStats.value,
                                    compatibilityWarning = warningString,
                                    compatibilityStatus = compatibilityStatus,
                                    speculativeEnabled = speculativeEnabled,
                                    draftCount = speculativeDraftCount,
                                    benchmarkState = chatVm.benchmarkState.value,
                                    onTune = if (benchmarkMode && speculativeEnabled) {{ chatVm.runSpeculativeBenchmark() }} else null,
                                    onDismissBenchmark = { chatVm.dismissBenchmark() },
                                    attachedChunks = chatVm.attachedChunks,
                                    onRemoveChunk = { id -> chatVm.removeChunk(id) },
                                    onClearChunks = { chatVm.clearAttachedChunks() },
                                    onNavigateToKnowledge = { navController.navigate("knowledge") },
                                    sessionSnapshot = chatVm.sessionSnapshot.value,
                                    onResetSession = { chatVm.resetSessionContext(conversationId) },
                                    onRetryInterrupted = { messageId -> chatVm.retryInterrupted(conversationId, messageId) },
                                    onDismissInterrupted = { messageId -> chatVm.dismissInterrupted(messageId) }
                                )
                            }

                            composable("conversations/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments?.getString("modelPath")?.let { Uri.decode(it) } ?: ""
                                ConversationListScreen(
                                    chatVm = chatVm,
                                    onConversationSelected = { conversationId ->
                                        navController.navigate("chat/${Uri.encode(modelPath)}/$conversationId")
                                    }
                                )
                            }

                            composable("ask_image/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments?.getString("modelPath")?.let { Uri.decode(it) } ?: ""
                                val contextSize by settingsVm.contextSize.collectAsState()
                                val threadCount by settingsVm.threadCount.collectAsState()
                                val gpuLayers by settingsVm.gpuLayers.collectAsState()
                                val maxTokens by settingsVm.maxTokens.collectAsState()
                                AskImageScreen(
                                    modelPath = modelPath,
                                    contextSize = contextSize,
                                    threadCount = threadCount,
                                    gpuLayers = gpuLayers,
                                    maxTokens = maxTokens,
                                    chatVm = chatVm,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }

                            composable("prompt_lab/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments?.getString("modelPath")?.let { Uri.decode(it) } ?: ""
                                val contextSize by settingsVm.contextSize.collectAsState()
                                val threadCount by settingsVm.threadCount.collectAsState()
                                val gpuLayers by settingsVm.gpuLayers.collectAsState()
                                PromptLabScreen(
                                    modelPath = modelPath,
                                    contextSize = contextSize,
                                    threadCount = threadCount,
                                    gpuLayers = gpuLayers,
                                    chatVm = chatVm,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }

                            composable("settings") {
                                val allModels by db.modelDao().getAllModels()
                                    .collectAsState(initial = emptyList())
                                val draftModels = allModels.filter { it.role == "DRAFT" }
                                SettingsScreen(
                                    settings = settingsVm,
                                    licenseManager = app.licenseManager,
                                    isPro = isPro,
                                    draftModels = draftModels,
                                    onNavigateToUpgrade = { navController.navigate("upgrade") },
                                    onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                                    onNavigateToKnowledge = { navController.navigate("knowledge") }
                                )
                            }

                            composable("diagnostics") {
                                val diagFactory = remember(settingsVm) {
                                    object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                            DiagnosticsViewModel(app, settingsVm, modelManager) as T
                                    }
                                }
                                val diagVm: DiagnosticsViewModel = viewModel(factory = diagFactory)
                                DiagnosticsScreen(
                                    vm = diagVm,
                                    lastInferenceStats = chatVm.lastInferenceStats.value,
                                    activeModelName = chatVm.modelName.value,
                                    backendName = chatVm.backendName.value,
                                    activeModelPath = chatVm.selectedModelPath.value,
                                    verificationStatus = chatVm.selectedModelVerificationStatus.value,
                                    isDraftModel = chatVm.selectedModelIsDraft.value,
                                    isPrimaryModel = chatVm.selectedModelIsPrimary.value,
                                    lastInferenceAtMillis = chatVm.lastSuccessfulInferenceAtMillis.value,
                                    modelLoaded = chatVm.isModelReady.value
                                )
                            }

                            composable("knowledge") {
                                val knowledgeRepo = remember {
                                    KnowledgeRepository(db, db.knowledgeDao())
                                }
                                val knowledgeFactory = remember(knowledgeRepo) {
                                    object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                            KnowledgeViewModel(app, knowledgeRepo) as T
                                    }
                                }
                                val knowledgeVm: KnowledgeViewModel = viewModel(factory = knowledgeFactory)
                                KnowledgeScreen(
                                    vm = knowledgeVm,
                                    onAttachChunk = { chunk -> chatVm.attachChunk(chunk) },
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToChat = { navController.popBackStack() }
                                )
                            }

                            composable("upgrade") {
                                UpgradeScreen(
                                    licenseManager = app.licenseManager,
                                    isPro = isPro,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
