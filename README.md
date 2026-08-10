# BLE Sonar

A native Android Bluetooth LE scanner drawn as a sonar scope. Java runs the
scan; a WebView draws it.

---

## Build it without installing anything

The repo builds itself on GitHub's runners, which already have the Android SDK.

1. Create a new repo on github.com and push this folder to it
   (or use **Add file → Upload files** and drag the whole thing in).
2. Open the **Actions** tab. The build starts on push; otherwise pick
   *Build APK* and press **Run workflow**.
3. When it goes green, open the run and download the **ble-sonar-apk**
   artifact from the Summary page.
4. Unzip on your phone, tap `app-debug.apk`, allow install from unknown
   sources when prompted.

That is the whole path. No Android Studio, no SDK, no JDK on your machine.

## Or build it locally

Android Studio → **Open** this folder → **Run**. The Gradle wrapper is
included and pinned to 8.9, so the sync will not ask you anything. From a
terminal, with the SDK already installed:

```
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` is intentionally absent; Android Studio writes it, or set
`ANDROID_HOME` for a command-line build.

---

## Why this is native and not a wrapped web page

Android's WebView does not implement Web Bluetooth. A WebView wrapper around
the browser build compiles, launches, looks perfectly correct, and finds
nothing — forever.

So `BluetoothLeScanner` runs in `MainActivity` in `SCAN_MODE_LOW_LATENCY`,
batches advertisements every 250 ms, and pushes them over a
`JavascriptInterface` into the HTML. The web layer keeps all the smoothing,
distance estimation and rendering. This also sidesteps every failure the
browser build ran into:

| Browser build | This build |
|---|---|
| Blocked by iframe Permissions-Policy | No iframe, no policy layer |
| Needs an experimental Chrome flag | Uses the ordinary platform API |
| Needs HTTPS hosting | Loads from `assets/` |
| Opaque per-origin device IDs | Real MAC addresses |
| Desktop adapter support is unreliable | Android BLE stack, well-trodden |

## Layout

```
app/src/main/
  AndroidManifest.xml                        permissions, one activity
  java/nl/tunc/blesonar/MainActivity.java    scanner + JS bridge
  assets/sonar.html                          the entire UI, self-contained
  res/                                       launcher icon, app name
.github/workflows/build.yml                  builds the APK in CI
```

No AndroidX, no support library, no third-party dependencies. The only thing
Gradle fetches is the Android Gradle Plugin itself.

## Permissions

Android 12+ needs only `BLUETOOTH_SCAN`, declared `neverForLocation`, so the
OS never asks for location and none is read. Android 11 and below are forced
by the OS to hold `ACCESS_FINE_LOCATION` for any BLE scan — an Android rule,
not a use of your position.

Scanning stops in `onPause()`. Android throttles background scans to roughly
one result every few seconds, which would make the trend arrows lie. If you
want it running in your pocket, that needs a foreground service with the
`connectedDevice` type.

## What the display actually means

Radius is distance estimated from RSSI by log-distance path loss —
`d = 10^((txPower − rssi) / (10 · n))` — with `txPower` taken from the
advertisement where present, else −59 dBm at 1 m, and `n` = 2.2. Expect 2–3×
error through bodies, pockets and walls. Both constants are at the top of
`sonar.html` if you want to calibrate against a known device at a measured
metre.

**Bearing is fabricated.** BLE carries no angle of arrival. Each contact sits
at a fixed angle hashed from its MAC so blips stay put and stay trackable, but
that angle means nothing. The trend arrow — RSSI slope in dB/s over the last
nine seconds — is the only real direction information, and it only resolves
once you move. Walk ten paces, then read it.

Real bearing needs AoA/AoD: Bluetooth 5.1 direction finding, with an antenna
array on the receiver. No phone has one.

## Expect a crowd

Phones and modern trackers rotate their Bluetooth addresses every ~15 minutes,
so the same handset reappears as a fresh contact. The stable blips are the
ones with static addresses — speakers, TVs, sensors, older gear.
