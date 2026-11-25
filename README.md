# VoltTrace

**VoltTrace** is an on-device Android battery telemetry and forensic root-cause analyzer built with Kotlin and Jetpack Compose. Instead of showing vague percentage bars, VoltTrace records timestamped electrical, thermal, deep-sleep, radio, and app-activity readings so you can answer three questions within ten seconds of opening the app:

1. **How much battery did I actually lose?** (measured in `%` and `mAh` from the battery charge counter)
2. **Which part of the night or day mattered most?** (isolating specific drain bursts and spikes)
3. **What did the phone actually record — and what remains unknown?**

All analysis runs strictly on-device with **ordinary Android permissions** — no root, no ADB, no cloud account, and no `INTERNET` permission.

---

## What Makes VoltTrace Different

Most Android battery monitors either repackage Android's coarse `%` estimates or drain the battery themselves while polling. VoltTrace is built around forensic rigor:

- **Counter-measured charge loss & true deep sleep:** Rather than guessing from 1% steps, VoltTrace measures net charge loss directly from Android's `BATTERY_PROPERTY_CHARGE_COUNTER` (`µAh`) and calculates real kernel deep sleep by comparing `SystemClock.elapsedRealtime()` against `SystemClock.uptimeMillis()` between samples.
- **Explicit separation of evidence vs. unknowns:** Every drain episode, spike drill-down, and overnight summary shows what the hardware and OS actually recorded (timestamps, current, voltage, temperature, screen/radio state, and Usage Events) alongside an explicit **"What Remains Unknown"** section for data Android withholds from ordinary apps (such as named kernel wakelocks).
- **Signed power direction & OEM current normalization:** Automatically normalizes OEM-specific microamp (`µA`) vs. milliamp (`mA`) battery current reporting and tracks signed power direction so charging peaks and discharge peaks are never conflated.
- **Event-reconstructed app windows:** Instead of relying on Android's coarse aggregate usage buckets (which can over-report app time beyond the selected window), VoltTrace reconstructs foreground and background sessions from timestamped `UsageEvents`, clips them strictly to the analysis window, and merges overlapping intervals.
- **Automatic hardware energy-meter discovery (Android 15+):** Discovers and samples whatever `PowerMonitor` hardware rails the device actually exposes, safely filtering out counter resets across reboots.
- **Built to let the phone sleep naturally:** Uses low-overhead 15-minute `WorkManager` sampling by default, with an opt-in 1-minute foreground monitor for high-resolution sessions — designed to avoid holding partial wake locks that distort standby measurements.
- **100% local & private by design:** VoltTrace declares **no `INTERNET` permission**, sets `android:allowBackup="false"` with explicit backup/device-transfer exclusions for its Room database, DataStore preferences, and shared preferences, and clears cached export files whenever you reset telemetry history.

---

## Key Features

### 1. Overnight Replay & 10-Second Morning Report (`Live` tab)
- **Morning Report Card:** Appears at the top of the `Live` tab with total overnight loss (`-%` and `-mAh`, labeled `Counter-measured` or `Estimated`), deep-sleep percentage, a **Which Part Mattered Most** burst highlight with sparkline, and plain-English **What Your Phone Recorded** bullets.
- **Rolling 14-Day Personal Baseline:** Compares last night against your phone's own median overnight drain rate (`%/hr`), `mAh` loss, and deep-sleep ratio across prior nights.
- **One-Tap Replay:** Opens an interactive overnight playback sheet with Play/Pause, scrubbing, **Key Peak** jump, shaded key-window band, tappable night breakdown chapters, verbatim source readings, and one-tap jump into spike drill-down.
- **"Before you plugged in" Fallback:** If your phone charged overnight (or had no 2+ hour unplugged overnight standby stretch), VoltTrace automatically pivots to analyze the last meaningful unplugged stretch right before you plugged in.
- **"Watch for this again" Investigation:** Arm a persistent watch rule on any suspicious overnight window and drain threshold. On subsequent nights only, VoltTrace evaluates the pattern in the background, flags matches in the Morning Report, and posts a **Watch Matched** notification.

### 2. Live Telemetry Oscilloscope & 30-Second Drain Probe (`Live` tab)
- Real-time 2-second hardware wattage, current (`mA`), voltage (`V`), temperature (`°C`), display brightness/refresh rate, radio transport, and thermal status.
- Interactive **30-Second Live Drain Probe** to measure active wattage draw, voltage sag, and thermal slope on demand.

### 3. True Root-Cause Attribution (`Causes` tab)
- Evidence-backed breakdown across display, cellular/Wi-Fi radios, standby wakeups, thermal load, and correlated apps.
- Pure screen-off **Sleep & Standby Forensics** (automatically suppressing noisy `< 5 min` screen-off snippets and mixed screen transitions).
- Traceable drain episodes linked directly back to their underlying source readings.

### 4. Interactive Power Timeline & Spike Drill-Down (`Timeline` tab)
- Switchable **Power (W)**, **Temp (°C)**, and **Voltage (V)** charts overlaid with battery percentage and screen-on bands across `1H`, `6H`, `24H`, `7D`, and `All` windows.
- Automatic detection of drain spikes — tap any spike or scrubber point to open the **Spike Drill-Down Sheet** showing surrounding samples, electrical/thermal deltas, deep-sleep interruption, and correlated app/service activity around that exact timestamp.

