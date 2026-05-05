package com.pocketnode.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.item
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class GalleryItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
    val gradientColors: List<Color>,
    val isComingSoon: Boolean = false
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(
    onNavigate: (String) -> Unit
) {
    val primaryNodes = listOf(
        GalleryItem(
            "Chat Node",
            "Conversational intelligence, fully on-device.",
            Icons.Default.Chat,
            "models/chat",
            listOf(Color(0xFF8B6BFF), Color(0xFF6B4EE6))
        ),
        GalleryItem(
            "Vision Node",
            "Ask questions about images instantly.",
            Icons.Default.Image,
            "models/ask_image",
            listOf(Color(0xFF00E5FF), Color(0xFF00B3CC))
        ),
        GalleryItem(
            "Prompt Forge",
            "Design, test, and refine prompts.",
            Icons.Default.Science,
            "models/prompt_lab",
            listOf(Color(0xFFFF3366), Color(0xFFCC0033))
        ),
        GalleryItem(
            "Model Vault",
            "Download and manage local models.",
            Icons.Default.Folder,
            "models/manage",
            listOf(Color(0xFFFFAA00), Color(0xFFFF7700))
        )
    )

    val systemNodes = listOf(
        GalleryItem(
            "Audio Node",
            "Transcribe and analyze speech locally.",
            Icons.Default.Mic,
            "",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468)),
            isComingSoon = true
        ),
        GalleryItem(
            "Skill Packs",
            "Equip models with domain-specific skills.",
            Icons.Default.Build,
            "",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468)),
            isComingSoon = true
        ),
        GalleryItem(
            "Agent Node",
            "Autonomous workflows powered locally.",
            Icons.Default.Memory,
            "",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468)),
            isComingSoon = true
        ),
        GalleryItem(
            "System Node",
            "Configure hardware, memory, and performance.",
            Icons.Default.Settings,
            "settings",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468))
        )
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 32.dp)
            ) {
                Text(
                    text = "Pocket Node",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Local-First Intelligence Engine",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Explore, run, and extend on-device AI.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "PRIMARY NODES",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        items(primaryNodes) { item ->
            GalleryTile(item = item, onClick = { onNavigate(item.route) })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "SYSTEM NODES",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        items(systemNodes) { item ->
            GalleryTile(item = item, onClick = { onNavigate(item.route) })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            NodeStatusBlock(isConnected = true)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            NodeFooter()
        }
    }
}

@Composable
fun GalleryTile(item: GalleryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = !item.isComingSoon) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(item.gradientColors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                        color = if (item.isComingSoon) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    if (item.isComingSoon) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "Soon",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }
        }
    }
}

@Composable
fun NodeStatusBlock(isConnected: Boolean) {
    val statusColor = if (isConnected) Color(0xFF00FF41) else Color(0xFFF59E0B)
    val statusText = if (isConnected) "Connected" else "Disconnected"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp)
            .border(1.dp, Color(0xFF1F2024), RoundedCornerShape(8.dp)),
        color = Color(0xFF16171A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "> STATUS",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            StatusRow(label = "Primary Node", value = "Mac Studio M1 Max (32GB RAM)")
            StatusRow(label = "Cluster", value = "ZCT Mesh — ", highlight = statusText, highlightColor = statusColor)
            StatusRow(label = "Engine", value = "Sovereign Brain v0.2")
            StatusRow(label = "Network", value = "Tailscale Mesh Active")
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    highlight: String? = null,
    highlightColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
            modifier = Modifier.weight(0.45f)
        )
        if (highlight != null) {
            Row(modifier = Modifier.weight(0.55f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE5E7EB)
                )
                Text(
                    text = highlight,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = highlightColor
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE5E7EB),
                modifier = Modifier.weight(0.55f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeFooter() {
    val badges = listOf(
        "Pocket Node Lite (Phi-3 Mini)",
        "Docker Runtime",
        "Local-First Blueprint",
        "OpenClaw Engine"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            badges.forEach { label -> NodeBadge(label) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Zero Cloud Tax",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF9CA3AF)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Sovereign AI  •  Data Ownership  •  Local-First Infrastructure",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF6B7280),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun NodeBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF1F2024)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF9CA3AF)
        )
    }
}
