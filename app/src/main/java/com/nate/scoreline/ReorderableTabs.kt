package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Equal-width tab row. Tap to select; press and hold, then drag sideways to move a tab.
 * The tab swaps with its neighbor once dragged past the halfway point of that neighbor's slot.
 * The new order is reported through onReorder when the finger lifts.
 */
@Composable
fun ReorderableTabRow(
    order: List<Int>,
    label: (Int) -> String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var working by remember(order) { mutableStateOf(order) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val primary = MaterialTheme.colorScheme.primary
    val lifted = MaterialTheme.colorScheme.secondaryContainer

    fun endDrag() {
        dragging = null
        dragOffset = 0f
        currentOnReorder(working)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width }) {
            working.forEach { id ->
                // key(): the dragged tab keeps its gesture handler while it moves between slots.
                key(id) {
                    val isDragging = dragging == id
                    Box(
                        Modifier
                            .weight(1f)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragOffset else 0f
                                val sc = if (isDragging) 1.06f else 1f
                                scaleX = sc
                                scaleY = sc
                            }
                            .background(if (isDragging) lifted else Color.Transparent, RoundedCornerShape(10.dp))
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = id
                                        dragOffset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.x
                                        val cell = if (working.isEmpty()) 0f else rowWidth.toFloat() / working.size
                                        val idx = working.indexOf(id)
                                        if (cell > 0f && idx >= 0) {
                                            if (dragOffset > cell / 2 && idx < working.lastIndex) {
                                                working = working.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                                dragOffset -= cell
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (dragOffset < -cell / 2 && idx > 0) {
                                                working = working.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                                dragOffset += cell
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = { endDrag() },
                                )
                            }
                            .clickable { currentOnSelect(id) }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(id),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected == id) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected == id) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        // Selection indicator under the chosen tab.
        Row(Modifier.fillMaxWidth()) {
            working.forEach { id ->
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp)
                        .height(3.dp)
                        .background(
                            if (selected == id && dragging == null) primary else Color.Transparent,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                        ),
                )
            }
        }
        HorizontalDivider()
    }
}
