package com.pocketnode.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.app.inference.ChatViewModel

@Composable
fun ConversationListScreen(
    chatVm: ChatViewModel,
    onConversationSelected: (Long) -> Unit
) {
    val conversations by chatVm.getConversations().collectAsState(initial = emptyList())
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                chatVm.createConversation("New Conversation") { id ->
                    onConversationSelected(id)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("New Conversation")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations) { convo ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onConversationSelected(convo.id) },
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(convo.title)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Rename",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    renameTargetId = convo.id
                                    renameText = convo.title
                                }
                            )
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { chatVm.clearChat(convo.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (renameTargetId != null) {
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        chatVm.renameConversation(renameTargetId!!, renameText.trim())
                    }
                    renameTargetId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetId = null }) { Text("Cancel") }
            },
            title = { Text("Rename Conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        )
    }
}
