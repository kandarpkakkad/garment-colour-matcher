@file:OptIn(ExperimentalMaterial3Api::class)

package com.garmentcolour.app.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garmentcolour.app.AppGraph
import com.garmentcolour.app.pipeline.Input
import com.garmentcolour.app.pipeline.Step
import com.garmentcolour.app.ui.common.Words
import com.garmentcolour.app.ui.theme.Stage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** S1 Capture. docs/03-frontend/screens.md, docs/01-platform/camera/lld.md. */
class CaptureViewModel(private val graph: AppGraph) : ViewModel() {
    private val _step = MutableStateFlow<Step?>(null)
    /** The step being worked on, or null when idle. */
    val step: StateFlow<Step?> = _step

    sealed interface Event {
        data class Done(val id: String) : Event
        data class Failed(val message: String) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun process(input: Input) {
        if (_step.value != null) return
        _step.value = Step.DECODING
        viewModelScope.launch {
            try {
                val id = graph.pipeline.process(input) { _step.value = it }
                _events.send(Event.Done(id))
            } catch (e: Exception) {
                _events.send(Event.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _step.value = null
            }
        }
    }

    fun failed(message: String) {
        viewModelScope.launch { _events.send(Event.Failed(message)) }
    }
}

@Composable
fun CaptureScreen(graph: AppGraph, onResult: (String) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    val vm: CaptureViewModel = viewModel { CaptureViewModel(graph) }
    val step by vm.step.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(false) }
    var askedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        askedOnce = true
    }
    // After a second refusal Android stops showing the dialog; then only settings can grant it.
    val permanentlyDenied = askedOnce && !granted &&
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.process(Input.Import(uri))
    }

    var flashOn by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(!graph.onboarded) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is CaptureViewModel.Event.Done -> onResult(e.id)
                is CaptureViewModel.Event.Failed -> snackbar.showSnackbar(e.message)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val binding = remember { CameraBinding(context) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var rawOn by remember { mutableStateOf(graph.rawPreferred) }
    var rawSupported by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(granted, rawOn) {
        if (!granted) return@LaunchedEffect
        try {
            binding.bind(lifecycleOwner, previewView, rawOn, flashOn)
            rawSupported = binding.rawSupported
        } catch (e: Exception) {
            snackbar.showSnackbar("The camera could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }
    LaunchedEffect(flashOn) { binding.setFlash(flashOn) }

    val busy = step != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Garment Colour") },
                actions = {
                    TextButton(onClick = onHistory) { Text("History") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Stage), contentAlignment = Alignment.Center) {
                if (granted) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize().alpha(if (busy) 0.4f else 1f),
                    )
                } else {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "The camera shows the outfit you want to check.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (permanentlyDenied) {
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            }) { Text("Open settings", color = Color.White) }
                        } else {
                            OutlinedButton(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) {
                                Text("Allow camera", color = Color.White)
                            }
                        }
                    }
                }
                step?.let { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Color.White)
                        Text(Words.step(s), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (granted) {
                // D23: RAW is opt-in until its gate is passed; disabled, with the reason, where
                // the camera cannot do it.
                FilterChip(
                    selected = rawOn && rawSupported == true,
                    onClick = {
                        rawOn = !rawOn
                        graph.rawPreferred = rawOn
                    },
                    enabled = !busy && rawSupported == true,
                    label = {
                        Text(
                            when {
                                rawSupported == false -> "This camera does not offer RAW"
                                rawOn -> "RAW on"
                                else -> "RAW off"
                            },
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                ) { Text("Open a photo") }
                Shutter(enabled = granted && !busy) {
                    val raw = binding.rawActive
                    val file = graph.store.newPending(if (raw) "dng" else "jpg")
                    binding.take(
                        file,
                        context.mainExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                vm.process(if (raw) Input.CameraRaw(file) else Input.Camera(file))
                            }

                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                vm.failed("Could not take the photo: ${exception.message ?: "camera error ${exception.imageCaptureError}"}")
                            }
                        },
                    )
                }
                TextButton(onClick = { flashOn = !flashOn }, enabled = granted && !busy) {
                    Text(if (flashOn) "Flash on" else "Flash off")
                }
            }
        }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Garment Colour") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Garment Colour shows your clothes in their true colours.")
                    Text("• Take a photo of an outfit. The app finds each garment and removes the colour cast of the light, keeping folds and texture.")
                    Text("• Everything happens on this phone. Nothing is uploaded; there is no account.")
                    Text("• If it says it is unsure, tap something white or grey in the photo.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    graph.onboarded = true
                    showOnboarding = false
                    if (!granted) scope.launch { askCamera.launch(Manifest.permission.CAMERA) }
                }) { Text("Got it") }
            },
        )
    }
}

/** Design system §4: a white disc with a grey ring. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .border(4.dp, Color(0xFF8A8A8A), CircleShape)
            .padding(8.dp)
            .background(Color.White, CircleShape)
            .clickable(enabled = enabled, onClickLabel = "Take a photo", onClick = onClick),
    ) { Spacer(Modifier.height(0.dp)) }
}