### 5. Cell Inspector, Exports & Widget (`Inspector` tab)
- Full battery hardware profile, discovered `PowerMonitor` rails, background collector controls, and critical thermal/standby drain alert toggles.
- **Zero-permission Export:** Share a forensic summary (`.md`) or raw timestamped telemetry table (`.csv`) via Android's `FileProvider` Share Sheet.
- **Home Screen Widget:** Glanceable battery %, live wattage, temperature, and top drain cause with manual sync.

---

## How It Works

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. Hardware & OS Sampling (data/hardware)                               │
│    • BatteryHardwareReader: BatteryManager (µAh, signed mA, mV, °C),    │
│      elapsedRealtime vs. uptimeMillis, screen/radio/thermal state       │
│    • PowerMonitorDiscovery: Android 15+ hardware energy rails           │
│    • UsageStatsManager: timestamped foreground & background events      │
│    • BatteryTelemetryWorker (15m) + HighPrecisionMonitorService (1m)    │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. Local Persistence (data/local & data/repository)                     │
│    • Room SQLite: timestamped telemetry samples, app events, rails      │
│    • Jetpack DataStore: monitor settings & armed Watch rules            │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. Pure-Kotlin Forensic Engines (domain)                                │
│    • OvernightReplayEngine: Morning Report, 14-day baseline, fallback,  │
│      key-window isolation, and post-arm Watch recurrence matching       │
│    • RootCauseAnalyzer: counter-based mAh loss, deep-sleep audit,       │
│      clipped/merged app sessions, and hardware subsystem attribution    │
│    • SpikeInspector: spike detection & timestamped root-cause drilldown │
│    • DiagnosticReportGenerator & CsvTelemetryParser: MD/CSV export &    │
│      deterministic CSV replay for regression testing                    │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. Jetpack Compose UI, Alerts & Widget (ui & widget)                    │
│    • Material 3 four-tab interface (Live, Causes, Timeline, Inspector)  │
│    • OvernightReplaySheet & SpikeDrillDownSheet modal inspectors        │
│    • BatteryAlertManager notifications & VoltTraceWidgetProvider        │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Permissions & Privacy

VoltTrace uses only standard, least-privilege Android permissions:

| Permission | Purpose |
| :--- | :--- |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Distinguish Wi-Fi vs. cellular radio draw and link speed |
| `BLUETOOTH` (API ≤ 30) | Record whether Bluetooth radio is active during drain windows |
| `PACKAGE_USAGE_STATS` *(optional, user-granted in Settings)* | Reconstruct clipped foreground/background app sessions and correlate spikes |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Power the opt-in 1-minute High-Precision Monitor when enabled by the user |
| `POST_NOTIFICATIONS` *(optional)* | Deliver critical thermal/standby alerts and **Watch Matched** notifications |

- **No `INTERNET` permission** — zero telemetry leaves the device.
- **No cloud backup of telemetry** — `android:allowBackup="false"` plus explicit `backup_rules.xml` and `data_extraction_rules.xml` exclusions keep the local Room database and preferences out of Android Auto Backup and device-to-device transfers.
- **No storage permissions & clean resets** — report exports use `FileProvider` and the Android Share Sheet, and resetting telemetry history also deletes cached `.md`/`.csv` export files.

---

## Tech Stack

- **Language & UI:** Kotlin, Jetpack Compose, Material Design 3, Navigation Compose
- **Architecture:** MVVM with `StateFlow` / Kotlin Coroutines and pure-Kotlin domain analyzers
- **Persistence:** Room (with KSP) + Jetpack Preferences DataStore
- **Background Execution:** AndroidX WorkManager + opt-in `specialUse` Foreground Service
- **Testing:** JUnit 4, Kotlin Coroutines Test, Robolectric, and Compose UI Testing backed by 100% synthetic telemetry fixtures (`app/src/test/resources/synthetic-discharge.csv`)

---

## Building & Running Tests

### Requirements
- Android Studio or a system **Gradle** installation (JDK 11+, Android SDK with `compileSdk 36`, `minSdk 24`).
- On a fresh Git checkout outside AI Studio, decode the checked-in debug keystore once before building:
```bash
base64 -d debug.keystore.base64 > debug.keystore
```

### Build Debug APK
```bash
gradle :app:assembleDebug
```

### Run Unit & Robolectric UI Tests
```bash
gradle :app:testDebugUnitTest
```

The test suite runs entirely on the local JVM (no emulator required) using synthetic multi-night and discharge CSV fixtures to verify:
- Morning Report local-night clipping, multi-night splitting, rolling personal baseline, and key-window detection (`OvernightReplayEngineTest`, `CodeReviewRegressionTest`)
- `"Before you plugged in"` vs. neutral `"Recent Session"` fallback, mixed counter/SOC evidence labeling, and post-arm `"Watch for this again"` deduplication (`OvernightReplayEngineTest`, `CodeReviewRegressionTest`)
- 10-second Morning Report Compose UI readability and actions (`OvernightMorningReportUiTest`)
- Canonical microamp low-current handling, period-level non-counter discharge reconciliation, honest cause & modeled-vs-measured attribution labels, spike interval intersection, signed net-charge export math, and timestamp-proportional charts (`CsvReplayRegressionTest`, `RootCauseAnalyzerTest`, `CodeReviewRegressionTest`)
- Backup-exclusion rules, export cache cleanup on history reset, lifecycle-gated live polling, and rolling 1H window cutoffs (`PrivacyAndLifecycleRegressionTest`)
