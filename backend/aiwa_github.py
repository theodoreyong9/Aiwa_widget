"""GitHub side of the Aiwa backend: everything that needs the `gh` CLI.

Aiwa uses the `gh` login the USER made themselves (`gh auth login -s
repo`, approved on github.com by them), separate from anything Claude
holds. It is used to list the account's repositories and to clone the
chosen one (so `claude --cloud` starts the cloud session on it). Claude's
OWN access to GitHub is deliberately not touched here: the user grants it
themselves (claude.ai/connect-github, or `/web-setup` typed and confirmed
in a terminal) — Aiwa never starts an authorization or confirms a token
hand-over on the user's behalf.

Nothing here is verified on a real phone: every function fails with a
GithubError carrying the real message, which the app shows.
"""
import io
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import tarfile
import threading
import time
import urllib.request
from pathlib import Path

AIWA_HOME = Path(os.environ.get("AIWA_HOME") or Path.home() / ".aiwa")
GH_BIN_DIR = AIWA_HOME / "bin"
REPOS_DIR = Path.home() / "repos"
GH_LOG = Path.home() / "aiwa_github_last.log"
# Overridable so the clone path can be tested without the network.
GITHUB_BASE = os.environ.get("AIWA_GITHUB_BASE", "https://github.com")
REPO_RE = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")

# Terminal escape sequences: CSI, OSC, and the two-byte kind (ESC 7 / ESC 8
# save/restore the cursor — those once left "78" in front of an error).
ANSI_RE = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[ -/]*[0-~])")
CTRL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")

# The user connects `gh` themselves, once, with their own approval on
# github.com — Aiwa never starts that authorization for them.
NOT_CONNECTED = (
    "GitHub n'est pas connecté. Une seule fois, dans Termux : "
    "proot-distro login ubuntu -- bash -lc 'apt-get install -y gh; gh auth login -s repo'"
)


class GithubError(Exception):
    pass


class NotFoundError(GithubError):
    pass


def clean(text):
    return CTRL_RE.sub("", ANSI_RE.sub("", text).replace("\r", "\n"))


def _log(kind, text):
    try:
        GH_LOG.write_text(f"[{time.strftime('%H:%M:%S')}] {kind}\n{text[-20000:]}\n", encoding="utf-8")
    except OSError:
        pass


def gh_env():
    env = dict(os.environ)
    env["PATH"] = f"{GH_BIN_DIR}{os.pathsep}{env.get('PATH', '')}"
    env.update({
        "GH_PROMPT_DISABLED": "1",
        "GH_NO_UPDATE_NOTIFIER": "1",
        "GH_SPINNER_DISABLED": "1",
        "NO_COLOR": "1",
        "GIT_TERMINAL_PROMPT": "0",
    })
    if env.get("TERM", "dumb") in ("", "dumb"):
        env["TERM"] = "xterm-256color"
    return env


def gh_path():
    return shutil.which("gh", path=gh_env()["PATH"])


_install_lock = threading.Lock()


def ensure_gh():
    """Path of the `gh` binary, downloading the official release into
    ~/.aiwa/bin when it isn't installed (no apt, no root needed)."""
    with _install_lock:
        found = gh_path()
        if found:
            return found
        arch = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "amd64", "amd64": "amd64"}.get(platform.machine().lower())
        if arch is None:
            raise GithubError(f"architecture non prise en charge pour gh : {platform.machine()}")
        try:
            with urllib.request.urlopen("https://api.github.com/repos/cli/cli/releases/latest", timeout=30) as reply:
                meta = json.load(reply)
            asset = next(a for a in meta["assets"] if a["name"].endswith(f"_linux_{arch}.tar.gz"))
            with urllib.request.urlopen(asset["browser_download_url"], timeout=180) as reply:
                archive = reply.read()
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
                member = next(m for m in tar.getmembers() if m.name.endswith("/bin/gh"))
                blob = tar.extractfile(member).read()
        except (OSError, StopIteration, KeyError, ValueError, tarfile.TarError) as err:
            raise GithubError(f"impossible d'installer gh : {err}")
        GH_BIN_DIR.mkdir(parents=True, exist_ok=True)
        target = GH_BIN_DIR / "gh"
        partial = GH_BIN_DIR / "gh.partial"
        partial.write_bytes(blob)
        partial.chmod(0o755)
        partial.replace(target)
        return str(target)


