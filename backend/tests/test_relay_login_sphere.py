"""Tests of what comes back through the relay (a sphere, the relay test, the
"Claude waits" ping), of the Claude login the app drives, and of the HTTP routes.

No network and no real `claude`: a fake ntfy server and a fake `claude` script stand
in for them. What the fakes copy was seen with the real ones — the real CLI prints
the login address inside terminal escape codes, then "Paste code here if prompted >",
and answers a refused code with "Login failed: Request failed with status code 400" and
exits; ntfy documents that a file sent with a PUT becomes an attachment whose message
event carries {name, url, size}. Run: python3 -m unittest discover -s backend/tests
"""
import http.server
import json
import os
import stat
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from pathlib import Path

HOME = tempfile.mkdtemp(prefix="aiwa-test-home-")
os.environ["HOME"] = HOME  # before the import: the server builds its paths from it
BIN = Path(HOME) / "bin"
BIN.mkdir()
os.environ["PATH"] = f"{BIN}{os.pathsep}{os.environ['PATH']}"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import aiwa_server as srv  # noqa: E402

FAKE_CLAUDE = r'''#!/usr/bin/env python3
import os, sys
marker = os.path.expanduser("~/.fake_logged_in")
if sys.argv[1:3] == ["auth", "status"]:
    print('{\n  "loggedIn": %s\n}' % ("true" if os.path.exists(marker) else "false"))
elif sys.argv[1:3] == ["auth", "login"]:
    print("Opening browser to sign in…")
    url = "https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=abc"
    print("If the browser didn't open, visit: \x1b]8;;" + url + "\x07\x1b[94m" + url + "\x1b[39m\x1b]8;;\x07")
    sys.stdout.write("Paste code here if prompted > ")
    sys.stdout.flush()
    line = sys.stdin.readline().strip()
    if line == "good#state":
        open(marker, "w").close()
        print("Login successful")
        sys.exit(0)
    print("Login failed: Request failed with status code 400")
    sys.exit(1)
elif "--cloud" in sys.argv:
    import json
    # Creation and follow-up (the /rename queued after a creation) run at the same time: two files.
    name = "last_followup_args.json" if "-p" in sys.argv else "last_create_args.json"
    open(os.path.expanduser("~/" + name), "w").write(json.dumps({"argv": sys.argv[1:]}))
    if os.environ.get("FAKE_CLAUDE_MODE") == "fail":
        print("Error: Not logged in · Please run /login")
        sys.exit(1)
    if "-p" in sys.argv:
        print('{"ok": true, "session_id": "session_TEST123abc", "url": "https://claude.ai/code/session_TEST123abc"}')
    else:
        print("Session created: session_TEST123abc https://claude.ai/code/session_TEST123abc")
'''
(BIN / "claude").write_text(FAKE_CLAUDE)
(BIN / "claude").chmod((BIN / "claude").stat().st_mode | stat.S_IEXEC)

SPHERE = "(function(){window.YM_S=window.YM_S||{};window.YM_S['radio.sphere.js']={name:'Radio'};})();\n" + "// pad\n" * 900


class FakeNtfy(http.server.BaseHTTPRequestHandler):
    files = {}      # /file/<id> -> bytes
    stream = []     # the events the next /json request gets

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.startswith("/file/"):
            body = self.files.get(self.path[len("/file/"):])
            if body is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif "/json" in self.path:
            events, FakeNtfy.stream = list(FakeNtfy.stream), []
            body = "".join(json.dumps(e) + "\n" for e in events).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)


def wait_for(condition, seconds=10):
    end = time.time() + seconds
    while time.time() < end:
        if condition():
            return True
        time.sleep(0.05)
    return False


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ntfy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeNtfy)
        threading.Thread(target=cls.ntfy.serve_forever, daemon=True).start()
        srv.NTFY_SERVER = f"http://127.0.0.1:{cls.ntfy.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.ntfy.shutdown()

    def setUp(self):
        srv.waiting_topic = "aiwa-" + "ab" * 12
        srv.sphere = None
        srv.deploy_mode = "none"
        srv.current_repo = None
        srv.current_cloud = None
        srv.relay_cloud.update(asked=None, ok=None)
        srv.relay_seen.update(id=None, time=None)
        srv.claude_login.update(state="unknown", at=0.0, busy=False)
        srv._clear_waiting()
        FakeNtfy.files.clear()
        FakeNtfy.stream = []
        for old in srv.SPHERE_DIR.glob("*"):
            old.unlink()

    def sphere_event(self, event_id="e1", name="radio.sphere.js", code=SPHERE, **over):
        FakeNtfy.files[event_id] = code.encode()
        event = {
            "id": event_id, "time": int(time.time()), "event": "message", "topic": srv.waiting_topic,
            "title": "aiwa-sphere", "message": f"You received a file: {name}",
            "attachment": {"name": name, "url": f"{srv.NTFY_SERVER}/file/{event_id}", "size": len(code.encode())},
        }
        event.update(over)
        return event


