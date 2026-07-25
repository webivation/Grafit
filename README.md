# Grafit

[![Android CI](https://github.com/webivation/Grafit/actions/workflows/android.yml/badge.svg)](https://github.com/webivation/Grafit/actions/workflows/android.yml)

> **Grafit** streams health data from your **Colmi R02 fitness ring** directly to
> **Grafana Cloud** via the Prometheus Remote Write API.  
> A local SQLite buffer absorbs network outages so no reading is ever lost.

---

## Features

| Feature | Detail |
|---|---|
| 🔵 BLE connection | Scans for and connects to the R02 ring by name |
| 📊 Metrics streamed | Heart rate · SpO₂ · Steps · Temperature · Battery |
| 🗄️ Local buffer | Room (SQLite) queue with configurable max rows |
| 🚀 Prometheus Remote Write | Protobuf + Snappy, HTTP Basic auth (Grafana Cloud) |
| ⚙️ Configuration UI | Endpoint URL · User ID · API key · Device name · Intervals |
| 🔔 Foreground service | Keeps streaming in the background with a persistent notification |

---

## Download a pre-built APK

Every push to `main` / every pull request triggers a CI build.  
Go to **Actions → Android CI → the latest run → Artifacts** and download
`grafit-debug-<run-number>.zip`.  Unzip it to get the `.apk` and sideload it
onto your Android device (API 26 +).

---

## Building locally

```bash
# Clone
git clone https://github.com/webivation/Grafit.git
cd Grafit

# Build a debug APK  (JDK 17 + Android SDK required)
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Install on a connected device / emulator
./gradlew installDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## First-time setup

1. **Install the APK** on an Android 8+ phone.
2. Open **Grafit → ⋮ menu → Settings** and fill in:
   - **Remote-write endpoint URL** – from your Grafana Cloud stack  
     `https://prometheus-prod-XX-prod-…grafana.net/api/prom/push`
   - **User ID** – the numeric Grafana Cloud user ID (shown on the same page)
   - **API key** – a Grafana Cloud API key with `metrics:write` scope
   - **Device name** – BLE name your ring advertises (default `R02`)
3. Grant **Bluetooth** and **Notification** permissions when prompted.
4. Tap the **▶ FAB** on the main screen to start streaming.

---

## Architecture

```
┌──────────────┐  BLE notifications  ┌──────────────────┐
│  R02 Ring    │ ──────────────────> │  R02BleManager   │
└──────────────┘                     └────────┬─────────┘
                                              │ RingMetric
                                     ┌────────▼─────────┐
                                     │  DataSyncService  │  (Foreground Service)
                                     └──┬────────────┬───┘
                           persist      │            │  flush (every N ms)
                     ┌──────────────────▼──┐   ┌────▼──────────────────┐
                     │  Room buffer (SQLite)│   │ PrometheusRemoteWriter│
                     │  BufferedMetric      │   │ (OkHttp + protobuf    │
                     └─────────────────────┘   │  + Snappy)            │
                                               └────────────────────────┘
                                                          │
                                               ┌──────────▼──────────┐
                                               │  Grafana Cloud       │
                                               │  Prometheus endpoint │
                                               └─────────────────────┘
```

### Prometheus metrics emitted

| Metric name | Unit | Description |
|---|---|---|
| `grafit_heart_rate_bpm` | bpm | Heart rate |
| `grafit_spo2_percent` | % | Blood oxygen saturation |
| `grafit_steps_total` | count | Steps since last sync |
| `grafit_temperature_celsius` | °C | Skin temperature |
| `grafit_battery_percent` | % | Ring battery level |

All metrics carry `device` and `source` labels (e.g. `device="R02",source="grafit"`).

---

## Permissions required

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+) | BLE scanning & GATT connection |
| `ACCESS_FINE_LOCATION` (API < 31) | Required by Android for BLE scanning |
| `INTERNET` | Sending metrics to Grafana Cloud |
| `FOREGROUND_SERVICE` | Background streaming service |
| `POST_NOTIFICATIONS` (API 33+) | Persistent service notification |

---

## License

[LICENSE](LICENSE)