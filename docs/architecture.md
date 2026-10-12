# Architecture

## Components

| Component | Implementation | Responsibility |
| --- | --- | --- |
| Night Terminal | Android app (`night-terminal/app`) | Home dashboard, searchable app list, shortcuts, settings, and pixel clock |
| Pixel wallpaper | `PixelWallpaperService` + `PixelMotion` | Shared Canvas renderer for static and animated wallpaper frames |
| Local wake detector | `WakeWordService` + `LocalKeywordDetector` | Opt-in microphone capture and offline keyword spotting |
| Voice screen | Android `VoiceActivity` and assistant services | Permission-gated WebView conversation and Android assistant integration |
| Codex bridge | Python standard library server in `phone/voice` | Authenticated loopback endpoints and one ephemeral Codex app-server session |
| Terminal and keyboard themes | `theme/termux` and `theme/keyboard` | Termux palette/installer and Trime configuration |

## Boundaries

Night Terminal is a regular launcher app. It cannot redraw another app’s UI, change the OEM status bar or system font, replace the keyguard, or change the biometric unlock animation. The wallpaper service can provide animated wallpaper behind system-owned lock-screen content when Android and the OEM permit it.

The wake detector is opt-in and local. Android microphone and notification permissions, a visible foreground-service notification, device battery policy, and OEM background limits apply. The app does not ship voice recordings or a speaker identity profile.

The voice bridge listens only on `127.0.0.1:8771`. It accepts a small fixed API, uses an independently generated local token, checks the Host and Origin, creates an ephemeral read-only thread, and denies tool requests. This is defense in depth, not a general-purpose remote-access server. Do not bind it to a LAN interface.

## Asset and build choices

The Android project targets API 35 and requires JDK 17. A pinned Sherpa-ONNX Android AAR and the offline keyword model are kept with their upstream notices so the wake detector can be built without downloading model data at runtime. Generated Gradle output, local SDK paths, phone reports, and private test audio are excluded.

The bundled `pixel_body.ttf` and Termux font are the same Simplified Chinese Fusion Pixel Font file. The duplicate paths are required by Android resources and Termux; Git stores their identical contents once. The Trime patch refers to the font as `FusionPixel.ttf` when copied into Rime’s fonts directory.
