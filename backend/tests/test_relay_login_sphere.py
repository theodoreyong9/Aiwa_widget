"""Tests of what comes back through the relay (a sphere, the relay test, the
"Claude waits" ping), of the Claude login the app drives, of the CI news behind the
widget's "Prêt" chip, and of the HTTP routes.

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
import re
import stat
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.request
from pathlib import Path

HOME = tempfile.mkdtemp(prefix="aiwa-test-home-")
os.environ["HOME"] = HOME  # before the import: the server builds its paths from it
BIN = Path(HOME) / "bin"
BIN.mkdir()
os.environ["PATH"] = f"{BIN}{os.pathsep}{os.environ['PATH']}"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import aiwa_github as gh  # noqa: E402
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


CONTRACT = "<!doctype html><html><head><title>Demo App</title></head><body><script type=\"module\">/* an Aiwa contract */</script></body></html>\n" + "<!-- pad -->\n" * 300


class AiwaContractTests(Base):
    """The "aiwa" mode: Claude sends a contract (one self-contained index.html, name.aiwa.html)."""

    def contract_event(self, event_id="c1", name="demo-app.aiwa.html", code=CONTRACT, **over):
        return self.sphere_event(event_id, name, code, **dict({"title": "aiwa-app"}, **over))

    def test_a_contract_sent_with_curl_is_kept_as_an_aiwa_contract(self):
        srv._sphere_received(self.contract_event(), kind="aiwa")
        snap = srv._sphere_snapshot()
        self.assertEqual((snap["name"], snap["kind"]), ("demo-app.aiwa.html", "aiwa"))
        self.assertEqual(srv._sphere_code(), {"ok": True, "name": "demo-app.aiwa.html", "code": CONTRACT, "kind": "aiwa"})

    def test_the_relay_tells_the_two_kinds_apart_by_title_or_by_file_name(self):
        srv._relay_event(self.contract_event("c1"))
        self.assertTrue(wait_for(lambda: (srv._sphere_snapshot() or {}).get("kind") == "aiwa"))
        srv._relay_event(self.sphere_event("s1"))
        self.assertTrue(wait_for(lambda: (srv._sphere_snapshot() or {}).get("kind") == "sphere"))
        untitled = self.contract_event("c2", name="other.aiwa.html")
        untitled.pop("title")
        srv._relay_event(untitled)
        self.assertTrue(wait_for(lambda: (srv._sphere_snapshot() or {}).get("name") == "other.aiwa.html"))
        self.assertEqual(srv._sphere_snapshot()["kind"], "aiwa")

    def test_the_name_falls_back_to_the_title_of_the_page(self):
        srv._sphere_received(self.contract_event(name="upload.bin", code="<title>My Token!</title><p>x"), kind="aiwa")
        self.assertEqual(srv._sphere_snapshot()["name"], "my-token.aiwa.html")
        srv._sphere_received(self.contract_event("c9", name="../../x", code="<p>no title here</p>"), kind="aiwa")
        self.assertEqual(srv._sphere_snapshot()["name"], "app.aiwa.html")

    def test_each_mode_only_lights_up_for_what_it_asked_for(self):
        srv._sphere_received(self.contract_event(), kind="aiwa")
        srv.deploy_mode = "aiwa"
        self.assertEqual(srv._site_snapshot(), {"url": None, "state": "live", "kind": "aiwa"})
        srv.deploy_mode = "sphere"
        self.assertEqual(srv._site_snapshot()["state"], "waiting")  # a contract is no answer to "sphere"
        srv._sphere_seen()
        srv.deploy_mode = "aiwa"
        self.assertEqual(srv._site_snapshot()["state"], "waiting")

    def test_the_two_kinds_are_pruned_separately(self):
        for n in range(13):
            srv._sphere_received(self.contract_event(f"c{n}", name=f"app{n}.aiwa.html"), kind="aiwa")
            time.sleep(0.01)
        for n in range(3):
            srv._sphere_received(self.sphere_event(f"s{n}", name=f"s{n}.sphere.js", code=SPHERE.replace("radio", f"s{n}")))
            time.sleep(0.01)
        self.assertEqual(len(list(srv.SPHERE_DIR.glob("*.aiwa.html"))), srv.SPHERE_KEEP)
        self.assertEqual(len(list(srv.SPHERE_DIR.glob("*.sphere.js"))), 3)

    def test_the_kind_survives_a_restart(self):
        srv._sphere_received(self.contract_event(), kind="aiwa")
        srv._save_state()
        srv.sphere = None
        srv._load_state()
        self.assertEqual(srv._sphere_snapshot()["kind"], "aiwa")

    def test_the_instruction_says_what_to_write_read_and_send(self):
        srv.deploy_mode = "aiwa"
        text = dict(srv._instruction_lines(None, None, None, True))["deploy"]
        for wanted in ("-T nom.aiwa.html", "Title: aiwa-app", f"{srv.NTFY_SERVER}/{srv.waiting_topic}", "YELLOWPAPER.md",
                       "Ne le pousse sur AUCUN dépôt", "defineContract",
                       # the contract runs on the Aiwa page, in an isolated frame: the shape it must have
                       "aiwa-contract-example.html", 'id="aiwa-logic"',
                       "mount(container, host)", "__AIWA_HOST__", "host.kind", "`.aiwa-` + le nom du fichier", "aiwa-icon"):
            self.assertIn(wanted, text)
        self.assertIn("raw.githubusercontent.com/theodoreyong9/aiwa_project/main/", text)
        for mode in ("none", "pages", "android", "sphere"):
            srv.deploy_mode = mode
            joined = " ".join(t for _, t in srv._instruction_lines("o/r", "w", "main", True))
            self.assertNotIn("aiwa-app", joined)


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
        # a rewrite in YourMine's format, profile parts included — not a copy of some source
        for wanted in ("réécriture", "Building a Sphere", "Profile as Infrastructure", "profileSection", "peerSection", "broadcastData"):
            self.assertIn(wanted, text)

    def test_the_account_repositories_are_offered_as_an_optional_reference(self):
        for repo in (None, "o/r"):
            for mode in ("none", "pages", "android", "sphere", "aiwa"):
                srv.deploy_mode = mode
                lines = dict(srv._instruction_lines(repo, "w", "main", True))
                self.assertIn("sources", lines, f"repo={repo} mode={mode}")
        text = dict(srv._instruction_lines(None, None, None, True))["sources"]
        for name, role in srv.REFERENCE_REPOS:
            self.assertIn(f"{name} = {role}", text)
        names = [name for name, _ in srv.REFERENCE_REPOS]
        self.assertEqual(len(names), len(set(names)))
        for wanted in ("FACULTATIVE", "ignore cette liste", "lecture seule", "github.com/theodoreyong9?tab=repositories",
                       "raw.githubusercontent.com/theodoreyong9/<dépôt>/main/README.md"):
            self.assertIn(wanted, text)
        # read-only: it must not contradict the rule that only the session's repositories are touched
        self.assertNotIn("add_repo", text)

    def test_the_reference_is_told_once_then_only_when_it_changes(self):
        srv.deploy_mode = "none"
        first, told = srv._compose({}, None, "w", "main", True)
        self.assertIn("Dépôts de référence", first)
        again, _ = srv._compose({"instr": told}, None, "w", "main", True)
        self.assertEqual(again, "")
        # a session from before this instruction existed gets it once, as an update, and nothing else repeated
        older = {k: v for k, v in told.items() if k != "sources"}
        update, _ = srv._compose({"instr": older}, None, "w", "main", True)
        self.assertIn("consignes mises à jour", update)
        self.assertIn("Dépôts de référence", update)
        self.assertNotIn("Alerte (obligatoire)", update)

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
        self.assertAlmostEqual(srv.last_message_at, time.time(), delta=60)  # the "Prêt" chip counts from here
        self.assertAlmostEqual(json.loads(Path(srv.STATE_FILE).read_text())["last_message"], time.time(), delta=60)

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


class FakeGithub(http.server.BaseHTTPRequestHandler):
    runs = []       # the workflow_runs the next request gets, newest first
    status = 200

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = json.dumps({"workflow_runs": self.runs}).encode()
        self.send_response(self.status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def run(run_id, sha, status="completed", conclusion="success", event="push", name="Build", title="a commit", actor="someone", updated="2026-09-30T05:17:06Z"):
    return {"id": run_id, "head_sha": sha, "status": status, "conclusion": conclusion if status == "completed" else None, "event": event,
            "name": name, "display_title": title, "actor": {"login": actor}, "updated_at": updated, "html_url": f"https://github.com/o/r/actions/runs/{run_id}"}


class CiNewsTests(unittest.TestCase):
    """The verdict on the latest commit's runs, and when it is news (the "Prêt" chip)."""

    @classmethod
    def setUpClass(cls):
        cls.api = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeGithub)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        gh.GITHUB_API = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()

    def setUp(self):
        FakeGithub.runs, FakeGithub.status = [], 200
        srv.current_repo = "o/r"
        srv.ci_seen.clear()
        srv.ci_announced.clear()
        srv.last_message_at = 0
        with srv.site_lock:
            srv.ci_cache.update(repo=None, info=None, at=0.0, busy=False)

    def look(self):
        """One look at the repository, as the backend does every 30 or 150 s."""
        with srv.site_lock:
            srv.ci_cache.update(repo="o/r", info=gh.latest_run("o/r"), at=time.time(), busy=False)
        return srv._ci_snapshot()

    def test_lookups_every_30_s_while_a_result_is_awaited_and_every_150_s_otherwise(self):
        now = 10_000.0
        idle, running, ok = None, {"state": "running"}, {"state": "success"}
        srv.last_message_at = 0
        self.assertEqual(srv._ci_ttl(ok, now), 150, "nothing awaited")
        self.assertEqual(srv._ci_ttl(idle, now), 150)
        self.assertEqual(srv._ci_ttl(running, now), 30, "a run is going")
        srv.last_message_at = int(now) - 60
        self.assertEqual(srv._ci_ttl(ok, now), 30, "a message was sent a minute ago: its result is awaited")
        srv.last_message_at = int(now) - 601
        self.assertEqual(srv._ci_ttl(ok, now), 150, "after 10 minutes it is not awaited any more")

    def test_never_more_than_50_lookups_an_hour_whatever_the_rhythm(self):
        now = 50_000.0
        with srv.site_lock:
            srv.ci_lookups[:] = [now - 10 * i for i in range(50)]      # 50 lookups in the last 500 s
            self.assertFalse(srv._ci_lookup_allowed(now), "the budget is spent")
            srv.ci_lookups[:] = [now - 3600 - 5] + [now - 10 * i for i in range(49)]
            self.assertTrue(srv._ci_lookup_allowed(now), "one fell out of the hour: there is room again")
            self.assertEqual(len(srv.ci_lookups), 49)
            srv.ci_lookups.clear()

    def test_the_runs_of_one_commit_are_one_verdict(self):
        FakeGithub.runs = [run(3, "S2", name="Pages", status="in_progress"), run(2, "S2", name="Build"), run(1, "S1")]
        self.assertEqual(gh.latest_run("o/r")["state"], "running")
        FakeGithub.runs = [run(3, "S2", name="Pages"), run(2, "S2", name="Build"), run(1, "S1")]
        verdict = gh.latest_run("o/r")
        self.assertEqual((verdict["state"], verdict["sha"]), ("success", "S2"))
        self.assertIn("Pages", verdict["detail"])

    def test_a_failed_run_of_the_commit_is_the_verdict_and_the_link(self):
        FakeGithub.runs = [run(3, "S2", name="Pages"), run(2, "S2", name="Build", conclusion="failure"), run(1, "S1")]
        verdict = gh.latest_run("o/r")
        self.assertEqual(verdict["state"], "failure")
        self.assertTrue(verdict["url"].endswith("/2"))

    def test_skipped_runs_do_not_spoil_a_green_commit(self):
        FakeGithub.runs = [run(2, "S2", conclusion="skipped"), run(1, "S2")]
        self.assertEqual(gh.latest_run("o/r")["state"], "success")

    def test_scheduled_and_dynamic_runs_are_not_news(self):
        FakeGithub.runs = [run(9, "X", event="schedule", name="Regenerate"), run(8, "Y", event="dynamic", name="pages build and deployment"), run(7, "S1")]
        self.assertEqual(gh.latest_run("o/r")["sha"], "S1")
        FakeGithub.runs = [run(9, "X", event="schedule")]
        self.assertEqual(gh.latest_run("o/r"), {"state": "none", "url": None})

    def test_an_unreadable_answer_is_not_a_verdict(self):
        FakeGithub.status = 500
        self.assertIsNone(gh.latest_run("o/r"))

    def test_the_first_commit_ever_seen_is_not_news(self):
        FakeGithub.runs = [run(1, "S1")]
        self.assertFalse(self.look()["fresh"])
        self.assertEqual(srv.ci_seen["o/r"], "S1")

    def test_a_new_green_commit_is_news_until_the_user_looked(self):
        FakeGithub.runs = [run(1, "S1")]
        self.look()
        FakeGithub.runs = [run(2, "S2"), run(1, "S1")]
        self.assertTrue(self.look()["fresh"])
        srv._ci_acknowledge()
        self.assertFalse(srv._ci_snapshot()["fresh"])

    def test_a_second_workflow_of_the_same_commit_is_not_news_again(self):
        FakeGithub.runs = [run(1, "S1")]
        self.look()
        FakeGithub.runs = [run(3, "S2", name="Pages", status="in_progress"), run(2, "S2", name="Build")]
        self.assertFalse(self.look()["fresh"])  # still running: not yet
        FakeGithub.runs = [run(3, "S2", name="Pages"), run(2, "S2", name="Build")]
        self.assertTrue(self.look()["fresh"])
        srv._ci_acknowledge()
        FakeGithub.runs = [run(4, "S2", name="Another", event="workflow_dispatch"), run(3, "S2", name="Pages"), run(2, "S2", name="Build")]
        self.assertFalse(self.look()["fresh"])  # the same commit: already looked at

    def test_a_red_commit_is_no_news_and_the_next_green_one_is(self):
        FakeGithub.runs = [run(1, "S1")]
        self.look()
        FakeGithub.runs = [run(2, "S2", conclusion="failure"), run(1, "S1")]
        self.assertFalse(self.look()["fresh"])
        FakeGithub.runs = [run(3, "S3"), run(2, "S2", conclusion="failure"), run(1, "S1")]
        self.assertTrue(self.look()["fresh"])

    def test_runs_finished_before_the_users_last_message_are_not_news(self):
        FakeGithub.runs = [run(1, "S1")]
        self.look()
        srv.last_message_at = gh._epoch("2026-09-30T06:00:00Z")
        FakeGithub.runs = [run(2, "S2", updated="2026-09-30T05:00:00Z"), run(1, "S1")]
        self.assertFalse(self.look()["fresh"])
        FakeGithub.runs = [run(3, "S3", updated="2026-09-30T06:30:00Z"), run(2, "S2"), run(1, "S1")]
        self.assertTrue(self.look()["fresh"])

    def test_a_run_id_kept_by_an_older_version_does_not_make_a_false_news(self):
        Path(srv.STATE_FILE).write_text(json.dumps({"repo": "o/r", "ci_seen": {"o/r": 123456, "x/y": "abc"}}))
        srv._load_state()
        self.assertEqual(srv.ci_seen, {"x/y": "abc"})
        FakeGithub.runs = [run(1, "S1")]
        self.assertFalse(self.look()["fresh"])

    def test_the_detail_says_which_run_it_is(self):
        FakeGithub.runs = [run(1, "S1", name="Build Aiwa APK", title="Sphere instruction", event="push", actor="theodoreyong9")]
        self.assertEqual(gh.latest_run("o/r")["detail"], "Build Aiwa APK — Sphere instruction (push, theodoreyong9)")


