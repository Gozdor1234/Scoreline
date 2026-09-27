package com.nate.scoreline

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
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Alerts.createChannel(this)
        if (Favorites.get(this).alertsEnabled) Alerts.schedule(this)
        setContent { ScorelineTheme { App() } }
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
fun App() {
    val stack = remember { mutableStateListOf<Route>() }
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
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
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
