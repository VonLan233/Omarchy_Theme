# Pixel Voice (experimental)

Pixel Voice is an optional Android voice front end. It combines offline keyword spotting for **大肥鱼** with an explicitly started voice session through the Codex CLI app-server interface.

## Data flow

1. The user starts standby from the app and grants Android microphone and notification permissions.
2. `WakeWordService` runs the sherpa-onnx keyword model on the device. It does not upload the standby audio.
3. On a detected wake word, Android opens the voice screen. A conversation begins only after the WebView has microphone permission.
4. The loopback bridge starts an ephemeral read-only Codex thread. During the conversation, WebRTC sends audio to the Codex service for the authenticated account; tool requests from the bridge are rejected.
5. The bridge creates a random local token with owner-only file permissions. It does not log request bodies, audio, or conversation text and stops abandoned sessions.

The model detects a phrase; it is not speaker verification. It can miss speech or react to similar sounds. Keep the visible notification enabled, and stop standby from the app when it is not needed.

## Requirements and setup

- Android 10 or later, with microphone and notification permissions available.
- Termux with Python and a working `codex` command that has been authenticated by its owner.
- The debug Android build from this repository.

Place the `phone/voice` directory at `~/.local/share/feiyu-voice` in Termux and start `server.py` from there. The bridge binds to `127.0.0.1:8771`; keep Termux running while using voice. The first server start creates `~/.local/share/feiyu-voice/token`.

For a **debug build only**, provision that local token into the app’s private preferences over USB debugging. Run this from the development computer after the bridge has started; it streams the token between the two app sandboxes without printing it:

```sh
adb shell run-as com.termux sh -c 'cat /data/data/com.termux/files/home/.local/share/feiyu-voice/token' \
  | tr -d '\r\n' \
  | python3 -c 'import sys; print("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"token\">" + sys.stdin.read() + "</string></map>")' \
  | adb shell run-as com.feiyu.nightterminal sh -c 'mkdir -p shared_prefs && cat > shared_prefs/voice.xml'
```

`run-as` is available because this is the debug variant. This is a developer setup step, not a supported release provisioning flow. Never put the token in the repository, a URL, or a public issue.

The optional `phone/codex` wrapper expects the Codex CLI binary at `~/.local/lib/codex/codex`; set `CODEX_BINARY` to another path when needed. It uses PRoot for Termux path compatibility. It does not make Android a supported Codex desktop platform.

## Compatibility limits

The bridge currently uses the Codex CLI app-server’s experimental realtime methods. This interface can change without notice. A successful voice response does not mean local shell tools or the desktop sandbox work on Android. If Codex changes the transport, disable the voice bridge until it is updated; do not expose the local server to the network.
