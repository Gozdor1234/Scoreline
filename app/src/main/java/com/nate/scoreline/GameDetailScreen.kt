@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun periodLabel(p: Int) = when {
    p in 1..4 -> "Q$p"
    p == 5 -> "OT"
    p > 5 -> "${p - 4}OT"
    else -> ""
}

@Composable
fun GameDetailScreen(league: League, eventId: String, onBack: () -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<GameDetail>(
        key = league to eventId,
        intervalMs = { d -> if (d?.game?.isLive == true) 20_000L else IDLE_MS },
    ) { Espn.summary(league, eventId) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var homeSide by rememberSaveable { mutableIntStateOf(0) }
    var collapsed by remember { mutableStateOf(setOf<String>()) } // drive ids the user has folded up

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(polled.state.data?.game?.matchup ?: "Game") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = polled.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            LoadableContent(polled) { d ->
                val g = d.game
                LazyColumn {
                    if (g != null) {
                        item { GameHeader(g, fav) }
                        if (g.away.linescores.isNotEmpty() || g.home.linescores.isNotEmpty()) item { Linescore(g) }
                    }
                    item {
                        ScrollableTabRow(selectedTabIndex = section, edgePadding = 8.dp, modifier = Modifier.padding(top = 8.dp)) {
                            listOf("Team stats", "Players", "Scoring", "Play-by-play").forEachIndexed { i, s ->
                                Tab(selected = section == i, onClick = { section = i }, text = { Text(s) })
                            }
                        }
                    }
                    when (section) {
                        0 -> if (d.teamStats.isEmpty()) {
                            item { EmptyNote("Team stats appear once the game starts.") }
                        } else {
                            item {
                                StatRow(g?.away?.abbr ?: "Away", "", g?.home?.abbr ?: "Home", bold = true)
                            }
                            items(d.teamStats) { s -> StatRow(s.away, s.label, s.home) }
                        }

                        1 -> {
                            val abbrs = listOfNotNull(g?.away?.abbr, g?.home?.abbr)
                            val chosen = abbrs.getOrNull(homeSide) ?: ""
                            val tables = d.players.filter { it.teamAbbr == chosen || chosen.isEmpty() }
                            item {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    abbrs.forEachIndexed { i, a ->
                                        FilterChip(selected = homeSide == i, onClick = { homeSide = i }, label = { Text(a) })
                                    }
                                }
                            }
                            if (tables.isEmpty()) item { EmptyNote("Player stats appear once the game starts.") }
                            items(tables) { t -> PlayerTableCard(t) }
                        }

                        2 -> if (d.scoring.isEmpty()) {
                            item { EmptyNote("No scoring plays yet.") }
                        } else {
                            items(d.scoring) { p -> ScoringRow(p, g) }
                        }

                        else -> if (d.drives.isEmpty()) {
                            item { EmptyNote("Play-by-play appears once the game starts.") }
                        } else {
                            d.drives.forEach { dr ->
                                item {
                                    DriveHeader(dr, collapsed = dr.id in collapsed) {
                                        collapsed = if (dr.id in collapsed) collapsed - dr.id else collapsed + dr.id
                                    }
                                }
                                if (dr.id !in collapsed) {
                                    items(dr.plays) { p -> PlayRow(p, g) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.padding(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun GameHeader(g: Game, fav: Favorites) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderTeam(g.away, g.league, fav, Modifier.weight(1f))
        Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (g.state != "pre") {
                Text("${g.away.score}  -  ${g.home.score}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            if (g.isLive) LiveBadge()
            Text(
                if (g.state == "pre") formatLocal(g.date).ifEmpty { g.detail } else g.detail,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
            if (g.isLive && g.downDistance.isNotBlank()) {
                Text(g.downDistance, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (g.broadcast.isNotBlank()) {
                Text(g.broadcast, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HeaderTeam(g.home, g.league, fav, Modifier.weight(1f))
    }
}

@Composable
private fun HeaderTeam(t: TeamSide, league: League, fav: Favorites, modifier: Modifier) {
    val isFav = fav.isFavTeam(league, t.id)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(t.logo, 52.dp)
        Text(t.abbr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (t.record.isNotBlank()) Text(t.record, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = { fav.toggleTeam(league, t.id, t.name) }) {
            Icon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFav) "Remove favorite" else "Add favorite",
                tint = if (isFav) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Linescore(g: Game) {
    val n = maxOf(g.away.linescores.size, g.home.linescores.size)
    val heads = (1..n).map { periodLabel(it) } + "T"
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            LineRow("", heads, header = true)
            LineRow(g.away.abbr, g.away.linescores + g.away.score)
            LineRow(g.home.abbr, g.home.linescores + g.home.score)
        }
    }
}

@Composable
private fun LineRow(label: String, cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(56.dp), fontWeight = FontWeight.SemiBold)
        cells.forEachIndexed { i, c ->
            Text(
                c,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (i == cells.lastIndex && !header) FontWeight.Bold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StatRow(away: String, label: String, home: String, bold: Boolean = false) {
    val w = if (bold) FontWeight.Bold else FontWeight.Normal
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(away, Modifier.weight(1f), fontWeight = w)
            Text(label, Modifier.weight(2f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(home, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = w)
        }
        HorizontalDivider()
    }
}

private val NAME_W: Dp = 150.dp
private val CELL_W: Dp = 58.dp

@Composable
private fun PlayerTableCard(t: PlayerTable) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            // Wide categories (passing has 7 columns) scroll sideways as one block, so columns stay aligned.
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                TableRow("", t.labels, header = true)
                t.rows.forEach { (name, stats) -> TableRow(name, stats) }
                if (t.totals.any { it.isNotBlank() }) TableRow("Team", t.totals, bold = true)
            }
        }
    }
}

@Composable
private fun TableRow(name: String, cells: List<String>, header: Boolean = false, bold: Boolean = false) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            name,
            Modifier.width(NAME_W),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        cells.forEach { c ->
            Text(
                c,
                Modifier.width(CELL_W),
                textAlign = TextAlign.End,
                style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ScoringRow(p: ScoringPlay, g: Game?) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(64.dp)) {
                Text(periodLabel(p.period), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(p.clock, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(p.team, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(p.text, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${g?.away?.abbr ?: ""} ${p.awayScore}\n${g?.home?.abbr ?: ""} ${p.homeScore}",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun DriveHeader(dr: Drive, collapsed: Boolean, onToggle: () -> Unit) {
    val highlight = dr.inProgress || dr.isScore
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onToggle),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(dr.logo, 24.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dr.team, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val result = if (dr.inProgress) "Current drive" else dr.result
                    if (result.isNotBlank()) {
                        Text(
                            "  ·  $result",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (dr.isScore) FontWeight.Bold else FontWeight.Normal,
                            color = if (dr.inProgress) LiveRed else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (dr.summary.isNotBlank()) {
                    Text(dr.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if (collapsed) "Show" else "Hide", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PlayRow(p: Play, g: Game?) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(64.dp)) {
                Text(periodLabel(p.period), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(p.clock, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                if (p.downDistance.isNotBlank()) {
                    Text(p.downDistance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    p.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (p.scoring) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (p.scoring) {
                Text(
                    "${g?.away?.abbr ?: ""} ${p.awayScore}\n${g?.home?.abbr ?: ""} ${p.homeScore}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        HorizontalDivider()
    }
}
