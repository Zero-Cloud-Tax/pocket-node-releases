package com.pocketnode.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pocketnode.app.data.ChatDao
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.LlamaInference
import com.pocketnode.app.ui.screens.ModelsViewModel

class ViewModelFactory(
    private val modelManager: ModelManager? = null,
    private val inference: LlamaInference? = null,
    private val chatDao: ChatDao? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ModelsViewModel::class.java) -> {
                ModelsViewModel(modelManager!!) as T
            }
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> {
                ChatViewModel(inference!!, chatDao!!) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
