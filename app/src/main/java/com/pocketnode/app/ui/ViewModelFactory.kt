package com.pocketnode.app.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.LlamaInference
import com.pocketnode.app.ui.screens.ModelsViewModel
import com.pocketnode.app.ui.screens.SettingsViewModel

class ViewModelFactory(
    private val app: MainApplication,
    private val modelManager: ModelManager? = null,
    private val chatRepository: ChatRepository? = null,
    private val settingsDataStore: DataStore<Preferences>? = null,
    private val defaultConversationId: Long = ChatViewModel.DEFAULT_CONVERSATION_ID
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ModelsViewModel::class.java) ->
                ModelsViewModel(modelManager!!, app) as T

            modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                ChatViewModel(app.inference, chatRepository!!, app, defaultConversationId) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(settingsDataStore!!) as T

            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
