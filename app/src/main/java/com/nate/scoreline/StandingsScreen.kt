package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val standingTabs = listOf("NFL", "College", "F1 Drivers", "F1 Teams")

@Composable
fun StandingsScreen(modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            standingTabs.forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
        }
        when (tab) {
            0 -> TeamStandings(League.NFL)
            1 -> TeamStandings(League.CFB)
            2 -> DriverStandings()
            else -> ConstructorStandings()
        }
    }
}

@Composable
private fun favTint(isFav: Boolean): Color =
    if (isFav) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

@Composable
private fun TeamStandings(league: League) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<StandingGroup>>(league, { 30 * 60_000L }) { Espn.standings(league) }
    LoadableContent(polled) { groups ->
        if (groups.isEmpty()) {
            Message("No standings published yet.")
            return@LoadableContent
        }
        LazyColumn {
            groups.forEach { grp ->
                item { SectionHeader(grp.title) }
                item { StandingLine("Team", grp.headers, header = true, logo = "") }
                items(grp.rows) { r ->
                    StandingLine(r.name, r.cols, logo = r.logo, highlight = fav.isFavTeam(league, r.teamId))
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun StandingLine(name: String, cols: List<String>, logo: String, header: Boolean = false, highlight: Boolean = false) {
    Column {
        Row(
            Modifier.fillMaxWidth().background(favTint(highlight)).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!header) { Logo(logo, 22.dp); Spacer(Modifier.width(8.dp)) }
            Text(
                name,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            cols.forEachIndexed { i, c ->
                Text(
                    c,
                    Modifier.width(if (i == 0) 58.dp else 44.dp),
                    textAlign = TextAlign.End,
                    style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    fontWeight = if (i == 0 && !header) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (!header) HorizontalDivider()
    }
}

@Composable
private fun DriverStandings() {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<F1DriverStanding>>("f1d", { 30 * 60_000L }) { F1.driverStandings() }
    LoadableContent(polled) { rows ->
        LazyColumn {
            item { SimpleRow("Pos", "Driver", "Team", "Wins", "Pts", header = true) }
            items(rows) { r ->
                SimpleRow(r.pos, r.name, r.team, r.wins, r.points, highlight = fav.isFavDriver(r.name))
            }
            item { SourceNote() }
        }
    }
}

@Composable
private fun ConstructorStandings() {
    val polled = rememberPolled<List<F1TeamStanding>>("f1c", { 30 * 60_000L }) { F1.teamStandings() }
    LoadableContent(polled) { rows ->
        LazyColumn {
            item { SimpleRow("Pos", "Team", "", "Wins", "Pts", header = true) }
            items(rows) { r -> SimpleRow(r.pos, r.team, "", r.wins, r.points) }
            item { SourceNote() }
        }
    }
}

@Composable
fun SimpleRow(pos: String, main: String, sub: String, a: String, b: String, header: Boolean = false, highlight: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column {
        Row(
            Modifier.fillMaxWidth().background(favTint(highlight)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pos, Modifier.width(36.dp), style = style, color = color, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f)) {
                Text(main, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank() && !header) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(a, Modifier.width(48.dp), textAlign = TextAlign.End, style = style, color = color)
            Text(b, Modifier.width(52.dp), textAlign = TextAlign.End, style = style, color = color,
                fontWeight = if (header) FontWeight.Normal else FontWeight.Bold)
        }
        if (!header) HorizontalDivider()
    }
}

@Composable
fun SourceNote() {
    Text(
        "F1 standings and results: Jolpica F1 API. Updated after each session.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
