# TV Remote — Android app

A real Android app (Kotlin + Jetpack Compose) that controls an **Android TV
/ Google TV** device using Google's own **Android TV Remote Service v2
protocol** — the same protocol the official Google TV app and Google Home
app use. Built from the provided `tv-remote.html` design.

## How it works
1. Your phone finds the TV over Wi-Fi via mDNS (`_androidtvremote2._tcp`) —
   no developer mode needed.
2. First connection: a mutual-TLS handshake on port 6467, the TV shows a
   6-character code on screen, you type it into the app once. The app's
   self-signed certificate is then remembered by the TV forever.
3. Every reconnect after that goes straight to port 6466 — a persistent
   session where every button press is a few protobuf bytes on an already
   open socket, dispatched directly into the TV's input pipeline. No
   process spawned per key, which is what makes it instant.

## Known ceiling (please read)
Even Google's own protocol has **no free-moving mouse pointer message** —
only key events. The "cursor" in remote apps like NowRemote is DPAD focus
navigation, not a true pixel pointer. This app's touchpad does the same:
instant DPAD navigation, not a floating cursor.

## Build it
Open in Android Studio, let Gradle sync (pulls in the protobuf compiler
and Bouncy Castle automatically), run on a phone on the same Wi-Fi as the
TV. Or push to GitHub — the included `.github/workflows/build.yml` builds
a debug APK automatically; download it from the Actions tab's Artifacts.

## First-time pairing
1. Tap the TV name at the top → app scans Wi-Fi (few seconds).
2. Tap your TV → a 6-character code appears on the TV screen.
3. Type it into the app's dialog → done. Every future connect skips this.

## Project structure
```
app/src/main/proto/
├── pairing.proto          # pairing handshake schema (port 6467)
└── remotemessage.proto    # live key-injection schema (port 6466)

app/src/main/java/com/tvremote/app/
├── RemoteViewModel.kt
├── protocol/
│   ├── crypto/
│   │   ├── CertificateManager.kt   # self-signed client cert (Bouncy Castle)
│   │   └── TlsSupport.kt           # mutual-TLS + pairing secret hash
│   ├── pairing/AndroidTvPairingClient.kt
│   ├── remote/AndroidTvRemoteClient.kt   # persistent session + key sends
│   └── discovery/TvDiscovery.kt          # mDNS discovery
└── ui/  (unchanged from the original design)
```

## Push to your own GitHub repo
```bash
cd TVRemoteApp
git remote add origin https://github.com/<your-username>/<your-repo>.git
git branch -M main
git push -u origin main
```