class SphereTests(Base):
    def test_a_sphere_sent_with_curl_is_downloaded_and_kept(self):
        srv._sphere_received(self.sphere_event())
        self.assertEqual(srv._sphere_snapshot()["name"], "radio.sphere.js")
        self.assertFalse(srv._sphere_snapshot()["seen"])
        got = srv._sphere_code()
        self.assertTrue(got["ok"])
        self.assertEqual(got["code"], SPHERE)
        self.assertEqual((srv.SPHERE_DIR / "radio.sphere.js").read_text(), SPHERE)

    def test_the_name_falls_back_to_the_key_the_sphere_registers(self):
        srv._sphere_received(self.sphere_event(name="upload.bin"))
        self.assertEqual(srv._sphere_snapshot()["name"], "radio.sphere.js")

    def test_a_dangerous_name_is_never_used(self):
        srv._sphere_received(self.sphere_event(name="../../etc/passwd.sphere.js", code="no key in here"))
        self.assertEqual(srv._sphere_snapshot()["name"], "sphere.sphere.js")
        self.assertFalse(Path(HOME, "..", "etc").exists())

    def test_an_attachment_from_another_host_is_not_fetched(self):
        event = self.sphere_event()
        event["attachment"]["url"] = "http://127.0.0.1:1/file/e1"
        srv._sphere_received(event)
        self.assertIsNone(srv._sphere_snapshot())

    def test_a_too_big_or_empty_or_binary_file_is_not_kept(self):
        big = self.sphere_event("big")
        big["attachment"]["size"] = srv.SPHERE_MAX + 1
        srv._sphere_received(big)
        binary = self.sphere_event("bin")
        FakeNtfy.files["bin"] = b"\xff\xfe\x00\x01"  # after the helper, which stores a sphere under that id
        binary["attachment"]["size"] = 4
        srv._sphere_received(binary)
        srv._sphere_received(self.sphere_event("empty", code="  \n"))
        self.assertIsNone(srv._sphere_snapshot())

    def test_a_small_sphere_sent_as_a_plain_message_is_kept_too(self):
        srv._relay_event({"id": "m1", "time": int(time.time()), "event": "message", "title": "aiwa-sphere", "message": "window.YM_S['tiny.sphere.js']={name:'Tiny'};"})
        self.assertTrue(wait_for(lambda: srv._sphere_snapshot() is not None))
        self.assertEqual(srv._sphere_snapshot()["name"], "tiny.sphere.js")

    def test_only_the_last_ten_are_kept(self):
        for n in range(13):
            srv._sphere_received(self.sphere_event(f"e{n}", name=f"s{n}.sphere.js", code=SPHERE.replace("radio", f"s{n}")))
            time.sleep(0.01)
        self.assertEqual(len(list(srv.SPHERE_DIR.glob("*.sphere.js"))), srv.SPHERE_KEEP)
        self.assertEqual(srv._sphere_snapshot()["name"], "s12.sphere.js")

    def test_seen_clears_the_widget_state(self):
        srv.deploy_mode = "sphere"
        self.assertEqual(srv._site_snapshot(), {"url": None, "state": "waiting", "kind": "sphere"})
        srv._sphere_received(self.sphere_event())
        self.assertEqual(srv._site_snapshot()["state"], "live")
        srv._sphere_seen()
        self.assertEqual(srv._site_snapshot()["state"], "waiting")

    def test_it_survives_a_restart(self):
        srv._sphere_received(self.sphere_event())
        srv._cloud_check_seen()
        srv._save_state()
        srv.sphere = None
        srv.relay_cloud.update(asked=None, ok=None)
        srv._load_state()
        self.assertEqual(srv._sphere_snapshot()["name"], "radio.sphere.js")
        self.assertEqual(srv._relay_cloud_state(), "ok")


