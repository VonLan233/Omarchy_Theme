import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("theme", Path(__file__).parents[1] / "theme/termux/install.py")
theme = importlib.util.module_from_spec(spec)
spec.loader.exec_module(theme)


class ThemeTest(unittest.TestCase):
    def test_install_is_idempotent_and_restore_is_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / ".termux").mkdir()
            old = {".bashrc": b"alias hello='printf hello'", ".termux/colors.properties": b"background=#123456\n", ".termux/font.ttf": b"original user font"}
            for name, content in old.items():
                (home / name).write_bytes(content)
            theme.apply(home)
            theme.apply(home)
            self.assertEqual((home / ".bashrc").read_text().count("# FEIYU NIGHT TERMINAL"), 1)
            theme.apply(home, restore=True)
            for name, content in old.items():
                self.assertEqual((home / name).read_bytes(), content)
            self.assertFalse((home / ".termux/night-terminal.bash").exists())

    def test_preserves_edits_made_after_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            theme.apply(home)
            edited = (home / ".bashrc").read_bytes() + b"\n# later user edit\n"
            (home / ".bashrc").write_bytes(edited)
            with self.assertRaisesRegex(ValueError, "Changed since"):
                theme.apply(home, restore=True)
            self.assertEqual((home / ".bashrc").read_bytes(), edited)

    def test_restore_absent_files_and_reject_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            theme.apply(home)
            theme.apply(home, restore=True)
            self.assertTrue(all(not (home / name).exists() for name in theme.FILES))
            target = home / "existing"; target.write_text("keep me")
            (home / ".bashrc").symlink_to(target)
            with self.assertRaisesRegex(ValueError, "non-regular"):
                theme.apply(home)
            self.assertEqual(target.read_text(), "keep me")


if __name__ == "__main__":
    unittest.main()
