package com.garmentcolour.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garmentcolour.app.data.CaptureEntity
import com.garmentcolour.app.data.GarmentEntity
import com.garmentcolour.app.pipeline.Bitmaps
import com.garmentcolour.app.pipeline.Step
import com.garmentcolour.engine.Label
import com.garmentcolour.engine.Level
import com.garmentcolour.engine.Method
import com.garmentcolour.engine.Reason
import com.garmentcolour.engine.Refusal
import com.garmentcolour.engine.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Loading, a bitmap, or a file that is not there. */
sealed interface Picture {
    data object Loading : Picture
    data class Ready(val bitmap: Bitmap) : Picture
    data object Missing : Picture
}

/** Decodes [file] off the main thread; [version] forces a reload after a re-render. */
@Composable
fun rememberPicture(file: File, version: Any?, maxSide: Int): State<Picture> =
    produceState<Picture>(Picture.Loading, file, version, maxSide) {
        value = withContext(Dispatchers.IO) {
            try {
                Bitmaps.forDisplay(file, maxSide)?.let { Picture.Ready(it) } ?: Picture.Missing
            } catch (_: Exception) {
                Picture.Missing
            }
        }
    }

fun parseHex(hex: String): Color = try {
    Color(0xFF000000 or hex.removePrefix("#").toLong(16))
} catch (_: NumberFormatException) {
    Color.Gray
}

/** Design system §4. */
@Composable
fun Swatch(hex: String, size: Dp = 48.dp) {
    val shape = RoundedCornerShape(size / 6)
    Spacer(
        Modifier
            .size(size)
            .background(parseHex(hex), shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    )
}

@Composable
fun GarmentRow(g: GarmentEntity, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(g.hex)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(Words.label(g.label), style = MaterialTheme.typography.titleMedium)
            Text(
                "${(g.fraction * 100).roundToInt()} % of the frame",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (g.clipped) {
                Text(
                    "Overexposed — colour unreliable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Text(g.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format(Locale.ROOT, "L %.1f a %.1f b %.1f", g.labL, g.labA, g.labB),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every piece of copy derived from a stored value (docs/03-frontend/screens.md). */
object Words {
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun date(epochMillis: Long): String = dateTime.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    fun label(name: String): String = Label.entries.firstOrNull { it.name == name }?.title ?: name

    fun step(step: Step) = when (step) {
        Step.DECODING -> "Reading the photo…"
        Step.FINDING_GARMENTS -> "Finding garments…"
        Step.READING_LIGHT -> "Reading the light…"
        Step.CORRECTING -> "Correcting…"
        Step.SAVING -> "Saving…"
    }

    fun reason(name: String?): String = when (name?.let { n -> Reason.entries.firstOrNull { it.name == n } }) {
        Reason.NO_SCENE -> "Not enough of the scene to read the light, so the photo is as the phone made it."
        Reason.LITTLE_SCENE -> "Most of the photo is garment; little else to read the light from."
        Reason.DISAGREE -> "The light could not be pinned down."
        Reason.ONE_ESTIMATOR -> "Only one way of reading the light worked on this photo."
        null -> "The light could not be pinned down."
    }

    fun refusal(why: Refusal) = when (why) {
        Refusal.TOO_BRIGHT -> "That spot is overexposed — tap something white or grey that isn't glaring."
        Refusal.TOO_DARK -> "That spot is too dark — tap something lit."
    }

    fun tier(name: String) = when (Tier.entries.firstOrNull { it.name == name }) {
        Tier.BOTH -> "Both models"
        Tier.CLOTHES_ONLY -> "Clothes model only"
        Tier.PEOPLE_ONLY -> "People model only"
        Tier.BOX -> "Your box"
        Tier.CENTRE -> "Centre of the frame"
        null -> name
    }

    /** USER when the light came from a tap, else the estimate's own level (storage schema). */
    fun level(c: CaptureEntity): Level =
        if (c.appliedMethod == Method.NEUTRAL_TAP.name) Level.USER
        else Level.entries.firstOrNull { it.name == c.estLevel } ?: Level.LOW
}
