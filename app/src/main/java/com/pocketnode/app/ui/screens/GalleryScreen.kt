package com.pocketnode.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun GalleryScreen(
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        GalleryItem(
            "AI Chat",
            "Engage in fluid, multi-turn conversations with Thinking Mode.",
            Icons.Default.Chat,
            "models/chat",
            listOf(Color(0xFF8B6BFF), Color(0xFF6B4EE6))
        ),
        GalleryItem(
            "Ask Image",
            "Use multimodal power to identify objects and solve visual puzzles.",
            Icons.Default.Image,
            "models/ask_image",
            listOf(Color(0xFF00E5FF), Color(0xFF00B3CC))
        ),
        GalleryItem(
            "Prompt Lab",
            "A dedicated workspace to test prompts and parameters.",
            Icons.Default.Science,
            "models/prompt_lab",
            listOf(Color(0xFFFF3366), Color(0xFFCC0033))
        ),
        GalleryItem(
            "Model Hub",
            "Download, manage, and import open-source LLMs.",
            Icons.Default.Folder,
            "models/manage",
            listOf(Color(0xFFFFAA00), Color(0xFFFF7700))
        ),
        GalleryItem(
            "Audio Scribe",
            "Transcribe and translate voice recordings in real-time.",
            Icons.Default.Mic,
            "",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468)),
            isComingSoon = true
        ),
        GalleryItem(
            "Agent Skills",
            "Local Ops Assistant: Query installed models, storage, RAM, and device health.",
            Icons.Default.Build,
            "",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468)),
            isComingSoon = true
        ),
        GalleryItem(
            "Settings",
            "Configure hardware acceleration, licenses, and defaults.",
            Icons.Default.Settings,
            "settings",
            listOf(Color(0xFF8D95B4), Color(0xFF4C5468))
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Text(
                text = "Pocket Node",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Explore, Experience, and Evaluate on-device AI.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items) { item ->
                GalleryTile(item = item, onClick = { onNavigate(item.route) })
            }
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
