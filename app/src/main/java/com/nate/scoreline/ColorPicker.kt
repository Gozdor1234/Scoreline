package com.nate.scoreline

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val swatches: List<Long> = listOf(
    0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF424242, 0xFF1B2230, 0xFF121212, 0xFF000000, 0xFF6D4C41, 0xFF8D6E63, 0xFFD7CCC8,
    0xFFE53935, 0xFFFF7043, 0xFFFFB300, 0xFFFDD835, 0xFF43A047, 0xFF00897B, 0xFF00ACC1, 0xFF0076B6, 0xFF1E3A8A, 0xFF8E24AA,
)

/** One settings row: label, what it affects, and a swatch of the color currently in use. */
@Composable
fun ColorSlotRow(slot: ColorSlot, shown: Color, isCustom: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(slot.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                slot.hint + if (isCustom) "  ·  ${ColorMath.toHex(shown.toArgbInt())}" else "  ·  Default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(shown)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

private fun Color.toArgbInt(): Int = ColorMath.argb(
    Math.round(red * 255), Math.round(green * 255), Math.round(blue * 255),
)

/**
 * Full color picker: tap or drag the spectrum for hue (left to right) and saturation
 * (top vivid, bottom pale), then set brightness with the slider. Swatches and a hex
 * field give quick exact picks.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    isCustom: Boolean,
    onApply: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(initial) { ColorMath.argbToHsv(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var bright by remember { mutableFloatStateOf(start[2]) }
    var hexText by remember { mutableStateOf(ColorMath.toHex(initial)) }
    val current = ColorMath.hsvToArgb(hue, sat, bright)

    fun setAll(argb: Int) {
        val h = ColorMath.argbToHsv(argb)
        // Grays have no hue; keep the previous hue so the spectrum marker doesn't jump to red.
        if (h[1] > 0f && h[2] > 0f) hue = h[0]
        sat = h[1]
        bright = h[2]
        hexText = ColorMath.toHex(argb)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(current))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { t ->
                            hexText = t
                            ColorMath.parseHex(t)?.let { c ->
                                val h = ColorMath.argbToHsv(c)
                                if (h[1] > 0f && h[2] > 0f) hue = h[0]
                                sat = h[1]
                                bright = h[2]
                            }
                        },
                        label = { Text("Hex code") },
                        singleLine = true,
                        isError = ColorMath.parseHex(hexText) == null,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spectrum(hue, sat) { h, s ->
                    hue = h
                    sat = s
                    if (bright < 0.15f) bright = 1f // picking a color while at black would otherwise show nothing
                    hexText = ColorMath.toHex(ColorMath.hsvToArgb(h, s, bright))
                }

                Column {
                    Text(
                        "Brightness  ${Math.round(bright * 100)}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
                            .background(Brush.horizontalGradient(listOf(Color.Black, Color(ColorMath.hsvToArgb(hue, sat, 1f))))),
                    )
                    Slider(
                        value = bright,
                        onValueChange = {
                            bright = it
                            hexText = ColorMath.toHex(ColorMath.hsvToArgb(hue, sat, it))
                        },
                        valueRange = 0f..1f,
                    )
                }

                swatches.chunked(10).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { sw ->
                            val argb = sw.toInt()
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable { setAll(argb) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(current) }) { Text("Apply") } },
        dismissButton = {
            Row {
                if (isCustom) TextButton(onClick = onReset) { Text("Use default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Spectrum(hue: Float, sat: Float, onPick: (Float, Float) -> Unit) {
    val hueStops = remember { (0..6).map { Color(ColorMath.hsvToArgb(it * 60f, 1f, 1f)) } }
    val pick by rememberUpdatedState(onPick)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    pick((p.x / size.width).coerceIn(0f, 1f) * 359.9f, 1f - (p.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val p = change.position
                    pick((p.x / size.width).coerceIn(0f, 1f) * 359.9f, 1f - (p.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(hueStops))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White)))
        val center = Offset(hue / 360f * size.width, (1f - sat) * size.height)
        drawCircle(Color.Black, radius = 12.dp.toPx(), center = center, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(Color.White, radius = 10.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
    }
}
