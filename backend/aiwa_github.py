"""GitHub helpers for the Aiwa backend.

Claude Code does the real work (clone, edit, commit, push, deploy) with its
OWN GitHub access, which the user grants once at claude.ai/connect-github.
What Aiwa does is small:

 - remember which repositories to offer, and start `claude --cloud` from a
   directory whose `origin` is the chosen one (the cloud session clones that
   GitHub remote itself);
 - turn the user's switches into instructions added to the conversation
   (that is aiwa_server._compose);
 - check whether a GitHub Pages address answers, so a link can appear.

Aiwa never logs in to GitHub, never calls its API and never asks for a
token. If the user happens to have the `gh` CLI logged in already, it only
adds their repositories to the list and lets a private repository be cloned
here; without it everything still works.

Nothing here is verified on a real phone.
"""
import json
import os
import re
import shlex
import shutil
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

REPOS_DIR = Path.home() / "repos"
# Overridable so the clone path can be tested without the network.
GITHUB_BASE = os.environ.get("AIWA_GITHUB_BASE", "https://github.com")
REPO_RE = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
_URL_RE = re.compile(r"github\.com[/:]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:[/?#\s]|$)")

# Terminal escape sequences: CSI, OSC, and the two-byte kind (ESC 7 / ESC 8
# save/restore the cursor — those once left "78" in front of an error).
ANSI_RE = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[ -/]*[0-~])")
CTRL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")


class GithubError(Exception):
    pass


def clean(text):
    return CTRL_RE.sub("", ANSI_RE.sub("", text).replace("\r", "\n"))


def parse_repo(text):
    """owner/name from a GitHub link, a git remote or a bare owner/name."""
    text = text.strip()
    match = _URL_RE.search(text)
    if match:
        repo = f"{match.group(1)}/{match.group(2)}"
    else:
        repo = text[:-4] if text.endswith(".git") else text
    return repo if REPO_RE.fullmatch(repo) else None


def pages_url(repo):
    """Where GitHub Pages publishes a repository (no custom domain)."""
    owner, name = repo.split("/", 1)
    host = f"{owner.lower()}.github.io"
    return f"https://{host}/" if name.lower() == host else f"https://{host}/{name}/"


def site_answers(url):
    """Whether the address answers with a success — a plain web request,
    no GitHub credentials involved."""
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "aiwa"})
        with urllib.request.urlopen(request, timeout=8) as reply:
            return 200 <= reply.status < 300
    except (OSError, ValueError):
        return False


# --- optional: an existing `gh` login -----------------------------------------

def gh_path():
    return shutil.which("gh")


_acct = {"login": None, "at": 0.0, "busy": False}


def _refresh_account():
    login = None
    gh = gh_path()
    if gh:
        try:
            done = subprocess.run([gh, "api", "user", "--jq", ".login"], capture_output=True, text=True, timeout=20, env=_env())
            if done.returncode == 0:
                login = done.stdout.strip() or None
        except (OSError, subprocess.TimeoutExpired):
            pass
    _acct.update(login=login, at=time.time(), busy=False)


def _gh_login():
    """The login of an already-connected `gh`, from a cache refreshed in the
    background (None until the first refresh finishes, or without `gh`)."""
    if gh_path() and time.time() - _acct["at"] > 300 and not _acct["busy"]:
        _acct["busy"] = True
        threading.Thread(target=_refresh_account, daemon=True).start()
    return _acct["login"]


def gh_repos():
    """The repositories a connected `gh` can push to; [] without one."""
    gh = gh_path()
    if not gh or not _gh_login():
        return []
    try:
        done = subprocess.run(
            [gh, "api", "user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member"],
            capture_output=True, text=True, timeout=60, env=_env(),
        )
        data = json.loads(done.stdout) if done.returncode == 0 else []
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return []
    return [
        {"name": r["full_name"], "private": bool(r.get("private"))}
        for r in data
        if isinstance(r, dict) and r.get("full_name") and (r.get("permissions") or {}).get("push", True)
    ]


# --- the directory `claude --cloud` starts from ----------------------------------

def _env():
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    return env


def _git(args, cwd=None, timeout=300):
    command = ["git"]
    gh = gh_path()
    if gh and _acct["login"]:
        # A connected `gh` lets private repositories be cloned too.
        command += ["-c", "credential.helper=", "-c", f"credential.helper=!{shlex.quote(gh)} auth git-credential"]
    try:
        done = subprocess.run(command + args, cwd=cwd, capture_output=True, text=True, timeout=timeout, env=_env())
    except (OSError, subprocess.TimeoutExpired) as err:
        raise GithubError(f"git {args[0]} : {err}")
    if done.returncode != 0:
        raise GithubError(f"git {args[0]} : " + clean(done.stderr or done.stdout).strip()[-300:])
    return done.stdout


def default_branch(repo):
    try:
        out = _git(["ls-remote", "--symref", f"{GITHUB_BASE}/{repo}.git", "HEAD"], timeout=30)
        match = re.search(r"ref: refs/heads/(\S+)\s+HEAD", out)
        if match:
            return match.group(1)
    except GithubError:
        pass
    return "main"


def prepare_repo_dir(repo, direct):
    """The local directory `claude --cloud` is started from: the cloud
    session clones the GitHub remote of this directory at its current
    branch (Anthropic's documented behaviour), with Claude's own access.
    Returns (directory, branch Claude should push to): the default branch,
    or with direct=False a fresh aiwa/<date> branch Claude is asked to
    create itself."""
    url = f"{GITHUB_BASE}/{repo}.git"
    branch = default_branch(repo)
    dest = REPOS_DIR / repo.replace("/", "__")
    REPOS_DIR.mkdir(parents=True, exist_ok=True)
    if (dest / ".git").exists():
        try:
            _git(["fetch", "--depth", "1", "origin", branch], cwd=dest)
            _git(["checkout", "-q", "-f", "-B", branch, f"origin/{branch}"], cwd=dest)
        except GithubError:
            pass  # a stale checkout is harmless: the cloud clones the remote anyway
    else:
        try:
            _git(["clone", "--depth", "1", "--branch", branch, url, str(dest)])
        except GithubError:
            # Private, and no `gh` login here: an empty stub whose origin is
            # the repository is enough — the cloud clones it with Claude's
            # own access.
            shutil.rmtree(dest, ignore_errors=True)
            dest.mkdir(parents=True)
            _git(["init", "-q", "-b", branch], cwd=dest)
            _git(["remote", "add", "origin", url], cwd=dest)
            _git(["-c", "user.name=aiwa", "-c", "user.email=aiwa@example.com", "commit", "-q", "--allow-empty", "-m", "stub"], cwd=dest)
    if direct:
        return dest, branch
    return dest, "aiwa/" + time.strftime("%Y%m%d-%H%M%S")
