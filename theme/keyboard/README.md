# Midnight Raster for Trime

The two YAML files are user-level Rime patches. Copy them into the Trime/Rime user data directory, copy `../termux/font.ttf` into its `fonts/` directory as `FusionPixel.ttf`, deploy the schema, and select the **Midnight Raster** color scheme.

`default.custom.yaml` prefers the simplified Chinese Luna Pinyin schema while keeping the original traditional Chinese schema available. `trime.custom.yaml` applies the palette and binds the keyboard, candidates, labels, Latin text, and symbols to the pixel font.

`midnight-keyboard.png` is a background image only. Trime draws the keys and composition UI. The patch changes neither the keyboard engine nor the user’s dictionaries and learned vocabulary. Trime versions and other Rime frontends may use different configuration paths or font keys.
