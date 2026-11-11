# TrayTimer

A clear-aligner wear-time tracker for Android: log when your trays are out, get reminded to put them back in, follow your treatment schedule, and watch your progress.

---

## ⚠️ This app is vibecoded

**TrayTimer was written almost entirely by AI through "vibe coding."** A human described the desired behaviour; an AI wrote the code and iterated until it worked. That means:

- It has **not** been professionally audited, security-reviewed, medically validated, or battle-tested.
- Expect rough edges, unfinished seams, and bugs.
- There is no backend, billing, ads, or telemetry (see *Stubs & omissions*).
- **Do not use it as medical advice or as the only record of your treatment.** Always follow your orthodontist's instructions.
- Review the code before trusting it with health data or building on it.

---

## What it does

### Timer
- Daily **In time / Out time** with a ring chart and a configurable daily wear goal.
- One-tap **trays out / trays in** toggle; ring is **blue while wearing** and **red while trays are out**.
- **Undo**, edit, and delete time entries (with confirmation).
- **Running timer adjustment:** tap *Adjust* while trays are out, choose 15/30/45/60/90 minutes or a custom duration, and the timer resets to that much **time remaining** — the original out time is preserved.
- Put-back reminder choice when you take trays out (15/30/45/60 min, custom, or none).
- **Brushing mode:** a 2-minute full-screen countdown that marks trays out when it starts and back in when it ends (cancelling leaves them out).

### Treatment plan
- Phases: initial aligners, refinements, retainers.
- Per-aligner durations with "apply to subsequent aligners".
- Early / late / backward tray-change detection with confirmations.
- Exact switch timestamps: wear time for a new aligner starts when you actually change trays, not at midnight.
- Tray-change reminder at the planned day/time, with an actionable notification and an in-app **Change now / Later** popover.
- Automatic Treatment Adjustment (recalculates subsequent trays on early/late switches).
- **Rebuild plan** action to re-anchor the current aligner to today.
- Per-aligner cumulative wear, plan summary, and deletion that keeps history.

### Calendar
- Month heat map of goal attainment.
- **Tap a day** to view that day's out/in events, appointments, and note.
- **+** to add: out/in event (with date, out time, in time), day note, orthodontic appointment (with 1 day / 1 hour / X hours / X minutes-before reminders), or an aligner switch.
- Distinct aligner badges (`T32`) on tray-change days.

### Teethies (progress photos)
- CameraX camera with 9 guided angles, mirror mode, and front/back flip.
- Import from the photo library, photo timeline, metadata editing (tray + angle), flip, delete.
- Side-by-side compare, collages, and a generated **MP4 progress video** (MediaCodec/EGL), shared via the system share sheet.
- Opt-in save to the device photo library.

### Stats
- 7/30/90-day averages (only over tracked days), goal attainment, current streak.
- Total wear per aligner, and a shareable text summary.

### Reminders & notifications
- Exact alarms with graceful fallback, plus boot / time-zone recovery.
- Put-back, tray-change, tray-total, selfie, and appointment reminders.
- **Full-screen wearing alarm** over the lock screen with a ringing foreground service.
- Mute a ringing alarm with the on-screen **Mute** button or the volume-down key; the full-screen reminder stays visible.
- Reminder notification actions: **Start wearing**, **Add 5 min**, **Add 10 min**.
- Re-alerts every **5 minutes for the first 30 minutes**, then every **15 minutes** until answered.
- Configurable **full-screen alarm** (or notification-only) and a selectable **alarm sound**.
- **Export/import** all treatment data and photos as a local `.zip`.
- Notification diagnostics, permission deep links, and OEM "don't kill my app" guidance.

