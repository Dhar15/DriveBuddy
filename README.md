# DriveBuddy (milestone 1: "hello drive")

Personal, open-source-first driving copilot for Android. Milestone 1 proves the plumbing and starts measuring latency:
**Talk -> beep -> listen -> "got it" tone -> transcript -> rule router -> spoken reply -> one JSON log line.**

Target device: OnePlus Nord 5, Android 15 (minSdk 29, compile/target 35). No AndroidX, no paid services.

## Status: NOT yet compiled

Written in a sandbox with no Android SDK. Kotlin syntax was parsed and the XML validated, and the Python tools are
tested, but the app has **never been type-checked or run**. Expect a few small compile fixes on first sync.

## Run it

1. Android Studio: File > Open > this folder. Accept Gradle sync (it fetches Gradle 8.9 and the plugins itself;
   if it offers to upgrade AGP/Kotlin versions, accepting is fine).
2. Run the unit tests first: right-click `app/src/test` > Run Tests (LatencyTracker, CommandParser).
3. Plug in the phone (USB debugging on), Run `app`.
4. Tap **Start drive session**, grant microphone and notification permissions.
5. Tap **Talk** (on screen, or in the notification), say something, hear "You said: ...". Say "stop" or "never mind"
   to get "Okay."
6. The screen now shows the last log lines (long-press to copy). To pull the whole file on Windows:
   - Studio: View > Tool Windows > Device Explorer, open `sdcard/Android/data/app.drivebuddy/files/latency.jsonl`,
     right-click > Save As.
   - Or PowerShell: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pull /sdcard/Android/data/app.drivebuddy/files/latency.jsonl .`
   - Then: `python tools\summarize_latency.py latency.jsonl`
7. Debugging: in Logcat filter by `DriveBuddy` to see each step (start command, transcript, recogniser errors).

## What to check on the phone (these decide the next step)

- Does the on-device recogniser get used? The log's `stt` field says `on-device` or `system-default`.
- `end_to_ack` should be tens of ms; `end_to_voice` is the real wait. Target: p50 under 2000 ms.
- Does the notification **Talk** button work with the screen off or the phone in your pocket?
- Does the session survive 30+ minutes in the background (OnePlus battery management can kill it)?
  Set Battery > DriveBuddy > Unrestricted if it does.

## Layout

| Path | Role |
| --- | --- |
| `core/LatencyTracker.kt`, `core/CommandParser.kt` | Pure Kotlin, unit-tested |
| `audio/SpeechCapture.kt`, `Speaker.kt`, `Cue.kt` | Android speech-to-text, TTS, tones |
| `DriveService.kt` | Microphone foreground service, interaction flow |
| `MainActivity.kt` | Plain-views test screen |
| `tools/summarize_latency.py` | p50/p90 and go/kill check on the pulled log |

## Roadmap

1. **M1b "Hi Oreo" wake word.** Plan: sherpa-onnx keyword spotting (open source; I believe it accepts arbitrary
   keywords as text so no model training is needed, but this is unverified) running only while a drive session is on,
   calling `DriveService.beginInteraction()`. Keep "Talk" as fallback.
2. M2 fast lane: call by contact name, navigate, music control.
3. M3 ETA from Google Maps' navigation notification, calendar lateness warning, confirm-then-send ETA message.
4. M4 model lane (free-tier API behind one interface) and stops along the route.
