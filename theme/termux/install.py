#!/usr/bin/env python3
"""Install/restore this theme in Termux; refuse to overwrite subsequent edits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

SOURCE = '\n# FEIYU NIGHT TERMINAL\n[ -f "$HOME/.termux/night-terminal.bash" ] && . "$HOME/.termux/night-terminal.bash"\n'
FILES = (".termux/colors.properties", ".termux/night-terminal.bash", ".bashrc", ".termux/font.ttf")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None


def apply(home, restore=False):
    home = Path(home)
    backup = home / ".termux/feiyu-theme-backup"
    manifest = backup / "state.json"
    for name in FILES:
        path = home / name
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise ValueError(f"Refusing non-regular file: {name}")
    if backup.exists() and not manifest.exists():
        raise ValueError(f"Incomplete backup; inspect before retrying: {backup}")
    if manifest.exists():
        state = json.loads(manifest.read_text())
        for name in FILES:
            if digest(home / name) != state[name]["installed"]:
                raise ValueError(f"Changed since installation; preserve and merge manually: {name}")
        if not restore:
            return "Theme already installed; original backup preserved."
        for name in FILES:
            if state[name]["existed"]:
                shutil.copy2(backup / name, home / name)
            else:
                (home / name).unlink(missing_ok=True)
        shutil.rmtree(backup)
        return "Original Termux theme restored."
    if restore:
        raise ValueError("No theme backup found; nothing changed.")
    source = Path(__file__).parent
    content = {
        FILES[0]: (source / "colors.properties").read_bytes(),
        FILES[1]: (source / "night-terminal.bash").read_bytes(),
        FILES[2]: ((home / ".bashrc").read_bytes() if (home / ".bashrc").exists() else b"") + SOURCE.encode(),
        FILES[3]: (source / "font.ttf").read_bytes(),
    }
    backup.mkdir(parents=True, mode=0o700)
    state = {}
    # Back up every target before writing any configuration.
    for name in FILES:
        target = home / name
        state[name] = {"existed": target.exists(), "installed": hashlib.sha256(content[name]).hexdigest()}
        if target.exists():
            (backup / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup / name)
    try:
        for name in FILES:
            target = home / name
            staged = target.with_name(target.name + ".feiyu-new")
            with staged.open("xb") as f:
                f.write(content[name])
            staged.chmod(target.stat().st_mode & 0o777 if target.exists() else 0o600)
            os.replace(staged, target)
        manifest.write_text(json.dumps(state, indent=2))
        manifest.chmod(0o600)
    except Exception:
        for name in FILES:
            if state[name]["existed"]:
                shutil.copy2(backup / name, home / name)
            else:
                (home / name).unlink(missing_ok=True)
        # Keep the backup available for inspection after an interrupted install.
        raise
    return "Night Terminal theme installed; original files backed up."


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restore", action="store_true")
    args = parser.parse_args()
    home = Path("/data/data/com.termux/files/home")
    if not home.is_dir():
        parser.error("Run this script inside Termux.")
    print(apply(home, args.restore))
    reload = Path("/data/data/com.termux/files/usr/bin/termux-reload-settings")
    if reload.is_file():
        subprocess.run([str(reload)], check=True)
