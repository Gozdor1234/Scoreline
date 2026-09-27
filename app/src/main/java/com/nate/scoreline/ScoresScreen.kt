@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

fun shiftWeek(w: WeekInfo, delta: Int, league: League): WeekInfo {
    var type = w.seasonType
    var n = w.week + delta
    when {
        type == 2 && n > league.regularWeeks -> { type = 3; n = 1 }
        type == 3 && n < 1 -> { type = 2; n = league.regularWeeks }
        n < 1 -> n = 1
    }
    if (type == 3 && n > 5) n = 5
    return WeekInfo(type, n)
}

@Composable
fun ScoresScreen(modifier: Modifier, open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    var league by rememberSaveable { mutableStateOf(League.NFL) }
    // Saved as seasonType*100 + week (0 = current) so it survives opening a game and coming back.
    var weekCode by rememberSaveable(league) { mutableIntStateOf(0) }
    val week: WeekInfo? = if (weekCode == 0) null else WeekInfo(weekCode / 100, weekCode % 100)
    val setWeek: (WeekInfo?) -> Unit = { v -> weekCode = if (v == null) 0 else v.seasonType * 100 + v.week }
    var mineOnly by rememberSaveable { mutableStateOf(false) }

    val polled = rememberPolled<Scoreboard>(
        key = league to week,
        intervalMs = { sb -> if (sb?.games?.any { it.isLive } == true) LIVE_MS else IDLE_MS },
    ) { Espn.scoreboard(league, week) }

    val shownWeek = week ?: polled.state.data?.week

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = league.ordinal) {
            League.entries.forEach { l ->
                Tab(selected = l == league, onClick = { league = l }, text = { Text(l.label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = shownWeek != null, onClick = { shownWeek?.let { setWeek(shiftWeek(it, -1, league)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
            }
            TextButton(onClick = { setWeek(null) }) {
                Text(shownWeek?.label ?: "This week", fontWeight = FontWeight.SemiBold)
            }
            IconButton(enabled = shownWeek != null, onClick = { shownWeek?.let { setWeek(shiftWeek(it, 1, league)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = mineOnly,
                onClick = { mineOnly = !mineOnly },
                label = { Text("My teams") },
                leadingIcon = if (mineOnly) { { Icon(Icons.Filled.Favorite, null) } } else null,
            )
            IconButton(onClick = polled.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }

        LoadableContent(polled) { sb ->
            val favIds = fav.favTeamIds(league)
            fun isFav(g: Game) = g.home.id in favIds || g.away.id in favIds
            val stateOrder = mapOf("in" to 0, "pre" to 1, "post" to 2)
            val games = sb.games
                .filter { !mineOnly || isFav(it) }
                .sortedWith(compareBy<Game>({ if (isFav(it)) 0 else 1 }, { stateOrder[it.state] ?: 3 }, { it.date }))
            if (games.isEmpty()) {
                Message(if (mineOnly) "None of your teams play this week.\nAdd teams under Settings." else "No games this week.")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(games, key = { it.id }) { g ->
                        GameCard(g, isFav(g)) { open(Route.GameDetail(league, g.id)) }
                    }
                    item {
                        Text(
                            agoText(polled.state.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GameCard(g: Game, favorite: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (favorite) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            TeamLine(g.away, g, hasBall = g.isLive && g.possessionTeamId == g.away.id)
            TeamLine(g.home, g, hasBall = g.isLive && g.possessionTeamId == g.home.id)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (g.state) {
                    "in" -> {
                        LiveBadge()
                        Text("  ${g.detail}", style = MaterialTheme.typography.labelMedium)
                        if (g.downDistance.isNotBlank()) {
                            Text("  •  ${g.downDistance}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    "pre" -> Text(
                        listOf(formatLocal(g.date).ifEmpty { g.detail }, g.broadcast).filter { it.isNotBlank() }.joinToString("  •  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(g.detail, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TeamLine(t: TeamSide, g: Game, hasBall: Boolean) {
    val emphasize = g.state == "post" && t.winner
    val dim = g.state == "post" && !t.winner
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(t.logo)
        Spacer(Modifier.width(10.dp))
        if (t.rank != null) {
            Text("${t.rank} ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            t.name.ifEmpty { t.abbr },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasBall) Text("  ●", color = LiveRed, style = MaterialTheme.typography.labelSmall)
        if (t.record.isNotBlank()) {
            Text("  ${t.record}  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (g.state != "pre") {
            Text(
                t.score,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (emphasize || g.isLive) FontWeight.Bold else FontWeight.Normal,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
