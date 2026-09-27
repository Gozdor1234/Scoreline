package com.nate.scoreline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Favorites and settings, persisted in SharedPreferences and mirrored into
 * Compose state so the UI recomposes when they change.
 * Team keys look like "NFL:8" / "CFB:130"; driver keys are F1.driverKey(name).
 */
class Favorites private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var teams by mutableStateOf(prefs.getStringSet(KEY_TEAMS, emptySet())!!.toSet())
        private set
    var teamNames by mutableStateOf(readNames())
        private set
    var drivers by mutableStateOf(prefs.getStringSet(KEY_DRIVERS, emptySet())!!.toSet())
        private set
    var driverNames by mutableStateOf(prefs.getStringSet(KEY_DRIVER_NAMES, emptySet())!!.toSet())
        private set
    var alertsEnabled by mutableStateOf(prefs.getBoolean(KEY_ALERTS, false))
        private set
    var scoreAlerts by mutableStateOf(prefs.getBoolean(KEY_SCORE_ALERTS, true))
        private set
    var f1Alerts by mutableStateOf(prefs.getBoolean(KEY_F1_ALERTS, true))
        private set

    /** "system", "light", "dark" or "amoled" */
    var themeMode by mutableStateOf(prefs.getString(KEY_THEME, "system") ?: "system")
        private set
    /** Use the phone's wallpaper-based palette (Android 12+). */
    var matchPhoneColors by mutableStateOf(prefs.getBoolean(KEY_DYNAMIC, true))
        private set

    fun isFavTeam(league: League, id: String) = "${league.name}:$id" in teams
    fun isFavDriver(name: String) = F1.driverKey(name) in drivers
    fun favTeamIds(league: League): Set<String> =
        teams.filter { it.startsWith("${league.name}:") }.map { it.substringAfter(':') }.toSet()

    fun toggleTeam(league: League, id: String, displayName: String) {
        val key = "${league.name}:$id"
        val nowFav = key !in teams
        teams = if (nowFav) teams + key else teams - key
        teamNames = if (nowFav) teamNames + (key to displayName) else teamNames - key
        prefs.edit()
            .putStringSet(KEY_TEAMS, teams)
            .putStringSet(KEY_TEAM_NAMES, teamNames.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }

    fun toggleDriver(fullName: String) {
        val key = F1.driverKey(fullName)
        if (key.isEmpty()) return
        val nowFav = key !in drivers
        drivers = if (nowFav) drivers + key else drivers - key
        driverNames = if (nowFav) driverNames + fullName else driverNames.filterNot { F1.driverKey(it) == key }.toSet()
        prefs.edit().putStringSet(KEY_DRIVERS, drivers).putStringSet(KEY_DRIVER_NAMES, driverNames).apply()
    }

    fun setAlerts(enabled: Boolean) { alertsEnabled = enabled; prefs.edit().putBoolean(KEY_ALERTS, enabled).apply() }
    fun updateScoreAlerts(enabled: Boolean) { scoreAlerts = enabled; prefs.edit().putBoolean(KEY_SCORE_ALERTS, enabled).apply() }
    fun updateF1Alerts(enabled: Boolean) { f1Alerts = enabled; prefs.edit().putBoolean(KEY_F1_ALERTS, enabled).apply() }
    // Named update*, not set*: a set* name would clash with the property's generated setter.
    fun updateThemeMode(mode: String) { themeMode = mode; prefs.edit().putString(KEY_THEME, mode).apply() }
    fun updateMatchPhoneColors(on: Boolean) { matchPhoneColors = on; prefs.edit().putBoolean(KEY_DYNAMIC, on).apply() }

    private fun readNames(): Map<String, String> =
        prefs.getStringSet(KEY_TEAM_NAMES, emptySet())!!.mapNotNull {
            val i = it.indexOf('|'); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    companion object {
        private const val PREFS = "favorites"
        private const val KEY_TEAMS = "teams"
        private const val KEY_TEAM_NAMES = "team_names"
        private const val KEY_DRIVERS = "drivers"
        private const val KEY_DRIVER_NAMES = "driver_names"
        private const val KEY_ALERTS = "alerts"
        private const val KEY_SCORE_ALERTS = "score_alerts"
        private const val KEY_F1_ALERTS = "f1_alerts"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "match_phone_colors"

        @Volatile private var instance: Favorites? = null
        fun get(context: Context): Favorites =
            instance ?: synchronized(this) { instance ?: Favorites(context).also { instance = it } }
    }
}
