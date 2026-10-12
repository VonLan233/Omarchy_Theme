# Contributing

Small, reviewable changes are easiest to maintain. Please open an issue for larger changes before sending a pull request.

## Before submitting

- Keep pull requests focused and describe the user-visible behavior.
- Do not include device reports, private paths, account data, screenshots with notifications, audio recordings, or generated build files.
- Preserve the backup and restore guarantees in the Termux installer.
- Keep the launcher usable with Android’s system font fallback and reduced-motion behavior.
- Keep the voice bridge loopback-only, authenticated, and limited to its fixed endpoints.

## Local checks

```sh
python3 -m unittest discover -s tests -v
node --test tests/voice-ui.test.js
./night-terminal/gradlew -p night-terminal :app:assembleDebug
```

Device-specific checks are useful for wallpaper, microphone, and OEM launcher behavior. Describe the Android version and the result without attaching personal device identifiers or private recordings.
