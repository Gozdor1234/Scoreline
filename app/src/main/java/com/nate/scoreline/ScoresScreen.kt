@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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

    // College view: "top25" and "fbs" both load all FBS games (Top 25 filters by AP rank on the phone);
    // a conference view asks ESPN for just that conference.
    val cfb = league == League.CFB
    val view = if (cfb) fav.cfbView else "fbs"
    val group: String? = when {
        !cfb -> null
        view == "top25" || view == "fbs" -> Conferences.FBS
        else -> view
    }
    val pinId = if (cfb) fav.pinnedCfb else null
    val pinFetch = if (pinId != null && group != pinId) pinId else null

    val polled = rememberPolled<ScoresData>(
        key = listOf(league, week, group, pinFetch),
        intervalMs = { d -> if (d?.board?.games?.any { it.isLive } == true) LIVE_MS else IDLE_MS },
    ) {
        supervisorScope {
            val main = async { Espn.scoreboard(league, week, group) }
            // The pinned conference is a second, optional request; if it fails the main list still shows.
            val pinned = pinFetch?.let { id -> async { runCatching { Espn.scoreboard(league, week, id) }.getOrNull() } }
            ScoresData(main.await(), pinned?.await()?.games?.map { it.id }?.toSet() ?: emptySet())
        }
    }

    val shownWeek = week ?: polled.state.data?.board?.week

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
        if (cfb) CollegeViewRow(fav, view)

        // Pinch on the list to resize cards. Two-finger gestures are handled here; one finger still scrolls.
        var zoom by remember { mutableFloatStateOf(fav.scoresZoom) }
        var pinching by remember { mutableStateOf(false) }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var changed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            pinching = true
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                zoom = (zoom * z).coerceIn(Favorites.ZOOM_MIN, Favorites.ZOOM_MAX)
                                changed = true
                            }
                            // Consume so the list doesn't also scroll during the pinch.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    pinching = false
                    if (changed) fav.updateScoresZoom(zoom)
                }
            },
        ) {
        LoadableContent(polled) { data ->
            val sb = data.board
            val favIds = fav.favTeamIds(league)
            fun isFav(g: Game) = g.home.id in favIds || g.away.id in favIds
            val stateOrder = mapOf("in" to 0, "pre" to 1, "post" to 2)
            fun arrange(list: List<Game>) = list
                .filter { !mineOnly || isFav(it) }
                .sortedWith(compareBy<Game>({ if (isFav(it)) 0 else 1 }, { stateOrder[it.state] ?: 3 }, { it.date }))
            val ranked: (Game) -> Boolean = { it.away.rank != null || it.home.rank != null }
            val pinnedGames = arrange(sb.games.filter { it.id in data.pinnedIds })
            val games = arrange(sb.games.filter { it.id !in data.pinnedIds && (view != "top25" || ranked(it)) })
            if (games.isEmpty() && pinnedGames.isEmpty()) {
                Message(
                    when {
                        mineOnly -> "None of your teams play this week.\nAdd teams under Settings."
                        view == "top25" -> "No ranked teams play this week."
                        else -> "No games this week."
                    },
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp * zoom),
                ) {
                    if (pinnedGames.isNotEmpty()) {
                        item { ListLabel("★ Pinned: ${Conferences.name(pinId)}") }
                        items(pinnedGames, key = { it.id }) { g ->
                            Zoomed(zoom) { GameCard(g, isFav(g)) { open(Route.GameDetail(league, g.id)) } }
                        }
                        if (games.isNotEmpty()) item { ListLabel(if (view == "top25") "Top 25" else "All FBS") }
                    }
                    items(games, key = { it.id }) { g ->
                        Zoomed(zoom) { GameCard(g, isFav(g)) { open(Route.GameDetail(league, g.id)) } }
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
            // Size readout while pinching.
            if (pinching) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                ) {
                    Text(
                        "${Math.round(zoom * 100)}%",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Draws content at a different size by scaling dp and sp together, so layout (not just pixels) shrinks or grows. */
@Composable
private fun Zoomed(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) { content() }
}

data class ScoresData(val board: Scoreboard, val pinnedIds: Set<String>)

@Composable
private fun ListLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** Top 25 / All FBS / conference chooser. The star next to each conference pins it to the top. */
@Composable
private fun CollegeViewRow(fav: Favorites, view: String) {
    var menuOpen by remember { mutableStateOf(false) }
    val confView = view != "top25" && view != "fbs"
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = view == "top25", onClick = { fav.updateCfbView("top25") }, label = { Text("Top 25") })
        FilterChip(selected = view == "fbs", onClick = { fav.updateCfbView("fbs") }, label = { Text("All FBS") })
        Box {
            FilterChip(
                selected = confView,
                onClick = { menuOpen = true },
                label = { Text(if (confView) Conferences.name(view) else "Conference") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    "Tap a conference to view it. Tap its star to pin it to the top.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(220.dp),
                )
                Conferences.all.forEach { (id, name) ->
                    val pinned = fav.pinnedCfb == id
                    DropdownMenuItem(
                        text = { Text(name, fontWeight = if (view == id) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { fav.updateCfbView(id); menuOpen = false },
                        trailingIcon = {
                            IconButton(onClick = { fav.togglePin(League.CFB, id) }) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = if (pinned) "Unpin $name" else "Pin $name",
                                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        },
                    )
                }
            }
        }
        if (confView) {
            val pinned = fav.pinnedCfb == view
            IconButton(onClick = { fav.togglePin(League.CFB, view) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (pinned) "Unpin conference" else "Pin conference to top",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
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
        if (hasBall) {
            Spacer(Modifier.width(6.dp))
            Image(
                painter = painterResource(R.drawable.ic_football),
                contentDescription = "Has the ball",
                modifier = Modifier.size(13.dp),
            )
        }
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
