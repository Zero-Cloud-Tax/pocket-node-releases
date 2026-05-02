package com.pocketnode.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketnode.app.data.AppDatabase
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.GenerationService
import com.pocketnode.app.ui.ViewModelFactory
import com.pocketnode.app.ui.screens.ChatScreen
import com.pocketnode.app.ui.screens.ModelsScreen
import com.pocketnode.app.ui.screens.ModelsViewModel
import com.pocketnode.app.ui.screens.SettingsScreen
import com.pocketnode.app.ui.screens.SettingsViewModel
import com.pocketnode.app.ui.screens.UpgradeScreen
import com.pocketnode.app.ui.screens.GalleryScreen
import com.pocketnode.app.ui.screens.PromptLabScreen
import com.pocketnode.app.ui.screens.AskImageScreen
import com.pocketnode.app.ui.screens.settingsDataStore
import com.pocketnode.app.ui.theme.PocketNodeTheme

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
                var updateInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    val info = com.pocketnode.app.updater.AppUpdater.checkForUpdate(context)
                    if (info != null) {
                        updateInfo = info
                        showUpdateDialog = true
                    }
                }

                if (showUpdateDialog && updateInfo != null) {
                    AlertDialog(
                        onDismissRequest = { showUpdateDialog = false },
                        title = { Text("Update Available") },
                        text = { Text("Version ${updateInfo!!.first} of Pocket Node is now available. Would you like to download and install it?") },
                        confirmButton = {
                            TextButton(onClick = {
                                com.pocketnode.app.updater.AppUpdater.downloadAndInstall(context, updateInfo!!.second, updateInfo!!.first)
                                showUpdateDialog = false
                            }) {
                                Text("Update")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUpdateDialog = false }) {
                                Text("Later")
                            }
                        }
                    )
                }

                val navController = rememberNavController()
                val factory = ViewModelFactory(app, modelManager, chatRepository, capturedSettingsDataStore)

                val settingsVm: SettingsViewModel = viewModel(factory = factory)
                val edgeApiEnabled by settingsVm.edgeApiEnabled.collectAsState()

                val isPro by app.licenseManager.isProFlow.collectAsState(initial = false)

                // Start/stop GenerationService when the Edge API toggle changes
                LaunchedEffect(edgeApiEnabled, isPro) {
                    if (edgeApiEnabled && isPro) {
                        startForegroundService(
                            Intent(this@MainActivity, GenerationService::class.java)
                        )
                    } else {
                        stopService(Intent(this@MainActivity, GenerationService::class.java))
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: ""

                val title = when {
                    currentRoute.startsWith("chat") -> "AI Chat"
                    currentRoute.startsWith("ask_image") -> "Ask Image"
                    currentRoute.startsWith("prompt_lab") -> "Prompt Lab"
                    currentRoute.startsWith("models") -> "Model Hub"
                    currentRoute == "settings" -> "Settings"
                    currentRoute == "upgrade" -> "Go Pro"
                    else -> "Pocket Node"
                }
                val showBack = currentRoute != "gallery"

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                if (showBack) {
                                    IconButton(onClick = { navController.popBackStack() }) {
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
                                    onNavigate = { route -> navController.navigate(route) }
                                )
                            }

                            composable("models/{mode}") { backStackEntry ->
                                val mode = backStackEntry.arguments?.getString("mode") ?: "manage"
                                val vm: ModelsViewModel = viewModel(factory = factory)
                                ModelsScreen(
                                    viewModel = vm,
                                    isPro = isPro,
                                    onModelSelected = { model ->
                                        when (mode) {
                                            "chat" -> navController.navigate("chat/${Uri.encode(model.path)}")
                                            "ask_image" -> navController.navigate("ask_image/${Uri.encode(model.path)}")
                                            "prompt_lab" -> navController.navigate("prompt_lab/${Uri.encode(model.path)}")
                                            else -> { /* In manage mode, maybe just show details or go to chat as default */ 
                                                navController.navigate("chat/${Uri.encode(model.path)}") 
                                            }
                                        }
                                    },
                                    onNavigateToSettings = { navController.navigate("settings") },
                                    onNavigateToUpgrade = { navController.navigate("upgrade") }
                                )
                            }

                            composable("chat/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments
                                    ?.getString("modelPath")
                                    ?.let { Uri.decode(it) }

                                val chatVm: ChatViewModel = viewModel(factory = factory)
                                val temperature by settingsVm.temperature.collectAsState()
                                val topP by settingsVm.topP.collectAsState()
                                val topK by settingsVm.topK.collectAsState()
                                val maxTokens by settingsVm.maxTokens.collectAsState()
                                val contextSize by settingsVm.contextSize.collectAsState()
                                val threadCount by settingsVm.threadCount.collectAsState()
                                val gpuLayers by settingsVm.gpuLayers.collectAsState()
                                val systemPrompt by settingsVm.systemPrompt.collectAsState()
                                val template by settingsVm.selectedTemplate.collectAsState()

                                LaunchedEffect(modelPath, contextSize, threadCount, gpuLayers) {
                                    modelPath?.let {
                                        chatVm.loadModel(it, contextSize, threadCount, gpuLayers)
                                    }
                                }

                                ChatScreen(
                                    messages = chatVm.messages,
                                    currentAssistantMessage = chatVm.currentAssistantMessage.value,
                                    isGenerating = chatVm.isGenerating.value,
                                    isLoadingModel = chatVm.isLoadingModel.value,
                                    isModelReady = chatVm.isModelReady.value,
                                    modelName = chatVm.modelName.value,
                                    modelError = chatVm.modelError.value,
                                    backendName = chatVm.backendName.value,
                                    onSendMessage = { text, imageBytes, _, _, _ ->
                                        chatVm.sendMessage(
                                            text = text,
                                            imageBytes = imageBytes,
                                            conversationId = 1L,
                                            temp = temperature,
                                            topP = topP,
                                            topK = topK,
                                            maxTokens = maxTokens,
                                            systemPrompt = systemPrompt,
                                            template = template
                                        )
                                    },
                                    onClearChat = { chatVm.clearChat(1L) },
                                    onStopGeneration = { chatVm.stopGeneration() },
                                    onDismissError = { chatVm.dismissError() },
                                    onNavigateToSettings = { navController.navigate("settings") }
                                )
                            }

                            composable("ask_image/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments?.getString("modelPath")?.let { Uri.decode(it) } ?: ""
                                AskImageScreen(
                                    modelPath = modelPath,
                                    factory = factory,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }

                            composable("prompt_lab/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments?.getString("modelPath")?.let { Uri.decode(it) } ?: ""
                                PromptLabScreen(
                                    modelPath = modelPath,
                                    factory = factory,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }

                            composable("settings") {
                                SettingsScreen(
                                    settings = settingsVm,
                                    licenseManager = app.licenseManager,
                                    isPro = isPro,
                                    onNavigateToUpgrade = { navController.navigate("upgrade") }
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
