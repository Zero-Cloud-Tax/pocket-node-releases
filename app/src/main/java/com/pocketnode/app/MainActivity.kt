package com.pocketnode.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import com.pocketnode.app.data.AppDatabase
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.LlamaInference
import com.pocketnode.app.ui.ViewModelFactory
import com.pocketnode.app.ui.screens.ChatScreen
import com.pocketnode.app.ui.screens.ModelsScreen
import com.pocketnode.app.ui.screens.ModelsViewModel
import com.pocketnode.app.ui.theme.PocketNodeTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "pocketnode.db"
        ).fallbackToDestructiveMigration().build()
        val modelManager = ModelManager(this)
        val inference = LlamaInference()

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            var isDarkTheme by rememberSaveable { mutableStateOf(systemDarkTheme) }

            PocketNodeTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()
                val factory = ViewModelFactory(modelManager, inference, db.chatDao())
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(if (currentRoute?.startsWith("chat") == true) "Chat" else "PocketNode")
                            },
                            navigationIcon = {
                                if (currentRoute?.startsWith("chat") == true) {
                                    IconButton(onClick = { navController.popBackStack() }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = "models"
                        ) {
                            composable("models") {
                                val vm: ModelsViewModel = viewModel(factory = factory)
                                ModelsScreen(
                                    viewModel = vm,
                                    isDarkTheme = isDarkTheme,
                                    onToggleTheme = { isDarkTheme = !isDarkTheme },
                                    onModelSelected = { model ->
                                        val encodedPath = Uri.encode(model.path)
                                        navController.navigate("chat/$encodedPath")
                                    }
                                )
                            }
                            composable("chat/{modelPath}") { backStackEntry ->
                                val modelPath = backStackEntry.arguments
                                    ?.getString("modelPath")
                                    ?.let { Uri.decode(it) }
                                val vm: ChatViewModel = viewModel(factory = factory)

                                LaunchedEffect(modelPath) {
                                    modelPath?.let { vm.loadModel(it) }
                                }

                                ChatScreen(
                                    messages = vm.messages,
                                    currentAssistantMessage = vm.currentAssistantMessage.value,
                                    isGenerating = vm.isGenerating.value,
                                    isLoadingModel = vm.isLoadingModel.value,
                                    isModelReady = vm.isModelReady.value,
                                    modelName = vm.modelName.value,
                                    modelError = vm.modelError.value,
                                    isDarkTheme = isDarkTheme,
                                    onToggleTheme = { isDarkTheme = !isDarkTheme },
                                    onSendMessage = { text, t, p, k -> vm.sendMessage(text, 1L, t, p, k) },
                                    onClearChat = { vm.clearChat(1L) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
