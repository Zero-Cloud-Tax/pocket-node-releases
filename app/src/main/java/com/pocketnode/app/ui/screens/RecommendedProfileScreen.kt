package com.pocketnode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.app.setup.RecommendedProfile

@Composable
fun RecommendedProfileScreen(
    profile: RecommendedProfile,
    onApply: () -> Unit,
    onCustomize: () -> Unit
) {
    var isApplying by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Recommended for This Device",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                ProfileRow("Mode", "CPU only")
                ProfileRow("Threads", profile.threads.toString())
                ProfileRow("GPU acceleration", "Off")
                ProfileRow("Speculative decoding", "Off")
                ProfileRow("Template", profile.templateName)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = profile.reasonCopy,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                if (!isApplying) {
                    isApplying = true
                    onApply()
                }
            },
            enabled = !isApplying,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isApplying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("Applying…")
            } else {
                Text("Apply Recommended Settings")
            }
        }

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = onCustomize,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Customize")
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
