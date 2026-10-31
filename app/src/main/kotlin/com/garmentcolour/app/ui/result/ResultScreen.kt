@file:OptIn(ExperimentalMaterial3Api::class)

package com.garmentcolour.app.ui.result

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garmentcolour.app.AppGraph
import com.garmentcolour.app.data.CaptureEntity
import com.garmentcolour.app.data.CaptureWithGarments
import com.garmentcolour.app.pipeline.TapResult
import com.garmentcolour.app.ui.common.GarmentRow
import com.garmentcolour.app.ui.common.Picture
import com.garmentcolour.app.ui.common.Words
import com.garmentcolour.app.ui.common.rememberPicture
import com.garmentcolour.app.ui.theme.Stage
import com.garmentcolour.engine.Box as FractionBox
import com.garmentcolour.engine.Colour
import com.garmentcolour.engine.EstimateCodec
import com.garmentcolour.engine.Estimator
import com.garmentcolour.engine.Label
import com.garmentcolour.engine.LabelMap
import com.garmentcolour.engine.Level
import com.garmentcolour.engine.Tier
import com.garmentcolour.engine.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** S2 Result. docs/03-frontend/screens.md. */
class ResultViewModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    sealed interface Load {
        data object Loading : Load
        data object Missing : Load
        data class Loaded(val c: CaptureWithGarments) : Load
    }

    val state: StateFlow<Load> = graph.store.observe(id)
        .map { it?.let { c -> Load.Loaded(c) } ?: Load.Missing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _labels = MutableStateFlow<LabelMap?>(null)
    val labels: StateFlow<LabelMap?> = _labels

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _deleted = Channel<Unit>(Channel.CONFLATED)
    val deleted = _deleted.receiveAsFlow()

    fun loadLabels() {
        viewModelScope.launch(Dispatchers.IO) {
            _labels.value = try {
                graph.store.labels(id).inputStream().buffered().use { LabelMap.read(it) }
            } catch (_: Exception) {
                null // the highlight is a nicety; the numbers do not depend on it
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _messages.send(e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = false
            }
        }
    }

    fun retap(u: Double, v: Double) = work {
        val r = graph.pipeline.retap(id, u, v)
        if (r is TapResult.Refused) _messages.send(Words.refusal(r.why))
    }

    fun useEstimate() = work { graph.pipeline.useEstimate(id) }

    fun box(b: FractionBox) = work { graph.pipeline.box(id, b) }

    fun saveToGallery() = work {
        withContext(Dispatchers.IO) { graph.store.saveToGallery(id) }
        _messages.send("Saved to Pictures/Garment Colour")
    }

    fun delete() = work {
        withContext(Dispatchers.IO) { graph.store.delete(id) }
        _deleted.send(Unit)
    }
}

private enum class Mode { VIEW, TAP, BOX }

@Composable
fun ResultScreen(graph: AppGraph, id: String, onBack: () -> Unit) {
    val vm: ResultViewModel = viewModel(key = id) { ResultViewModel(graph, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.deleted.collect { onBack() } }

    val loaded = (state as? ResultViewModel.Load.Loaded)?.c
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text(loaded?.let { Words.date(it.capture.createdAt) } ?: "") },
                actions = {
                    if (loaded != null) {
                        TextButton(onClick = vm::saveToGallery, enabled = !busy) { Text("Save to gallery") }
                        IconButton(onClick = { confirmDelete = true }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ResultViewModel.Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ResultViewModel.Load.Missing -> Text("This capture no longer exists.", Modifier.align(Alignment.Center))
                is ResultViewModel.Load.Loaded -> {
                    LaunchedEffect(s.c.capture) { vm.loadLabels() }
                    Content(graph, s.c, labels, busy, vm)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this capture?") },
            text = { Text("Its photos and colours are removed from this phone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Content(graph: AppGraph, cg: CaptureWithGarments, labels: LabelMap?, busy: Boolean, vm: ResultViewModel) {
    val c = cg.capture
    var showOriginal by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Mode.VIEW) }
    var selected by remember { mutableStateOf<String?>(null) }
    var detailsOpen by remember { mutableStateOf(false) }
    val garments = cg.garments
    val tier = Tier.entries.firstOrNull { it.name == c.tier }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Photo(
            c = c,
            file = if (showOriginal) graph.store.original(c.id) else graph.store.corrected(c.id),
            labels = labels,
            selected = selected,
            mode = mode,
            busy = busy,
            onTap = { u, v -> mode = Mode.VIEW; showOriginal = false; vm.retap(u, v) },
            onBox = { b -> mode = Mode.VIEW; showOriginal = false; selected = null; vm.box(b) },
        )

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !showOriginal,
                    onClick = { showOriginal = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Corrected") }
                SegmentedButton(
                    selected = showOriginal,
                    onClick = { showOriginal = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Original") }
            }

            when (mode) {
                Mode.TAP -> ModeBar("Tap something white or grey in the photo") { mode = Mode.VIEW }
                Mode.BOX -> ModeBar("Drag a box around a garment") { mode = Mode.VIEW }
                Mode.VIEW -> Confidence(c, busy, onTap = { mode = Mode.TAP; showOriginal = false }, onUseEstimate = vm::useEstimate)
            }

            Text("Garments", style = MaterialTheme.typography.titleMedium)
            if (garments.isEmpty()) Text("No garment found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            garments.forEach { g ->
                val isSelected = selected == g.label
                GarmentRow(
                    g,
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { selected = if (isSelected) null else g.label }
                        .padding(horizontal = 8.dp),
                )
            }
            if (garments.isEmpty() || tier == Tier.BOX || tier == Tier.CENTRE) {
                OutlinedButton(onClick = { mode = Mode.BOX; showOriginal = false }, enabled = !busy) {
                    Text(if (tier == Tier.BOX || tier == Tier.CENTRE) "Select a different area" else "Drag a box around one")
                }
            }

            HorizontalDivider()
            TextButton(onClick = { detailsOpen = !detailsOpen }) {
                Text("Details")
                Icon(if (detailsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (detailsOpen) Details(c)
        }
    }
}

@Composable
private fun ModeBar(text: String, onCancel: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Design system §4; the reasons are illuminant LLD §5. */
@Composable
private fun Confidence(c: CaptureEntity, busy: Boolean, onTap: () -> Unit, onUseEstimate: () -> Unit) {
    when (Words.level(c)) {
        Level.HIGH -> Row(
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Confident")
        }
        Level.LOW -> OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Low confidence", style = MaterialTheme.typography.titleSmall)
                }
                Text(Words.reason(c.estReason))
                OutlinedButton(onClick = onTap, enabled = !busy) { Text("Tap something white or grey") }
            }
        }
        Level.USER -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Set from the spot you tapped",
                Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onUseEstimate, enabled = !busy) { Text("Use the estimate") }
            TextButton(onClick = onTap, enabled = !busy) { Text("Tap again") }
        }
    }
}

@Composable
private fun Photo(
    c: CaptureEntity,
    file: java.io.File,
    labels: LabelMap?,
    selected: String?,
    mode: Mode,
    busy: Boolean,
    onTap: (Double, Double) -> Unit,
    onBox: (FractionBox) -> Unit,
) {
    // The capture row changes on every re-render, so it is the reload key.
    val picture by rememberPicture(file, c, 2048)
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val selectedCode = selected?.let { name -> Label.entries.firstOrNull { it.name == name }?.code }
    val mask = remember(labels, selectedCode) { if (labels != null && selectedCode != null) highlight(labels, selectedCode) else null }

    Box(Modifier.fillMaxWidth().background(Stage).padding(8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(c.width.toFloat() / c.height.coerceAtLeast(1))) {
            when (val p = picture) {
                is Picture.Ready -> Image(
                    p.bitmap.asImageBitmap(),
                    contentDescription = "The photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Picture.Missing -> Text("Image missing", Modifier.align(Alignment.Center), color = Color.White)
                Picture.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            if (mask != null) {
                Image(mask.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            val gestures = when (mode) {
                Mode.TAP -> Modifier.pointerInput(mode) {
                    detectTapGestures { o ->
                        onTap((o.x / size.width).toDouble().coerceIn(0.0, 1.0), (o.y / size.height).toDouble().coerceIn(0.0, 1.0))
                    }
                }
                Mode.BOX -> Modifier.pointerInput(mode) {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragEnd
                            dragStart = null
                            dragEnd = null
                            if (a != null && b != null) {
                                onBox(
                                    FractionBox(
                                        (a.x / size.width).toDouble(), (a.y / size.height).toDouble(),
                                        (b.x / size.width).toDouble(), (b.y / size.height).toDouble(),
                                    ),
                                )
                            }
                        },
                        onDragCancel = { dragStart = null; dragEnd = null },
                    )
                }
                Mode.VIEW -> Modifier
            }
            Canvas(Modifier.fillMaxSize().then(gestures)) {
                val a = dragStart
                val b = dragEnd
                if (a != null && b != null) {
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                    val sz = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                    drawRect(Color.Black, tl, sz, style = Stroke(width = 6f))
                    drawRect(Color.White, tl, sz, style = Stroke(width = 3f))
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/** Everything but the garment dimmed to 45 % black; nothing chromatic added (design system §4). */
private fun highlight(labels: LabelMap, code: Int): Bitmap {
    val dim = 0x73000000
    val px = IntArray(labels.size) { i -> if (labels.code(i) == code) 0 else dim }
    return Bitmap.createBitmap(px, labels.width, labels.height, Bitmap.Config.ARGB_8888)
}

@Composable
private fun Details(c: CaptureEntity) {
    val applied = Vec3(c.appliedR, c.appliedG, c.appliedB)
    val combined = if (c.estR != null && c.estG != null && c.estB != null) Vec3(c.estR, c.estG, c.estB) else null
    val cct = Colour.cct(c.appliedX, c.appliedY)
    val answers = EstimateCodec.decode(c.estAnswers)
    val names = mapOf(
        Estimator.GRAY_WORLD to "Gray world (shown, not voting)",
        Estimator.SHADES_OF_GRAY to "Shades of gray",
        Estimator.GRAY_EDGE to "Gray edge",
        Estimator.SKIN to "Skin",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailLine(
            "Light",
            (cct?.let { "≈ ${(it / 50).roundToInt() * 50} K · " } ?: "") +
                String.format(Locale.ROOT, "x %.4f y %.4f", c.appliedX, c.appliedY),
        )
        for (k in Estimator.entries) {
            val a = answers[k]
            val text = when {
                a == null -> "did not answer"
                combined == null -> "answered"
                else -> String.format(Locale.ROOT, "%.1f° from the estimate", Colour.angleDeg(a, combined))
            }
            DetailLine(names.getValue(k), text)
        }
        if (combined != null && c.appliedMethod == "NEUTRAL_TAP") {
            DetailLine("Your tap", String.format(Locale.ROOT, "%.1f° from the estimate", Colour.angleDeg(applied, combined)))
        }
        DetailLine("Disagreement", c.estDisagreement?.let { String.format(Locale.ROOT, "%.1f°", it) } ?: "—")
        DetailLine("Scene used", "${(c.estSupport * 100).roundToInt()} %")
        DetailLine("Found with", Words.tier(c.tier))
        val raw = c.capturePath == "RAW"
        DetailLine(
            "Photo",
            "${c.width} × ${c.height}, " +
                (if (c.source == "IMPORT") "imported" else if (raw) "RAW from the camera" else "from the camera") +
                (if (c.downscaled) String.format(Locale.ROOT, ", scaled down from %.0f MP", c.sourceMegapixels) else ""),
        )
        if (raw) DetailLine("Exposure", String.format(Locale.ROOT, "×%.2f", c.exposureGain))
        DetailLine("Engine", c.engineVersion.toString())
    }
}

@Composable
private fun DetailLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
