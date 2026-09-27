package com.nate.scoreline

/**
 * Pure color math on ARGB ints (0xAARRGGBB). No Android types, so it's unit-testable.
 * Contrast and luminance follow the WCAG 2.x definitions.
 */
object ColorMath {
    fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** h in [0,360), s and v in [0,1]. */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1 - Math.abs((hh / 60f) % 2 - 1))
        val m = vv - c
        val (r1, g1, b1) = when {
            hh < 60 -> Triple(c, x, 0f)
            hh < 120 -> Triple(x, c, 0f)
            hh < 180 -> Triple(0f, c, x)
            hh < 240 -> Triple(0f, x, c)
            hh < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(Math.round((r1 + m) * 255), Math.round((g1 + m) * 255), Math.round((b1 + m) * 255))
    }

    /** Returns [h, s, v]. */
    fun argbToHsv(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (max == 0f) 0f else d / max, max)
    }

    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    fun luminance(c: Int): Double = 0.2126 * channel(red(c)) + 0.7152 * channel(green(c)) + 0.0722 * channel(blue(c))

    /** WCAG contrast ratio, 1.0 (none) to 21.0 (black on white). 4.5 is the usual bar for body text. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun isDark(c: Int) = luminance(c) < 0.18

    /** Black or white, whichever reads better on c. */
    fun onColorFor(c: Int): Int {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (contrast(c, black) >= contrast(c, white)) black else white
    }

    /** Linear blend: t = 0 gives a, t = 1 gives b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = Math.round(x + (y - x) * tt)
        return argb(ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }

    /** Nudge toward white on dark colors, toward black on light ones (for card layers over a background). */
    fun elevate(base: Int, amount: Float): Int =
        if (isDark(base)) mix(base, 0xFFFFFFFF.toInt(), amount) else mix(base, 0xFF000000.toInt(), amount * 0.6f)

    fun toHex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    /** Accepts "#RRGGBB", "RRGGBB", "#RGB". Returns null if invalid. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000.toInt()) or t.toInt(16)
    }
}

/** App areas the user can recolor. Keys are what's stored in settings; don't rename them. */
enum class ColorSlot(val key: String, val label: String, val hint: String) {
    Background("bg", "Background", "Main screen color"),
    Cards("cards", "Cards & panels", "Game cards, tables, menus"),
    Accent("accent", "Accent", "Buttons, selected tabs, stars, headings"),
    Highlight("highlight", "Favorites highlight", "Favorite teams' cards, pinned rows, current drive"),
    Text("text", "Main text", "Team names, scores, body text"),
    SubText("subtext", "Secondary text", "Times, records, labels"),
    Lines("lines", "Divider lines", "Separators and outlines"),
    Bars("bars", "Top & bottom bars", "Navigation bar and screen headers"),
    Live("live", "Live & favorite hearts", "LIVE badge, possession dot, hearts"),
}

/** One check the settings screen runs to warn about hard-to-read combinations. */
data class ContrastIssue(val message: String, val ratio: Double)

object ContrastCheck {
    /** Pairs are (description, foreground, background, minimum ratio). */
    fun issues(pairs: List<Triple<String, Pair<Int, Int>, Double>>): List<ContrastIssue> =
        pairs.mapNotNull { (desc, fb, min) ->
            val r = ColorMath.contrast(fb.first, fb.second)
            if (r < min) ContrastIssue(desc, r) else null
        }
}
