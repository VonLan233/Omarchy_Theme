# Termux and keyboard themes

## Termux

Run the installer inside Termux from the repository checkout:

```sh
python theme/termux/install.py
```

It updates the Termux color palette, prompt include, shell profile, and `~/.termux/font.ttf`. Before writing, it stores the original files in `~/.termux/feiyu-theme-backup/`. Re-running the installer is safe. Restore the saved files with:

```sh
python theme/termux/install.py --restore
```

If a managed file has changed since installation, restore stops and preserves the backup so the changes can be merged manually. The prompt snippet targets Bash; it does not alter SSH configuration or credentials.

## Trime

Copy `default.custom.yaml` and `trime.custom.yaml` into the Trime/Rime user data directory. Copy `theme/termux/font.ttf` into its `fonts/` subdirectory as `FusionPixel.ttf`, deploy the schema, and select the **Midnight Raster** color scheme. The patch is designed for Trime’s list-valued font settings; other Rime frontends may use different keys.

`midnight-keyboard.png` is background artwork only. The input method draws keys, candidates, and composition text. This theme does not change a keyboard’s engine, dictionary, or learned vocabulary.
