@file:OptIn(ExperimentalMaterial3Api::class)

package com.garmentcolour.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garmentcolour.app.AppGraph
import com.garmentcolour.app.BuildConfig
import com.garmentcolour.app.models.ModelStatus
import com.garmentcolour.engine.Engine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** S4 Settings. docs/03-frontend/screens.md. */
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val people by graph.segmenters.people.collectAsStateWithLifecycle()
    val clothes by graph.segmenters.clothes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val message = try {
                val n = graph.export(uri)
                if (n == 1) "Exported 1 capture" else "Exported $n captures"
            } catch (e: Exception) {
                "Could not export: ${e.message ?: e.javaClass.simpleName}"
            }
            exporting = false
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Settings") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section("Privacy") {
                Text(
                    "Photos and colours stay on this phone. Garment Colour has no internet permission, no account " +
                        "and no analytics. Save to gallery and Export data are the only ways anything leaves the app, " +
                        "and only when you use them.",
                )
            }
            Section("Models") {
                Text("People model (MediaPipe): ${words(people)}")
                Text("Clothes model (SegFormer): ${words(clothes)}")
            }
            Section("Export data") {
                Text("Every capture's light and garment colours as a JSON file. No photos.")
                OutlinedButton(
                    onClick = { export.launch("garment-colour-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json") },
                    enabled = !exporting,
                ) { Text("Export data") }
            }
            Section("About") {
                Text("Version ${BuildConfig.VERSION_NAME} · engine ${Engine.VERSION}")
                Text(
                    "People model: MediaPipe Selfie Multiclass (Google), Apache 2.0, used as published.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Clothes model: SegFormer B2 clothes (mattmdjaga, Hugging Face), quantised to 8 bits. Its training " +
                        "code is MIT; its weights' card states no licence terms, and it was trained on the ATR dataset, " +
                        "which has research terms.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun words(s: ModelStatus) = when (s) {
    ModelStatus.Loading -> "Loading…"
    is ModelStatus.Ready -> "Ready"
    ModelStatus.Missing -> "Not in this build"
    is ModelStatus.Failed -> "Failed: ${s.message}"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