def run_gh(args, input_text=None, timeout=60):
    gh = ensure_gh()
    try:
        done = subprocess.run([gh] + args, input=input_text, capture_output=True, text=True, timeout=timeout, env=gh_env())
    except (OSError, subprocess.TimeoutExpired) as err:
        raise GithubError(f"gh {' '.join(args[:2])} : {err}")
    if done.returncode != 0:
        message = clean(done.stderr or done.stdout).strip()
        if "gh auth login" in message:
            raise GithubError(NOT_CONNECTED)
        if "HTTP 404" in message or "Not Found" in message:
            raise NotFoundError(message[-300:])
        raise GithubError(message[-400:] or f"gh a échoué (code {done.returncode})")
    return done.stdout


def gh_json(args, input_text=None, timeout=60):
    out = run_gh(args, input_text, timeout)
    try:
        return json.loads(out) if out.strip() else None
    except ValueError as err:
        raise GithubError(f"réponse GitHub illisible : {err}")


# --- account ----------------------------------------------------------------

_acct = {"login": None, "at": 0.0, "busy": False}


def _refresh_account():
    try:
        if gh_path() is None:
            _acct["login"] = None
        else:
            _acct["login"] = run_gh(["api", "user", "--jq", ".login"], timeout=20).strip() or None
    except GithubError:
        _acct["login"] = None
    _acct["at"] = time.time()
    _acct["busy"] = False


def account_snapshot():
    """The connected GitHub login, from a cache refreshed in the
    background (this is called on every /api/status)."""
    if time.time() - _acct["at"] > 120 and not _acct["busy"]:
        _acct["busy"] = True
        threading.Thread(target=_refresh_account, daemon=True).start()
    return _acct["login"]


def account_now():
    _refresh_account()
    return _acct["login"]


# --- repositories -----------------------------------------------------------

_repos_cache = {"at": 0.0, "data": []}


def list_repos():
    """Repositories the login can push to, most recently pushed first."""
    if time.time() - _repos_cache["at"] < 60 and _repos_cache["data"]:
        return _repos_cache["data"]
    data = gh_json(["api", "user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member"], timeout=60) or []
    repos = [
        {"name": r["full_name"], "private": bool(r.get("private")), "branch": r.get("default_branch") or "main"}
        for r in data
        if (r.get("permissions") or {}).get("push", True)
    ]
    _repos_cache.update(at=time.time(), data=repos)
    return repos


def repo_info(repo):
    info = gh_json(["api", f"repos/{repo}"]) or {}
    return {"default_branch": info.get("default_branch") or "main", "private": bool(info.get("private"))}


def prepare_clone(repo, push_main):
    """Local clone `claude --cloud` is started from (the cloud session
    clones the same GitHub repository at the branch checked out here).
    push_main: the default branch itself; otherwise a fresh aiwa/<date>
    branch, pushed so it exists on GitHub. Returns (directory, branch)."""
    gh = ensure_gh()
    default = repo_info(repo)["default_branch"]
    dest = REPOS_DIR / repo.replace("/", "__")
    git = ["git", "-c", "credential.helper=", "-c", f"credential.helper=!{shlex.quote(gh)} auth git-credential"]

    def run_git(args, cwd=None):
        try:
            done = subprocess.run(git + args, cwd=cwd, capture_output=True, text=True, timeout=300, env=gh_env())
        except (OSError, subprocess.TimeoutExpired) as err:
            raise GithubError(f"git {args[0]} : {err}")
        if done.returncode != 0:
            raise GithubError(f"git {args[0]} : " + clean(done.stderr or done.stdout).strip()[-300:])

    REPOS_DIR.mkdir(parents=True, exist_ok=True)
    if not (dest / ".git").exists():
        run_git(["clone", "--depth", "1", "--branch", default, f"{GITHUB_BASE}/{repo}.git", str(dest)])
    else:
        run_git(["fetch", "--depth", "1", "origin", default], cwd=dest)
        run_git(["checkout", "-q", "-f", "-B", default, f"origin/{default}"], cwd=dest)
    if push_main:
        return dest, default
    branch = "aiwa/" + time.strftime("%Y%m%d-%H%M%S")
    run_git(["checkout", "-q", "-b", branch], cwd=dest)
    run_git(["push", "-u", "origin", branch], cwd=dest)
    return dest, branch
