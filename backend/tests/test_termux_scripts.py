"""The Termux scripts, run for real with stand-ins for curl, pkg and termux-open (no phone needed)."""
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent

CURL = """#!/bin/bash
out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift;; esac; shift; done
[ "$STUB_CURL" = fail ] && exit 22
head -c "$STUB_BYTES" /dev/zero > "$out"
"""
CP = """#!/bin/bash
[ "$STUB_CP" = fail ] && exit 1
exec /bin/cp "$@"
"""
OPEN = """#!/bin/bash
[ "$STUB_OPEN" = fail ] && exit 1
echo "$1" >> "$HOME/opened.txt"
"""


class InstallApkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        bin_dir = self.home / "bin"
        bin_dir.mkdir()
        for name, body in (("curl", CURL), ("cp", CP), ("termux-open", OPEN), ("pkg", "#!/bin/bash\nexit 0\n")):
            path = bin_dir / name
            path.write_text(body)
            path.chmod(path.stat().st_mode | stat.S_IEXEC)
        self.bin = bin_dir

    def tearDown(self):
        self.tmp.cleanup()

    def run_script(self, *args, **env):
        full = {"HOME": str(self.home), "PATH": f"{self.bin}:{os.environ['PATH']}", "STUB_BYTES": "3000000", "AIWA_STORAGE_WAIT": "0"}
        full.update(env)
        return subprocess.run(["bash", str(BACKEND / "install-apk.sh"), *args], capture_output=True, text=True, env=full, timeout=60)

    def test_a_real_download_is_kept_and_the_installer_is_not_opened_for_you(self):
        (self.home / "storage" / "downloads").mkdir(parents=True)
        done = self.run_script()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        apk = self.home / "aiwa-debug.apk"
        self.assertEqual(apk.stat().st_size, 3000000)
        self.assertFalse((self.home / "aiwa-debug.apk.part").exists())
        self.assertIn("downloaded, 2 MB", done.stdout)
        self.assertFalse((self.home / "opened.txt").exists(), "the installer must not be opened unless asked")
        self.assertIn("open the Files app, go to Downloads, tap Aiwa_widget.apk", done.stdout)

    def test_the_installer_is_opened_only_with_open(self):
        (self.home / "storage" / "downloads").mkdir(parents=True)
        done = self.run_script("--open")
        self.assertEqual((self.home / "opened.txt").read_text().strip(), str(self.home / "aiwa-debug.apk"))
        self.assertIn("tap Install/Update when it appears", done.stdout)

    def test_a_copy_goes_to_the_phones_downloads_folder_when_termux_may_see_it(self):
        (self.home / "storage" / "downloads").mkdir(parents=True)
        done = self.run_script()
        self.assertEqual((self.home / "storage" / "downloads" / "Aiwa_widget.apk").stat().st_size, 3000000)
        self.assertIn("tap Aiwa_widget.apk", done.stdout)

    def test_the_downloads_copy_is_replaced_not_duplicated(self):
        downloads = self.home / "storage" / "downloads"
        downloads.mkdir(parents=True)
        (downloads / "Aiwa_widget.apk").write_bytes(b"older build")
        done = self.run_script()
        self.assertEqual((downloads / "Aiwa_widget.apk").stat().st_size, 3000000)
        self.assertEqual(sorted(p.name for p in downloads.iterdir()), ["Aiwa_widget.apk"])
        self.assertIn("Replaced, not duplicated", done.stdout)
        self.assertNotIn("other copies", done.stdout)

    def test_copies_made_by_something_else_are_reported_and_left_alone(self):
        downloads = self.home / "storage" / "downloads"
        downloads.mkdir(parents=True)
        (downloads / "Aiwa_widget (1).apk").write_bytes(b"from the browser")
        done = self.run_script()
        self.assertEqual((downloads / "Aiwa_widget (1).apk").read_bytes(), b"from the browser")
        self.assertIn("other copies", done.stdout)
        self.assertIn("Aiwa_widget (1).apk", done.stdout)

    def test_a_file_that_cannot_be_replaced_is_said_not_hidden(self):
        downloads = self.home / "storage" / "downloads"
        downloads.mkdir(parents=True)
        (downloads / "Aiwa_widget.apk").write_bytes(b"not ours to replace")
        done = self.run_script(STUB_CP="fail")   # the copy is refused, as Android may for a file another app made
        self.assertEqual(done.returncode, 0)
        self.assertIn("could not write Downloads/Aiwa_widget.apk", done.stdout)
        self.assertNotIn("Replaced, not duplicated", done.stdout)
        self.assertTrue((self.home / "aiwa-debug.apk").exists())

    def test_without_storage_access_it_asks_for_it_and_says_so(self):
        done = self.run_script()
        self.assertIn("termux-setup-storage", done.stdout)
        self.assertIn("cannot see the phone's Downloads folder", done.stdout)
        self.assertEqual(done.returncode, 0)

    def test_the_storage_permission_is_asked_for_and_the_copy_made_once_it_is_given(self):
        # a stand-in for termux-setup-storage that "grants" it: the Downloads folder appears
        stub = self.bin / "termux-setup-storage"
        stub.write_text('#!/bin/bash\nmkdir -p "$HOME/storage/downloads"\n')
        stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
        done = self.run_script()
        self.assertEqual((self.home / "storage" / "downloads" / "Aiwa_widget.apk").stat().st_size, 3000000)
        self.assertIn("open the Files app", done.stdout)

    def test_a_failed_download_is_said_and_keeps_the_previous_apk(self):
        old = self.home / "aiwa-debug.apk"
        old.write_bytes(b"previous")
        done = self.run_script(STUB_CURL="fail")
        self.assertEqual(done.returncode, 1)
        self.assertIn("NOT downloaded", done.stdout)
        self.assertEqual(old.read_bytes(), b"previous")
        self.assertFalse((self.home / "opened.txt").exists())

    def test_an_error_page_instead_of_an_apk_is_not_kept(self):
        done = self.run_script(STUB_BYTES="600")
        self.assertEqual(done.returncode, 1)
        self.assertIn("NOT downloaded", done.stdout)
        self.assertFalse((self.home / "aiwa-debug.apk").exists())
        self.assertFalse((self.home / "aiwa-debug.apk.part").exists())

    def test_an_installer_that_does_not_open_when_asked_says_so_and_points_to_the_files_app(self):
        (self.home / "storage" / "downloads").mkdir(parents=True)
        done = self.run_script("--open", STUB_OPEN="fail")
        self.assertEqual(done.returncode, 0)
        self.assertTrue((self.home / "aiwa-debug.apk").exists())
        self.assertIn("did not open from here", done.stdout)
        self.assertIn("Files app", done.stdout)


class ScriptSyntaxTests(unittest.TestCase):
    def test_every_termux_script_parses(self):
        for name in ("bootstrap.sh", "setup-termux-proot.sh", "install-apk.sh", "start.sh"):
            done = subprocess.run(["bash", "-n", str(BACKEND / name)], capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, f"{name}: {done.stderr}")

    def test_setup_stops_the_running_backend_before_starting_another(self):
        text = (BACKEND / "setup-termux-proot.sh").read_text()
        kill, start = text.index('pkill -f "[a]iwa_server.py"'), text.index("exec proot-distro login")
        self.assertLess(kill, start)


if __name__ == "__main__":
    unittest.main()
