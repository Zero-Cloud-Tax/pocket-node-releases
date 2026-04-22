package com.pocketnode.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.app.licensing.LicenseManager
import com.pocketnode.app.licensing.ProGate
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun SettingsScreen(
    settings: SettingsViewModel,
    licenseManager: LicenseManager,
    isPro: Boolean,
    onNavigateToUpgrade: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val temperature by settings.temperature.collectAsState()
    val topP by settings.topP.collectAsState()
    val topK by settings.topK.collectAsState()
    val maxTokens by settings.maxTokens.collectAsState()
    val contextSize by settings.contextSize.collectAsState()
    val threadCount by settings.threadCount.collectAsState()
    val systemPrompt by settings.systemPrompt.collectAsState()
    val templateName by settings.templateName.collectAsState()
    val edgeApiEnabled by settings.edgeApiEnabled.collectAsState()

    var licenseInput by remember { mutableStateOf("") }
    var licenseStatus by remember { mutableStateOf<Boolean?>(null) }

    val context = LocalContext.current
    val deviceIp = remember { getDeviceIp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Inference ──
        SettingsSection("Inference Parameters") {
            LabeledSlider(
                label = "Temperature",
                value = temperature,
                displayValue = "%.1f".format(temperature),
                range = 0.1f..2.0f,
                onValueChange = { settings.setTemperature(it) }
            )
            LabeledSlider(
                label = "Top-P",
                value = topP,
                displayValue = "%.2f".format(topP),
                range = 0.0f..1.0f,
                onValueChange = { settings.setTopP(it) }
            )
            LabeledSlider(
                label = "Top-K",
                value = topK.toFloat(),
                displayValue = "$topK",
                range = 1f..100f,
                onValueChange = { settings.setTopK(it.toInt()) }
            )
            LabeledSlider(
                label = "Max Tokens",
                value = maxTokens.toFloat(),
                displayValue = "$maxTokens",
                range = 64f..2048f,
                onValueChange = { settings.setMaxTokens(it.toInt()) }
            )
            LabeledSlider(
                label = "Context Size",
                value = contextSize.toFloat(),
                displayValue = "$contextSize",
                range = 512f..8192f,
                steps = 15,
                onValueChange = { settings.setContextSize((it / 512).toInt() * 512) }
            )
            LabeledSlider(
                label = "Threads",
                value = threadCount.toFloat(),
                displayValue = "$threadCount",
                range = 1f..8f,
                steps = 6,
                onValueChange = { settings.setThreadCount(it.toInt()) }
            )
        }

        // ── System Prompt ──
        SettingsSection("System Prompt") {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { settings.setSystemPrompt(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. You are a helpful assistant.") },
                minLines = 3,
                maxLines = 6
            )
        }

        // ── Template ──
        SettingsSection("Prompt Template") {
            val templates = listOf("ChatML", "Llama3", "Alpaca")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { name ->
                    FilterChip(
                        selected = templateName == name,
                        onClick = { settings.setTemplateName(name) },
                        label = { Text(name) }
                    )
                }
            }
        }

        // ── Edge API (Pro) ──
        ProGate(
            isUnlocked = isPro,
            onUpgrade = onNavigateToUpgrade,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsSection("Edge API (Port 11434)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Enable Edge API", style = MaterialTheme.typography.bodyLarge)
                        if (edgeApiEnabled && deviceIp != null) {
                            Text(
                                "http://$deviceIp:11434",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Switch(
                        checked = edgeApiEnabled,
                        onCheckedChange = { enabled ->
                            settings.setEdgeApiEnabled(enabled)
                            if (enabled) promptBatteryExemption(context)
                        }
                    )
                }
            }
        }

        // ── License ──
        SettingsSection("License") {
            if (isPro) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Pro Unlocked",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text(
                    "Enter your license key to unlock Pro features.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = licenseInput,
                    onValueChange = {
                        licenseInput = it.uppercase()
                        licenseStatus = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("PN-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX") },
                    singleLine = true,
                    isError = licenseStatus == false,
                    supportingText = when (licenseStatus) {
                        false -> ({ Text("Invalid license key") })
                        true -> ({ Text("Activated!") })
                        else -> null
                    }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            licenseStatus = licenseManager.validateAndSave(licenseInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Activate")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onNavigateToUpgrade,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Buy a license key — \$19 one-time")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )
        HorizontalDivider()
        content()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    displayValue: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            displayValue,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun promptBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}

private fun getDeviceIp(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}