### Android surfaces
- **Live Updates**: persistent progress notification via a special-use foreground service.
- Home-screen widgets: 1×1 status and resizable 4×1 timer with start/pause and change-tray actions.
- **Quick Settings tile** to toggle the timer.
- Deep links (`traytimer://app.traytimer.com/...`), app shortcuts, and voice-command phrases for Assistant/Gemini.
- In-app settings hub (timer, reminders, notifications, calendar, live updates, widgets, tile, watch placeholder, voice, photos, appearance, time & locale, privacy, about).
- Onboarding wizard with treatment setup, goal affirmations, and reminder setup.

---

## Stubs & omissions

These are intentionally not implemented or are placeholders:

- **No cloud backend / sync / backup.** All data is local (Room + DataStore). Uninstalling the app deletes it. Use *Photos → Export photos* for images.
- **No Wear OS companion.**
- **No analytics/crash reporting** — the analytics facade only logs to Logcat.
- **No network access of any kind:** the APK has no `INTERNET` permission. There is no update check, remote content, FAQ, analytics or social-media link. Brand templates ship inside the app.
- **English only.**
- Release signing uses a self-signed project keystore (not Play App Signing); back up the keystore and passwords.
- Not Play-Store ready: update the version, signing, policy disclosures, and review the permissions for your own release.

---

## Build & install

**Requirements:** JDK 17, Android SDK Platform 35 + Build-Tools 35.

```bash
# production package: com.traytimer
./gradlew assembleStandardDebug

# dev package: com.traytimer.dev (installs alongside the production app)
./gradlew assembleDevDebug

# unit tests (pure logic: day maths, plan engine, stats, cadence)
./gradlew testStandardDebugUnitTest
```

APKs:

```
app/build/outputs/apk/standard/debug/app-standard-debug.apk
app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

Install over ADB:

```bash
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

Or copy the APK to a device, allow "install unknown apps" for the file manager/browser, and tap it. Debug-signed builds may trigger Play Protect's "unsafe app" prompt — choose *Install anyway*.

**Package IDs**

| Flavor | Application ID | Label |
|---|---|---|
| standard | `com.traytimer` | TrayTimer |
| dev | `com.traytimer.dev` | TrayTimer Dev |

Version: `1.0.6` (`versionCode 7`), min SDK 26, target/compile SDK 35.

---

## Project layout

```
app/src/main/kotlin/com/traytimer/
├── MainActivity.kt                 # Compose host, deep links
├── TrayTimerApp.kt                 # Application: channels, service locator init
├── core/
│   ├── Graph.kt                    # lightweight service locator
│   ├── logic/                      # pure, unit-tested domain logic
│   │   ├── DayMath.kt              # day windows, session splitting, goal colours
│   │   ├── PlanEngine.kt           # treatment phases, chaining, switches, auto-adjust
│   │   ├── StatsCalc.kt            # averages, streaks, per-tray wear
│   │   └── ReminderCadence.kt      # 5-min → 15-min re-alert cadence
│   ├── notifications/              # channels and notification builders
│   └── services/                   # brands, update check, URLs
├── data/
│   ├── db/                         # Room entities, DAOs, database
│   ├── prefs/                      # DataStore settings
│   └── repo/                       # timer, treatment, appointments/notes, photos
├── alarm/                          # full-screen alarm activity + ringing service
├── reminders/                      # exact alarms, receivers, boot/time-change recovery
├── liveupdates/                    # persistent timer notification (FGS)
├── widgets/                        # small + medium app widgets
├── tile/                           # Quick Settings tile
└── ui/                             # Compose theme, navigation, screens
```

State is derived from timestamped out-sessions; the timer, calendar, stats and reminders all read the same data.

---

## Permissions

- `CAMERA` — Teethies capture.
- `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` — reminders and alarms.
- `FOREGROUND_SERVICE*` — full-screen ringing and Live Updates.
- `READ_CALENDAR` / `WRITE_CALENDAR` — optional system-calendar sync.

All treatment data stays on-device. The app does not request the `INTERNET` permission at all.

---

## Status & license

This is a personal, AI-generated project shared as-is. No warranty. No license has been chosen yet — add one before accepting contributions or redistributing.
