package com.nate.scoreline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * The official full-course map for a PGA TOUR event. The PGA TOUR media site
 * (pgatourmedia.pgatourhq.com) posts a "Course Map" PDF on each event's page; the app finds
 * that link, renders the PDF's first page to an image on the phone, and caches the image,
 * so each tournament's map downloads once.
 */
object CourseMaps {
    private const val BASE = "https://pgatourmedia.pgatourhq.com"

    /** Page slugs the media site uses: the event name, lowercase, letters and digits only. */
    fun slugs(eventName: String): List<String> {
        fun norm(s: String, amp: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace("&", amp).replace(Regex("[^a-z0-9]"), "")
        val base = eventName.substringBefore(" presented by ", eventName).substringBefore(" pres. by ")
        val noThe = base.removePrefix("The ").removePrefix("THE ")
        // "AT&T" could be "att" or "atandt" on the site, so try both.
        return listOf(norm(base, ""), norm(base, "and"), norm(noThe, ""), norm(eventName, ""))
            .filter { it.isNotEmpty() }.distinct()
    }

    /** First "Course Map" PDF link in an event page's HTML, absolute and space-safe. */
    fun findMapLink(html: String): String? {
        val m = Regex("""href\s*=\s*["']([^"']*course[ _%20-]*map[^"']*\.pdf)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val href = m.groupValues[1].replace("&amp;", "&")
        val abs = if (href.startsWith("http")) href else BASE + (if (href.startsWith("/")) "" else "/") + href
        return abs.replace(" ", "%20")
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Cached map image for this event, fetching it if needed. Null if none is posted. */
    suspend fun load(ctx: Context, eventName: String, year: Int): Bitmap? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "coursemaps").apply { mkdirs() }
        val key = slugs(eventName).firstOrNull() ?: return@withContext null
        val png = File(dir, "$key-$year.png")
        val none = File(dir, "$key-$year.none")
        if (png.exists()) return@withContext BitmapFactory.decodeFile(png.path)
        // Remember "no map" for a day so the page isn't re-checked on every open.
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 24 * 3600_000L) return@withContext null
        if (inFlight[key] == true) return@withContext null
        inFlight[key] = true
        try {
            // This year's page first, then last year's (maps are often posted close to the event).
            for (y in listOf(year, year - 1)) {
                for (slug in slugs(eventName)) {
                    val html = runCatching { getText("$BASE/tours/$y/pgatour/$slug") }.getOrNull() ?: continue
                    val link = findMapLink(html) ?: continue
                    val bmp = runCatching { renderPdf(ctx, link) }.getOrNull() ?: continue
                    png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    none.delete()
                    return@withContext bmp
                }
            }
            none.writeText("none")
            null
        } finally {
            inFlight.remove(key)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Scoreology/1.0 (Android app)")
        }

    private fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the PDF and draws its first page on white, about 2000 px on the long side. */
    private fun renderPdf(ctx: Context, url: String): Bitmap {
        val tmp = File.createTempFile("coursemap", ".pdf", ctx.cacheDir)
        try {
            val c = open(url)
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                c.disconnect()
            }
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    r.openPage(0).use { page ->
                        val longSide = maxOf(page.width, page.height).coerceAtLeast(1)
                        val scale = 2000f / longSide
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }
}

/** Official course map on the tournament Info tab. Tap to open it full screen with pinch-zoom. */
@Composable
fun CourseMapCard(eventName: String, startIso: String) {
    val ctx = LocalContext.current
    val year = remember(startIso) { startIso.take(4).toIntOrNull() ?: java.time.LocalDate.now().year }
    val state by produceState<Pair<Boolean, Bitmap?>>(true to null, eventName, year) {
        value = false to runCatching { CourseMaps.load(ctx, eventName, year) }.getOrNull()
    }
    val (loading, bmp) = state
    var full by remember { mutableStateOf(false) }
    if (bmp == null) {
        if (loading) {
            Text(
                "Loading course map…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        return
    }
    val image = remember(bmp) { bmp.asImageBitmap() }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Image(
                image,
                contentDescription = "Course map",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { full = true },
            )
            Text(
                "Official course map · PGA TOUR  ·  Tap to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
    if (full) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    },
            ) {
                Image(
                    image,
                    contentDescription = "Course map",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    },
                )
                IconButton(onClick = { full = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    "Pinch to zoom",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}
