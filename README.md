# Scoreology

Personal Android app for NFL, college football, and F1: live scores, game detail with team and player stats, standings, favorites, and alerts.

## What's in it

| Tab | What it shows | Refresh |
|---|---|---|
| Scores | NFL / College (all FBS) by week, favorites pinned first, live down and distance, "My teams" filter | 30 s while any game is live, 5 min otherwise |
| Game detail | Score, quarter linescore, team stats, player stats by category (passing, rushing, receiving, defense...), scoring plays, drive-by-drive play-by-play, favorite toggle | 20 s while live |
| F1 | This weekend's sessions with running order (tap to expand), last race classified results with grid, time/status, points, fastest lap | 30 s while a session is live |
| Standings | NFL (by division), College (by conference), F1 drivers, F1 constructors | 30 min |
| Settings | Favorite teams and drivers, alerts, appearance (match phone colors; System, Light, Dark, AMOLED) | |

Polling only runs while the app is on screen.

## Build the APK with GitHub Actions (no installs on your PC)

1. Sign in at github.com (free account is fine) and create a **new private repository**, for example `scoreline`. Don't add a README or .gitignore.
2. Unzip `Scoreline-source.zip` on your computer.
3. On the empty repo page, click **uploading an existing file**. Open the unzipped `scoreline` folder, select **everything inside it** (not the folder itself), and drag it onto the page. Commit to `main`.
4. Confirm the repo now shows a `.github/workflows/build-apk.yml` file. Some browsers skip hidden folders on drag-and-drop. If it's missing: **Add file > Create new file**, type `.github/workflows/build-apk.yml` as the name, paste in that file's contents from the zip, and commit.
5. Open the **Actions** tab. The "Build APK" run starts automatically (first run is roughly 5 to 8 minutes). You can also start it manually with **Run workflow**.
6. When the run shows a green check, open it and download **Scoreology-APK-N** under Artifacts. GitHub delivers it as a .zip; the .apk is inside.

## Install on the phone

1. Get the .apk onto the phone. Easiest: open the Actions run in the phone's browser (signed in to GitHub), download the artifact, and open the zip in the Files app.
2. Tap the .apk. Android will ask you to allow **Install unknown apps** for that app (browser or Files). Allow it for that one source.
3. Play Protect may say the developer is unrecognized. Choose **Install anyway**. That warning appears for any app not from the Play Store.

To update: push any change (or click Run workflow), download the new APK, and install it over the old one. Favorites are kept, because every build is signed with the same key and gets a higher version number.

## Things to know

- **Data sources.** Scores, stats, and NFL/college standings come from ESPN's public but *undocumented* endpoints. They are free and fast but can change without notice; if a screen suddenly shows "Couldn't load data", that's the likely cause and the parser needs a tweak. F1 standings and results come from the Jolpica F1 API (documented, community-run successor to Ergast). The F1 weekend running order comes from ESPN and is not official timing.
- **Alerts** are checked about every 15 minutes, which is Android's floor for background work, and Doze can delay them further while the phone sits idle. They are close-to-live, not play-by-play. For reliability set the app's battery usage to **Unrestricted** (Settings > Apps > Scoreology > Battery). Alert types: kickoff, score change (optional), final; F1 qualifying, sprint, and race results with the top 3.
- **Keep the repo private.** The signing key (`keystore/scoreline.jks`) and its password are committed so every build can update the installed app. Anyone holding that key could sign an "update" your phone would accept. For a stricter setup, move the key into GitHub Actions secrets.
- **Personal use.** ESPN's data is not licensed for redistribution; don't publish this app.

## Project layout

```
app/src/main/java/com/nate/scoreline/
  Net.kt              HTTP + null-tolerant JSON helpers
  Espn.kt             NFL/college scoreboard, game summary, standings, team list parsers
  F1.kt               ESPN F1 weekend + Jolpica results/standings parsers
  AlertLogic.kt       Pure change detection for notifications
  Alerts.kt           Notification channel + 15-minute WorkManager job
  Favorites.kt        Saved favorites and settings
  MainActivity.kt     Tabs and back stack
  UiCommon.kt         Theme, lifecycle-aware polling, shared widgets
  ScoresScreen.kt / GameDetailScreen.kt / F1Screen.kt / StandingsScreen.kt / SettingsScreen.kt
.github/workflows/build-apk.yml   CI build that produces the APK
```

Stack: Kotlin 2.0.21, Jetpack Compose (Material 3), AGP 8.5.2, Gradle 8.9, minSdk 26 (Android 8.0), targetSdk 34.
