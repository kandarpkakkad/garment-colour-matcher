@file:OptIn(ExperimentalMaterial3Api::class)

package com.garmentcolour.app.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garmentcolour.app.AppGraph
import com.garmentcolour.app.data.CaptureWithGarments
import com.garmentcolour.app.ui.common.Picture
import com.garmentcolour.app.ui.common.Swatch
import com.garmentcolour.app.ui.common.Words
import com.garmentcolour.app.ui.common.rememberPicture
import com.garmentcolour.app.ui.theme.Stage
import com.garmentcolour.engine.Level

/** S3 History. docs/03-frontend/screens.md. */
@Composable
fun HistoryScreen(graph: AppGraph, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val flow = remember { graph.store.observeAll() }
    val items by flow.collectAsStateWithLifecycle(initialValue = null)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("History") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = items
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "Nothing yet. Captures you take appear here.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.capture.id }) { item ->
                        HistoryRow(graph, item, onOpen)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(graph: AppGraph, item: CaptureWithGarments, onOpen: (String) -> Unit) {
    val c = item.capture
    val picture by rememberPicture(graph.store.corrected(c.id), c, 256)
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).background(Stage), contentAlignment = Alignment.Center) {
            val p = picture
            if (p is Picture.Ready) {
                Image(p.bitmap.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Words.date(c.createdAt), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.garments.forEach { Swatch(it.hex, 20.dp) }
            }
            val marker = when (Words.level(c)) {
                Level.LOW -> "Low confidence"
                Level.USER -> "Tapped"
                Level.HIGH -> null
            }
            if (marker != null) {
                Text(marker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