class RepoListTests(unittest.TestCase):
    """The repository picker's list, and a repository created a moment ago ("Créer un dépôt GitHub") showing up in it."""

    def setUp(self):
        gh._owner_cache.clear()
        with srv.site_lock:
            srv.ci_lookups.clear()
        self.reads = []
        self.listing = [{"full_name": "me/old"}]

        class Reply:
            def __init__(inner, data):
                inner.data = data

            def __enter__(inner):
                return inner

            def __exit__(inner, *args):
                return False

            def read(inner, *args):
                return json.dumps(inner.data).encode()

        def fake_urlopen(request, timeout=None):
            self.reads.append(request.full_url)
            return Reply(self.listing)

        self.patch = unittest.mock.patch.object(gh.urllib.request, "urlopen", fake_urlopen)
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        gh._owner_cache.clear()
        with srv.site_lock:
            srv.ci_lookups.clear()

    def test_a_new_repository_is_in_the_list_when_the_picker_is_opened_again(self):
        self.assertEqual([r["name"] for r in srv._owner_repos_current("me")], ["me/old"])
        self.listing = [{"full_name": "me/new"}, {"full_name": "me/old"}]
        self.assertEqual([r["name"] for r in srv._owner_repos_current("me")], ["me/old"], "opened twice within 30 s: no new request")
        self.assertEqual(len(self.reads), 1)
        gh._owner_cache["me"] = (time.time() - 31, gh._owner_cache["me"][1])
        self.assertEqual([r["name"] for r in srv._owner_repos_current("me")], ["me/new", "me/old"], "30 s later it is read again")
        self.assertEqual(len(self.reads), 2)

    def test_the_re_reads_count_in_the_hourly_budget_and_stop_when_it_is_spent(self):
        srv._owner_repos_current("me")
        self.assertEqual(len(srv.ci_lookups), 1, "a read is a request of the unauthenticated API: counted")
        now = time.time()
        with srv.site_lock:
            srv.ci_lookups[:] = [now - 10 * i for i in range(srv.CI_LOOKUPS_PER_HOUR)]
        gh._owner_cache["me"] = (now - 60, gh._owner_cache["me"][1])
        self.listing = [{"full_name": "me/new"}]
        self.assertEqual([r["name"] for r in srv._owner_repos_current("me")], ["me/old"], "out of budget: the list already held is used")
        self.assertEqual(len(self.reads), 1)


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

    def test_a_contract_is_served_as_it_was_sent_whatever_the_query(self):
        page = "<!doctype html><title>Demo</title><script type=\"module\" id=\"aiwa-logic\">export function mount(){}</script>"
        srv._sphere_received(self.sphere_event("c1", "demo-app.aiwa.html", page, title="aiwa-app"), kind="aiwa")
        served = self.call("/api/sphere/code")
        self.assertEqual((served["name"], served["kind"], served["code"]), ("demo-app.aiwa.html", "aiwa", page))

    def test_deploy_mode_aiwa_is_accepted(self):
        self.call("/api/options", json.dumps({"deploy": "aiwa"}))
        self.assertEqual(self.call("/api/status")["deploy"], "aiwa")
        self.assertEqual(self.call("/api/status")["site"]["kind"], "aiwa")
        self.call("/api/options", json.dumps({"deploy": "none"}))

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