class RelayTests(Base):
    def event(self, message="attend", **over):
        event = {"id": f"id{time.time_ns()}", "time": int(time.time()), "event": "message", "message": message}
        event.update(over)
        return event

    def test_the_relay_test_goes_untested_pending_missing_then_ok(self):
        self.assertEqual(srv._relay_cloud_state(), "untested")
        text = srv._relay_check_text()
        self.assertIn("Title: aiwa-check", text)
        self.assertIn(srv.waiting_topic, text)
        self.assertEqual(srv._relay_cloud_state(), "pending")
        srv.relay_cloud["asked"] -= srv.CLOUD_CHECK_WAIT + 1
        self.assertEqual(srv._relay_cloud_state(), "missing")
        srv._relay_event(self.event("ok", title="aiwa-check"))
        self.assertEqual(srv._relay_cloud_state(), "ok")
        self.assertEqual(srv._relay_check_text(), "")  # confirmed: nothing more to ask
        self.assertIn("Title: aiwa-check", srv._relay_check_text(force=True))  # unless asked again

    def test_claudes_ping_proves_the_cloud_reaches_the_relay_and_wakes_the_alert(self):
        srv._relay_event(self.event("attend"))
        self.assertEqual(srv._relay_cloud_state(), "ok")
        self.assertIsNotNone(srv.waiting["since"])

    def test_the_apps_own_test_ping_proves_only_the_phone_side(self):
        srv._relay_event(self.event("test"))
        self.assertEqual(srv._relay_cloud_state(), "untested")
        self.assertIsNotNone(srv.waiting["since"])

    def test_an_old_ping_replayed_after_a_restart_does_not_wake_the_alert(self):
        srv._relay_event(self.event("attend", time=int(time.time()) - 600))
        self.assertIsNone(srv.waiting["since"])

    def test_the_last_event_replayed_is_ignored(self):
        first = self.event("attend")
        srv._relay_event(first)
        srv._clear_waiting()
        srv._relay_event(dict(first))
        self.assertIsNone(srv.waiting["since"])

    def test_the_listener_reads_a_stream_and_gets_a_sphere_through(self):
        FakeNtfy.stream = [self.sphere_event("live1"), self.event("ok", title="aiwa-check")]
        threading.Thread(target=srv._relay_listener, daemon=True).start()
        self.assertTrue(wait_for(lambda: srv._sphere_snapshot() is not None and srv._relay_cloud_state() == "ok"))
        self.assertEqual(srv._sphere_code()["code"], SPHERE)

    def test_a_login_problem_in_a_failed_send_makes_the_widget_ask_for_it(self):
        srv._note_login_problem("Error: Not logged in · Please run /login")
        self.assertEqual(srv._login_state(), "needed")
        srv._set_login_state("ok")
        srv._note_login_problem("git push failed: permission denied")
        self.assertEqual(srv.claude_login["state"], "ok")


class InstructionTests(Base):
    def test_sphere_mode_tells_claude_what_to_send_and_where(self):
        srv.deploy_mode = "sphere"
        text = dict(srv._instruction_lines(None, None, None, True))["deploy"]
        self.assertIn("-T nom.sphere.js", text)
        self.assertIn("Title: aiwa-sphere", text)
        self.assertIn(f"{srv.NTFY_SERVER}/{srv.waiting_topic}", text)
        self.assertIn("Ne la pousse sur AUCUN dépôt", text)
        self.assertIn(srv.SPHERE_README, text)

    def test_other_modes_do_not_mention_spheres(self):
        for mode in ("none", "pages", "android"):
            srv.deploy_mode = mode
            joined = " ".join(t for _, t in srv._instruction_lines("o/r", "w", "main", True))
            self.assertNotIn("aiwa-sphere", joined)


class LoginTests(Base):
    def tearDown(self):
        srv._login_cancel()
        Path(HOME, ".fake_logged_in").unlink(missing_ok=True)

    def test_status_reads_the_cli(self):
        self.assertIs(srv._claude_auth_status(), False)
        Path(HOME, ".fake_logged_in").touch()
        self.assertIs(srv._claude_auth_status(), True)

    def test_the_address_is_found_inside_the_escape_codes(self):
        snapshot = srv._login_start()
        self.assertEqual(snapshot["phase"], "url")
        self.assertEqual(snapshot["url"], "https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=abc")

    def test_a_refused_code_ends_the_login_with_the_clis_words(self):
        srv._login_start()
        snapshot = srv._login_code("bad#code")
        self.assertEqual(snapshot["phase"], "failed")
        self.assertEqual(snapshot["message"], "Login failed: Request failed with status code 400")
        self.assertEqual(srv._login_code("good#state")["message"], "Aucune connexion en cours : ouvre d'abord la page de connexion.")

    def test_a_good_code_logs_in(self):
        srv._login_start()
        snapshot = srv._login_code("good#state")
        self.assertEqual(snapshot["phase"], "done")
        self.assertEqual(srv.claude_login["state"], "ok")
        self.assertIs(srv._claude_auth_status(), True)

    def test_a_code_with_spaces_is_refused_without_touching_the_login(self):
        srv._login_start()
        snapshot = srv._login_code("two words")
        self.assertEqual(snapshot["phase"], "url")
        self.assertIn("sans espace", snapshot["message"])

    def test_cancel_stops_the_cli(self):
        srv._login_start()
        proc = srv.login_flow["proc"]
        srv._login_cancel()
        self.assertTrue(wait_for(lambda: proc.poll() is not None))
        self.assertEqual(srv._login_snapshot()["phase"], "idle")


