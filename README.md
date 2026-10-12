<div align="center">
  <img src="theme/keyboard/midnight-keyboard.png" alt="Midnight Raster pixel artwork" width="720" />
  <h1>Omarchy Theme</h1>
  <p><strong>Midnight Raster</strong> — an Omarchy-inspired pixel workspace for Android.</p>
  <p>
    <a href="LICENSE"><img alt="AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-8e79a8?style=flat-square"></a>
    <img alt="Android API 29+" src="https://img.shields.io/badge/Android-API%2029%2B-87bcad?style=flat-square&logo=android&logoColor=white">
    <img alt="SIL Open Font License 1.1" src="https://img.shields.io/badge/fonts-OFL--1.1-c1afd4?style=flat-square">
  </p>
</div>

Midnight Raster brings a dark, large-pixel visual language to an Android home workspace: lavender and warm-gray type, quiet mint accents, and a field of drifting square pixels. It combines a standalone launcher, animated wallpaper, an opt-in local wake-word assistant, and matching terminal and keyboard themes.

## Acknowledgements

Midnight Raster was inspired by [Omarchy](https://github.com/omacom/omarchy), DHH’s Linux distribution. Thank you to DHH and the Omarchy contributors for creating and sharing a distinctive, opinionated pixel-art desktop experience that inspired this Android adaptation. Midnight Raster is an independent project and is not affiliated with or endorsed by Omarchy or its maintainers.

## What’s included

- **Night Terminal** — searchable app drawer, configurable shortcuts, pixel clock, and a launcher-style home screen.
- **Midnight Raster wallpaper** — animated pixel motion for the home and lock-screen wallpaper surfaces, with a static fallback. Android and the device manufacturer still draw the system status bar, lock screen controls, and unlock flow.
- **Pixel Voice (experimental)** — an on-device `sherpa-onnx` wake-word detector for “大肥鱼” and a local bridge to an already authenticated Codex CLI. Background standby is explicit and uses Android’s foreground-service notification.
- **Terminal and keyboard themes** — reversible Termux configuration plus a Trime color and font patch.
- **Redistributable pixel fonts** — Fusion Pixel Font for Chinese and Latin text, and Press Start 2P for display accents. See [third-party notices](THIRD_PARTY_NOTICES.md).

## Preview

The launcher is a normal Android application, not a replacement for ColorOS or another vendor’s system UI. Some OEM builds may ignore a third-party Home selection or continue routing the Home action to their own launcher. The app can still be opened directly, and the wallpaper and themes can be used independently.

## Build the Android app

Requirements: JDK 17 and Android SDK Platform 35. The pinned Sherpa-ONNX AAR is included in the repository; Gradle downloads the Android Gradle Plugin and Kotlin standard library.

```sh
./night-terminal/gradlew -p night-terminal :app:assembleDebug
```

Install the debug build over USB with Android Debug Bridge:

```sh
adb install -r night-terminal/app/build/outputs/apk/debug/app-debug.apk
```

Choose **Night Terminal** from Android’s Home app setting if your device supports third-party launchers. Debug APKs are locally signed and are intended for development; this repository does not publish a store-ready release APK.

## Apply the themes

In Termux, install from a checkout of this repository:

```sh
python theme/termux/install.py
```

To restore the previous files later, run:

```sh
python theme/termux/install.py --restore
```

The installer backs up the files it changes and refuses to restore over later edits. Trime’s configuration and font setup are documented in [Termux and keyboard themes](docs/termux-and-keyboard.md).

## Pixel Voice

Voice setup is optional and experimental. It requires the Android app, Termux, a working Codex CLI installation that the `codex` command can start, and a user-authorized login. The wake detector runs locally; a Codex voice session starts only after the assistant is opened and microphone access is granted. Setup, local token provisioning, data flow, and compatibility limits are in [Pixel Voice](docs/pixel-voice.md).

The bridge uses the Codex CLI app-server interface, which can change between CLI versions. Android shell execution through Termux/PRoot is not a supported Codex desktop environment; this project uses the bridge for voice conversation and does not promise a full local coding-agent workflow on Android.

## Repository map

| Path | Purpose |
| --- | --- |
| `night-terminal/app/` | Android launcher, animated wallpaper, and voice app |
| `phone/voice/` | Loopback Codex voice bridge and web UI |
| `phone/codex` | Configurable Termux/PRoot Codex launcher |
| `theme/termux/` | Termux palette, font, and backup-aware installer |
| `theme/keyboard/` | Trime palette, font mapping, and background artwork |
| `docs/` | Architecture, setup, and privacy notes |
| `tests/` | Offline regression checks for the bridge and theme installer |

The device-specific icon-pack export and test audio recordings are not distributed. The app resolves installed icons at runtime; the wake-word model is included, but personal voice samples are not.

## Development

Read [architecture notes](docs/architecture.md) before changing platform services. Python checks use only the standard library; the browser UI checks use Node’s built-in test runner:

```sh
python3 -m unittest discover -s tests -v
node --test tests/voice-ui.test.js
```

Please keep real-device identifiers, screenshots with personal notifications, authentication material, generated recordings, and local build output out of commits. See [Contributing](CONTRIBUTING.md).

## License

Project source is licensed under [GNU AGPL v3](LICENSE). Bundled fonts, the Sherpa-ONNX runtime, and the keyword model carry their own notices and licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). A component’s own license takes precedence for that component.
