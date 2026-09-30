package com.nate.scoreline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Compact lines table:
 *   FanDuel     SPREAD        ML      TOTAL
 *   DET     -5.5 (-108)    -240   O 47.5 (-110)
 *   CAR     +5.5 (-112)    +196   U 47.5 (-110)
 */
@Composable
fun OddsTable(odds: GameOdds, awayAbbr: String, homeAbbr: String, modifier: Modifier = Modifier, divider: Boolean = true) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    fun withPrice(line: String, price: String) = if (line.isBlank()) "–" else if (price.isBlank()) line else "$line ($price)"
    val modern = LocalModern.current
    Column(if (modern) modifier.modernWell().padding(horizontal = 12.dp, vertical = 8.dp) else modifier) {
        if (divider && !modern) HorizontalDivider(Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(odds.source, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = sub, maxLines = 1)
            Text("SPREAD", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = sub, textAlign = TextAlign.End)
            Text("ML", Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = sub, textAlign = TextAlign.End)
            Text("TOTAL", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = sub, textAlign = TextAlign.End)
        }
        listOf(
            Triple(awayAbbr, withPrice(odds.awaySpread, odds.awaySpreadOdds), odds.awayMl) to
                (if (odds.total.isBlank()) "–" else withPrice("O ${odds.total}", odds.overOdds)),
            Triple(homeAbbr, withPrice(odds.homeSpread, odds.homeSpreadOdds), odds.homeMl) to
                (if (odds.total.isBlank()) "–" else withPrice("U ${odds.total}", odds.underOdds)),
        ).forEach { (t, total) ->
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(t.first, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(t.second, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, maxLines = 1)
                Text(t.third.ifBlank { "–" }, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, maxLines = 1)
                Text(total, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}
