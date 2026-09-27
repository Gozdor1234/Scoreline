@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(modifier: Modifier, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    val fav = Favorites.get(ctx)
    var permDenied by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            fav.setAlerts(true); Alerts.schedule(ctx); permDenied = false
        } else {
            permDenied = true
        }
    }

    fun setAlerts(on: Boolean) {
        if (!on) { fav.setAlerts(false); Alerts.cancel(ctx); return }
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(ctx)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            fav.setAlerts(true); Alerts.schedule(ctx)
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader("Favorite teams") }
        val teams = fav.teams.sorted()
        if (teams.isEmpty()) item { Hint("No favorites yet. Add some below, or tap the heart on any game.") }
        items(teams) { key ->
            val league = League.entries.firstOrNull { key.startsWith("${it.name}:") } ?: League.NFL
            val name = fav.teamNames[key] ?: key
            RemovableRow("$name  ·  ${league.label}") { fav.toggleTeam(league, key.substringAfter(':'), name) }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(Route.TeamPicker(League.NFL)) }) { Icon(Icons.Filled.Add, null); Text(" NFL") }
                OutlinedButton(onClick = { open(Route.TeamPicker(League.CFB)) }) { Icon(Icons.Filled.Add, null); Text(" College") }
            }
        }

        item { SectionHeader("Favorite F1 drivers") }
        val drivers = fav.driverNames.sorted()
        if (drivers.isEmpty()) item { Hint("None yet.") }
        items(drivers) { d -> RemovableRow(d) { fav.toggleDriver(d) } }
        item {
            OutlinedButton(onClick = { open(Route.DriverPicker) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Icon(Icons.Filled.Add, null); Text(" F1 driver")
            }
        }

        item { SectionHeader("Alerts") }
        item { SwitchRow("Notifications for my teams", fav.alertsEnabled) { setAlerts(it) } }
        item { SwitchRow("Include score changes (not just kickoff and final)", fav.scoreAlerts, enabled = fav.alertsEnabled) { fav.setScoreAlerts(it) } }
        item { SwitchRow("F1 qualifying, sprint and race results", fav.f1Alerts, enabled = fav.alertsEnabled) { fav.setF1Alerts(it) } }
        if (permDenied) item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Hint("Notification permission was denied. Enable it in Android settings to get alerts.")
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open app settings") }
            }
        }
        item {
            Hint(
                "Alerts check about every 15 minutes (Android's minimum for background work) and can be delayed " +
                    "further when the phone is idle. For play-by-play, keep the app open: live screens refresh every 20 to 30 seconds. " +
                    "If alerts seem to stop, set Scoreline's battery usage to Unrestricted in Android settings.",
            )
        }

        item { SectionHeader("About") }
        item {
            Hint(
                "Scores and stats use ESPN's public but unofficial endpoints, which can change without notice. " +
                    "F1 standings and results use the Jolpica F1 API. Personal use only.",
            )
        }
        item { Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Favorite, null, tint = LiveRed)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PickerScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad -> content(Modifier.padding(pad)) }
}

@Composable
private fun PickRow(name: String, sub: String, logo: String, selected: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (logo.isNotBlank()) { Logo(logo, 28.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (selected) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (selected) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@Composable
fun TeamPickerScreen(league: League, onBack: () -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    var query by rememberSaveable { mutableStateOf("") }
    val polled = rememberPolled<List<TeamRef>>("teams-${league.name}", { 24 * 60 * 60_000L }) { Espn.teams(league) }
    PickerScaffold("Add ${league.label} teams", onBack) { m ->
        Column(m.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                LoadableContent(polled) { teams ->
                    val q = query.trim()
                    val shown = if (q.isEmpty()) teams else teams.filter {
                        it.name.contains(q, ignoreCase = true) || it.abbr.equals(q, ignoreCase = true)
                    }
                    LazyColumn {
                        items(shown, key = { it.id }) { t ->
                            PickRow(t.name, t.abbr, t.logo, fav.isFavTeam(league, t.id)) { fav.toggleTeam(league, t.id, t.name) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DriverPickerScreen(onBack: () -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<F1DriverStanding>>("drivers", { 60 * 60_000L }) { F1.driverStandings() }
    PickerScaffold("Add F1 drivers", onBack) { m ->
        Box(m.fillMaxSize()) {
            LoadableContent(polled, emptyText = "No drivers listed yet.") { drivers ->
                LazyColumn {
                    items(drivers, key = { it.name }) { d ->
                        PickRow(d.name, d.team, "", fav.isFavDriver(d.name)) { fav.toggleDriver(d.name) }
                    }
                }
            }
        }
    }
}