class CloudSendTests(Base):
    """cloud_send with the fake CLI: what a new session's first message carries, and what a
    failure does to the login and relay states."""

    def setUp(self):
        super().setUp()
        srv.cloud_busy = False
        srv.current_model = srv.current_effort = None
        srv.last_cloud = None
        os.environ.pop("FAKE_CLAUDE_MODE", None)
        Path(HOME, "last_create_args.json").unlink(missing_ok=True)

    def tearDown(self):
        os.environ.pop("FAKE_CLAUDE_MODE", None)

    def sent(self):
        return json.loads(Path(HOME, "last_create_args.json").read_text())

    def test_a_new_sessions_first_message_carries_the_relay_test_once(self):
        answer = srv.cloud_send("bonjour")
        self.assertTrue(answer["ok"], answer)
        self.assertEqual(answer["session_id"], "session_TEST123abc")
        task = self.sent()["argv"][-1]
        self.assertIn("bonjour", task)
        self.assertIn("Title: aiwa-check", task)
        self.assertEqual(srv._relay_cloud_state(), "pending")
        self.assertEqual(srv.claude_login["state"], "ok")  # a send that worked proves the login

    def test_once_the_relay_is_confirmed_a_new_session_does_not_ask_again(self):
        srv._cloud_check_seen()
        self.assertTrue(srv.cloud_send("bonjour")["ok"])
        self.assertNotIn("aiwa-check", self.sent()["argv"][-1])

    def test_a_failed_creation_because_of_the_login_says_so_and_asks_nothing(self):
        os.environ["FAKE_CLAUDE_MODE"] = "fail"
        answer = srv.cloud_send("bonjour")
        self.assertFalse(answer["ok"])
        self.assertEqual(srv.claude_login["state"], "needed")
        self.assertEqual(srv._relay_cloud_state(), "untested")  # no session: the test was not asked

    def test_a_failed_follow_up_because_of_the_login_says_so_too(self):
        srv.current_cloud = "session_TEST123abc"
        os.environ["FAKE_CLAUDE_MODE"] = "fail"
        self.assertFalse(srv.cloud_send("encore")["ok"])
        self.assertEqual(srv.claude_login["state"], "needed")

    def test_the_sphere_instruction_goes_with_the_first_message(self):
        srv.deploy_mode = "sphere"
        self.assertTrue(srv.cloud_send("fais une sphère météo")["ok"])
        task = self.sent()["argv"][-1]
        self.assertIn("Title: aiwa-sphere", task)
        self.assertIn(f"{srv.NTFY_SERVER}/{srv.waiting_topic}", task)


class HttpTests(Base):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.api = http.server.ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()
        super().tearDownClass()

    def call(self, path, body=None):
        url = f"http://127.0.0.1:{self.api.server_address[1]}{path}"
        request = urllib.request.Request(url, data=None if body is None else body.encode(), method="GET" if body is None else "POST")
        with urllib.request.urlopen(request, timeout=60) as reply:
            return json.loads(reply.read())

    def test_status_carries_the_new_fields(self):
        status = self.call("/api/status")
        self.assertEqual(status["version"], srv.BACKEND_VERSION)
        for key in ("claude_login", "relay_cloud", "sphere"):
            self.assertIn(key, status)
        self.assertEqual(status["relay_cloud"], "untested")

    def test_the_sphere_reaches_the_app_and_is_marked_seen(self):
        self.assertIsNone(self.call("/api/sphere")["sphere"])
        self.assertFalse(self.call("/api/sphere/code")["ok"])
        srv._sphere_received(self.sphere_event())
        self.assertEqual(self.call("/api/status")["sphere"]["name"], "radio.sphere.js")
        self.assertEqual(self.call("/api/sphere/code")["code"], SPHERE)
        self.call("/api/sphere/seen", "")
        self.assertTrue(self.call("/api/status")["sphere"]["seen"])

    def test_deploy_mode_sphere_is_accepted(self):
        self.call("/api/options", json.dumps({"deploy": "sphere"}))
        self.assertEqual(self.call("/api/status")["deploy"], "sphere")
        self.assertEqual(self.call("/api/status")["site"]["kind"], "sphere")

    def test_retest_without_a_session_says_so_and_asks_nothing(self):
        answer = self.call("/api/relay/retest", "")
        self.assertFalse(answer["accepted"])
        self.assertIn("aucune session", answer["reason"])
        self.assertEqual(self.call("/api/status")["relay_cloud"], "untested")

    def test_login_over_http(self):
        started = self.call("/api/claude/login/start", "")
        self.assertEqual(started["phase"], "url")
        self.assertEqual(self.call("/api/claude/login")["phase"], "url")
        done = self.call("/api/claude/login/code", "good#state")
        self.assertEqual(done["phase"], "done")
        self.assertEqual(self.call("/api/claude/check", "")["claude_login"], "ok")


if __name__ == "__main__":
    unittest.main()
