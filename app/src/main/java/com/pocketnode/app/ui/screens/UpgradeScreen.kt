package com.pocketnode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.app.licensing.LicenseManager
import kotlinx.coroutines.launch

private val PRO_FEATURES = listOf(
    "Unlimited local models",
    "Background execution (Edge API)",
    "Edge API — run prompts from your PC/server",
    "All future Pro updates",
    "Priority support"
)

@Composable
fun UpgradeScreen(
    licenseManager: LicenseManager,
    isPro: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var licenseInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Boolean?>(null) }
    var isActivating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (isPro) {
            // Already unlocked
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                "Pro Unlocked",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "You have full access to all Pro features.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
            return@Column
        }

        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )

        Text(
            "Unlock Pocket Node Pro",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            "\$19 — one-time payment, lifetime access",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Feature list
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PRO_FEATURES.forEach { feature ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(feature, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Purchase CTA — placeholder URL (replace with your Gumroad/Stripe link)
        Button(
            onClick = { /* TODO: open purchase URL */ },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Buy License Key — \$19", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
            "Already have a key? Enter it below:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = licenseInput,
            onValueChange = {
                licenseInput = it.uppercase()
                status = null
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("PN-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX") },
            singleLine = true,
            isError = status == false,
            supportingText = when (status) {
                false -> ({ Text("Invalid key — check for typos and try again") })
                true -> ({ Text("Activated! Pro features are now unlocked.") })
                else -> null
            }
        )

        Button(
            onClick = {
                isActivating = true
                scope.launch {
                    status = licenseManager.validateAndSave(licenseInput)
                    isActivating = false
                    if (status == true) onBack()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = licenseInput.isNotBlank() && !isActivating
        ) {
            if (isActivating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("Activate Key")
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}
