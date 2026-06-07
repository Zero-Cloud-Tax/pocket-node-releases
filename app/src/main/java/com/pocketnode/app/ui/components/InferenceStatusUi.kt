package com.pocketnode.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.inference.BackendInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InferenceStatusCardState(
    val selectedModelName: String?,
    val resolvedModelPath: String?,
    val verificationStatus: String?,
    val isDraftModel: Boolean,
    val isPrimaryModel: Boolean,
    val backendName: String,
    val lastInferenceAtMillis: Long?,
    val modelLoaded: Boolean
)

@Composable
fun VerificationStatusBadge(status: String?) {
    val (label, color) = when (status) {
        VerificationStatus.VERIFIED -> "VERIFIED" to Color(0xFF2E7D32)
        VerificationStatus.UNKNOWN_HASH -> "UNKNOWN_HASH" to Color(0xFFEF6C00)
        VerificationStatus.FAILED -> "FAILED" to MaterialTheme.colorScheme.error
        VerificationStatus.HASHING -> "HASHING" to MaterialTheme.colorScheme.primary
        else -> return
    }
    Badge(containerColor = color.copy(alpha = 0.14f)) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ModelRoleBadge(label: String, color: Color) {
    Badge(containerColor = color.copy(alpha = 0.14f)) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun BackendStatusChip(backendName: String) {
    val label = BackendInfo.displayLabel(backendName)
    val accent = if (BackendInfo.isAccelerated(label)) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun InferenceStatusCard(
    state: InferenceStatusCardState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.selectedModelName ?: "No model selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.modelLoaded) "Model loaded" else "Model not loaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.modelLoaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BackendStatusChip(state.backendName)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VerificationStatusBadge(state.verificationStatus)
                if (state.isPrimaryModel) {
                    ModelRoleBadge("PRIMARY_MODEL", Color(0xFF1565C0))
                }
                if (state.isDraftModel) {
                    ModelRoleBadge("DRAFT_MODEL", Color(0xFFB45309))
                }
            }

            StatusLine("Resolved file", state.resolvedModelPath ?: "Unavailable", mono = true)
            StatusLine("Verification", state.verificationStatus ?: "Unknown")
            StatusLine("Backend", BackendInfo.displayLabel(state.backendName))
            StatusLine("Last inference", formatInferenceTime(state.lastInferenceAtMillis))
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatInferenceTime(lastInferenceAtMillis: Long?): String {
    if (lastInferenceAtMillis == null) return "Not available yet"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return formatter.format(Date(lastInferenceAtMillis))
}
