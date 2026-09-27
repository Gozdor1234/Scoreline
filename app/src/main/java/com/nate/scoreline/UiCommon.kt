package com.nate.scoreline

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScorelineTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

val LiveRed = Color(0xFFE53935)

class Loadable<T>(val data: T?, val error: String?, val loading: Boolean, val updatedAt: Long)
class Polled<T>(val state: Loadable<T>, val refresh: () -> Unit)

const val LIVE_MS = 30_000L
const val IDLE_MS = 5 * 60_000L

/**
 * Fetches immediately, then again every intervalMs(data). Runs only while the screen
 * is at least STARTED, so nothing polls in the background; returning to the app refreshes.
 */
@Composable
fun <T> rememberPolled(key: Any?, intervalMs: (T?) -> Long, fetch: suspend () -> T): Polled<T> {
    var state by remember(key) { mutableStateOf(Loadable<T>(null, null, true, 0L)) }
    var tick by remember(key) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentFetch by rememberUpdatedState(fetch)
    val currentInterval by rememberUpdatedState(intervalMs)
    LaunchedEffect(key, tick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state = Loadable(state.data, null, true, state.updatedAt)
                state = try {
                    Loadable(currentFetch(), null, false, System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Loadable(state.data, e.message ?: e.javaClass.simpleName, false, state.updatedAt)
                }
                delay(currentInterval(state.data))
            }
        }
    }
    return Polled(state) { tick++ }
}

/** Standard wrapper: spinner on first load, error with retry, thin bar while refreshing. */
@Composable
fun <T> LoadableContent(polled: Polled<T>, emptyText: String = "Nothing here right now.", content: @Composable (T) -> Unit) {
    val s = polled.state
    Column(Modifier.fillMaxSize()) {
        if (s.loading && s.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.error != null && s.data != null) {
            Text(
                "Couldn't refresh (${s.error}). Showing last data.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            s.data != null -> content(s.data)
            s.error != null -> Message("Couldn't load data.\n${s.error}", action = "Retry", onAction = polled.refresh)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) { Text(action) }
    }
}

@Composable
fun Logo(url: String, size: Dp = 28.dp) {
    if (url.isBlank()) Box(Modifier.size(size)) else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size))
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun LiveBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).padding(0.dp)) {
            Surface(color = LiveRed, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxSize()) {}
        }
        Text(" LIVE", color = LiveRed, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private val localFmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

/** ESPN times look like 2026-09-27T17:00Z; show them in the phone's time zone. */
fun formatLocal(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(localFmt)
} catch (e: Exception) {
    ""
}

fun agoText(updatedAt: Long): String {
    if (updatedAt == 0L) return ""
    val s = (System.currentTimeMillis() - updatedAt) / 1000
    return if (s < 60) "Updated just now" else "Updated ${s / 60} min ago"
}
