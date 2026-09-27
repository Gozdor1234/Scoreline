package com.nate.scoreline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    /** A game to open, set when the app is launched from a widget row. */
    private val pendingRoute = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Alerts.createChannel(this)
        if (Favorites.get(this).alertsEnabled) Alerts.schedule(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ScorelineTheme { App(pendingRoute) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val league = intent?.getStringExtra(ScoresWidget.EXTRA_LEAGUE) ?: return
        val event = intent.getStringExtra(ScoresWidget.EXTRA_EVENT) ?: return
        val l = runCatching { League.valueOf(league) }.getOrNull() ?: return
        pendingRoute.value = Route.GameDetail(l, event)
    }
}

/** Screens pushed on top of the tabs. A tiny back stack instead of a navigation library. */
sealed interface Route {
    data class GameDetail(val league: League, val eventId: String) : Route
    data class TeamPicker(val league: League) : Route
    data object DriverPicker : Route
}

private data class TabDef(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabDef("Scores", Icons.Filled.Home),
    TabDef("F1", Icons.Filled.DateRange),
    TabDef("Standings", Icons.AutoMirrored.Filled.List),
    TabDef("Settings", Icons.Filled.Settings),
)

@Composable
fun App(pendingRoute: MutableState<Route?>) {
    val stack = remember { mutableStateListOf<Route>() }
    // Opened from the widget: show that game on top of the Scores tab.
    val incoming = pendingRoute.value
    LaunchedEffect(incoming) {
        if (incoming != null) {
            stack.clear()
            stack.add(incoming)
            pendingRoute.value = null
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.isNotEmpty()) { back() }
    // Keeps tab state (league, week, filters) alive while a detail screen is on top.
    val holder = rememberSaveableStateHolder()

    when (val top = stack.lastOrNull()) {
        is Route.GameDetail -> GameDetailScreen(top.league, top.eventId, onBack = back)
        is Route.TeamPicker -> TeamPickerScreen(top.league, onBack = back)
        Route.DriverPicker -> DriverPickerScreen(onBack = back)
        null -> holder.SaveableStateProvider("tabs") {
          Scaffold(
            bottomBar = {
                val extra = LocalExtraColors.current
                val onBar = extra.onBar
                NavigationBar(containerColor = extra.bar ?: NavigationBarDefaults.containerColor) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = if (onBar == null) NavigationBarItemDefaults.colors()
                            else NavigationBarItemDefaults.colors(
                                unselectedIconColor = onBar.copy(alpha = 0.75f),
                                unselectedTextColor = onBar.copy(alpha = 0.75f),
                                selectedTextColor = onBar,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when (tab) {
                0 -> ScoresScreen(m, open)
                1 -> F1Screen(m)
                2 -> StandingsScreen(m)
                else -> SettingsScreen(m, open)
            }
          }
        }
    }
}
