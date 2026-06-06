package com.pocketnode.app.ui

import com.pocketnode.app.data.HashUtils
import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import java.io.File

data class ChatNodeEntryDecision(
    val modelPathToOpen: String?,
    val reason: String,
    val userMessage: String?,
    val activeMainModelId: String?,
    val selectedDefaultMainModelId: String?,
    val selectedDefaultMainModelName: String?,
    val verificationStatus: String?,
    val role: String?,
    val isPrimary: Boolean,
    val isDraft: Boolean,
    val fileExists: Boolean
) {
    val redirectsToModelHub: Boolean
        get() = modelPathToOpen == null
}

object ChatNodeEntryResolver {
    fun resolve(
        models: List<LocalModel>,
        fileExists: (String) -> Boolean = { path -> File(path).exists() }
    ): ChatNodeEntryDecision {
        val selected = models
            .filter { it.role != ModelRole.DRAFT.name }
            .maxByOrNull { it.addedAt }

        if (selected == null) {
            return ChatNodeEntryDecision(
                modelPathToOpen = null,
                reason = "no_active_main_model",
                userMessage = "No active chat model selected. Import or download a main model in Model Hub.",
                activeMainModelId = null,
                selectedDefaultMainModelId = null,
                selectedDefaultMainModelName = null,
                verificationStatus = null,
                role = null,
                isPrimary = false,
                isDraft = false,
                fileExists = false
            )
        }

        val exists = fileExists(selected.path)
        val isDraft = selected.role == ModelRole.DRAFT.name
        val isPrimary = !isDraft

        if (isDraft) {
            return redirect(
                selected = selected,
                fileExists = exists,
                reason = "draft_selected_for_chat_node",
                message = "Draft model selected for chat. Choose a main chat model in Model Hub."
            )
        }

        if (!exists) {
            return redirect(
                selected = selected,
                fileExists = false,
                reason = "selected_main_model_file_missing",
                message = "Selected chat model file is missing. Re-import it from Model Hub."
            )
        }

        if (selected.verificationStatus == VerificationStatus.FAILED) {
            return redirect(
                selected = selected,
                fileExists = true,
                reason = "selected_main_model_failed_verification",
                message = "Selected chat model failed verification. Re-download or re-import it from Model Hub."
            )
        }

        val knownOperatorHash = HashUtils.KNOWN_HASHES[selected.name]
        if (knownOperatorHash != null && !selected.sha256.isNullOrBlank()) {
            if (!selected.sha256.equals(knownOperatorHash, ignoreCase = true)) {
                return redirect(
                    selected = selected,
                    fileExists = true,
                    reason = "selected_main_model_hash_mismatch",
                    message = "Selected chat model does not match the expected PocketNode Operator artifact."
                )
            }
        }

        return ChatNodeEntryDecision(
            modelPathToOpen = selected.path,
            reason = "using_default_main_model",
            userMessage = null,
            activeMainModelId = null,
            selectedDefaultMainModelId = selected.id,
            selectedDefaultMainModelName = selected.name,
            verificationStatus = selected.verificationStatus,
            role = selected.role,
            isPrimary = isPrimary,
            isDraft = isDraft,
            fileExists = true
        )
    }

    private fun redirect(
        selected: LocalModel,
        fileExists: Boolean,
        reason: String,
        message: String
    ) = ChatNodeEntryDecision(
        modelPathToOpen = null,
        reason = reason,
        userMessage = message,
        activeMainModelId = null,
        selectedDefaultMainModelId = selected.id,
        selectedDefaultMainModelName = selected.name,
        verificationStatus = selected.verificationStatus,
        role = selected.role,
        isPrimary = selected.role != ModelRole.DRAFT.name,
        isDraft = selected.role == ModelRole.DRAFT.name,
        fileExists = fileExists
    )
}
