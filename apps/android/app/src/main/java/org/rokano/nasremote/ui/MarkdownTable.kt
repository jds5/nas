package org.rokano.nasremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.rokano.nasremote.core.MessageBlock

/** Keep useful column widths and scroll the entire grid, including its header. */
@Composable
fun MarkdownTable(table: MessageBlock.Table) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val widths = remember(table) {
        List(table.rows.maxOf { it.size }) { col ->
            (table.rows.maxOf { row -> row.getOrElse(col) { "" }.sumOf { if (it.code > 255) 14 else 8 }.coerceAtMost(240) } + 24).coerceIn(128, 264).dp
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (scroll.maxValue > 0) "左右滑动查看表格" else "表格", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.labelSmall)
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.horizontalScroll(scroll)) {
                table.rows.forEachIndexed { index, row ->
                    Surface(color = when {
                        index == 0 -> MaterialTheme.colorScheme.secondaryContainer
                        index % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            widths.forEachIndexed { col, width ->
                                Text(readableMarkdown(row.getOrElse(col) { "" }), Modifier.width(width).padding(12.dp),
                                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = when (table.alignment.getOrNull(col)) { 0 -> TextAlign.Center; 1 -> TextAlign.End; else -> TextAlign.Start },
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        if (scroll.maxValue > 0) {
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            val thumb = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)) {
                val fraction = scroll.viewportSize.toFloat() / (scroll.viewportSize + scroll.maxValue)
                val length = (size.width * fraction).coerceAtLeast(20.dp.toPx()).coerceAtMost(size.width)
                drawRoundRect(track, cornerRadius = CornerRadius(size.height))
                drawRoundRect(thumb, topLeft = Offset((size.width - length) * scroll.value / scroll.maxValue, 0f), size = Size(length, size.height), cornerRadius = CornerRadius(size.height))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scope.launch { scroll.animateScrollTo((scroll.value - scroll.viewportSize / 2).coerceAtLeast(0)) } }, enabled = scroll.canScrollBackward) { Text("← 向左") }
                TextButton(onClick = { scope.launch { scroll.animateScrollTo((scroll.value + scroll.viewportSize / 2).coerceAtMost(scroll.maxValue)) } }, enabled = scroll.canScrollForward) { Text("向右 →") }
            }
        }
    }
}
