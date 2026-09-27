package com.nate.scoreline

import org.json.JSONObject
import java.text.Normalizer

/**
 * F1 circuit outlines and facts from the bundled f1-circuits GeoJSON
 * (github.com/bacinger/f1-circuits, MIT license; see assets/f1-circuits-LICENSE.md).
 */
data class Circuit(
    val id: String,
    val name: String,
    val location: String,
    val opened: Int,
    val firstGp: Int,
    val lengthM: Int,
    val altitudeM: Int,
    /** (longitude, latitude) along the track */
    val points: List<Pair<Double, Double>>,
)

object Circuits {
    fun parse(root: JSONObject): List<Circuit> = root.arr("features").objects().mapNotNull { f ->
        val p = f.obj("properties") ?: return@mapNotNull null
        val coords = f.obj("geometry")?.arr("coordinates")
        val pts = (0 until (coords?.length() ?: 0)).mapNotNull { i ->
            val c = coords?.optJSONArray(i) ?: return@mapNotNull null
            val lon = c.optDouble(0, Double.NaN)
            val lat = c.optDouble(1, Double.NaN)
            if (lon.isNaN() || lat.isNaN()) null else lon to lat
        }
        Circuit(
            id = p.str("id"),
            name = p.str("Name"),
            location = p.str("Location"),
            opened = p.optInt("opened", 0),
            firstGp = p.optInt("firstgp", 0),
            lengthM = p.optInt("length", 0),
            altitudeM = p.optInt("altitude", 0),
            points = pts,
        )
    }.filter { it.points.size >= 3 }

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Match ESPN's circuit name / city to a dataset entry: exact name, then name containment, then city. */
    fun find(all: List<Circuit>, circuitName: String, city: String): Circuit? {
        val n = norm(circuitName)
        val c = norm(city)
        return all.firstOrNull { n.isNotEmpty() && norm(it.name) == n }
            ?: all.firstOrNull { n.length >= 4 && (norm(it.name).contains(n) || n.contains(norm(it.name))) }
            ?: all.firstOrNull { c.isNotEmpty() && norm(it.location) == c }
    }

    /**
     * Projects the outline to x,y in [0,1] x [0,1] (north up), keeping the real aspect ratio.
     * Returns the points and the drawing's width/height ratio.
     */
    fun project(points: List<Pair<Double, Double>>): Pair<List<Pair<Float, Float>>, Float> {
        val lat0 = Math.toRadians(points.map { it.second }.average())
        val xy = points.map { (lon, lat) -> lon * Math.cos(lat0) to -lat }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val w = (maxX - minX).coerceAtLeast(1e-9)
        val h = (maxY - minY).coerceAtLeast(1e-9)
        val span = maxOf(w, h)
        val norm = xy.map { (x, y) -> ((x - minX) / span).toFloat() to ((y - minY) / span).toFloat() }
        return norm to (w / h).toFloat()
    }
}
