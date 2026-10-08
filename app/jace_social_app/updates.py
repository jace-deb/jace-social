"""Is there a newer desktop app? Compares APP_VERSION with the newest app-v* GitHub release."""
import platform
import sys

import requests

from jace_social_app import APP_VERSION

REPO = "jace-deb/jace-social"
RELEASES = f"https://github.com/{REPO}/releases"


def _version(v: str) -> tuple[int, ...]:
    return tuple(int(x) if x.isdigit() else 0 for x in v.split("."))


def _asset(assets: list[dict]) -> str | None:
    """This computer's download (as named by packaging/build.py)."""
    if sys.platform == "win32":
        ends = ".exe"
    elif sys.platform == "darwin":
        ends = f"-macos-{'arm64' if platform.machine() == 'arm64' else 'x86_64'}.app.zip"
    else:
        ends = ".AppImage"
    return next((a["browser_download_url"] for a in assets if a.get("name", "").endswith(ends)), None)


def check() -> dict:
    """{current, latest, newer, url} where url is this computer's download (or the release page)."""
    r = requests.get(f"https://api.github.com/repos/{REPO}/releases", params={"per_page": 50},
                     headers={"Accept": "application/vnd.github+json"}, timeout=15)
    r.raise_for_status()
    newest = None
    for rel in r.json():
        tag = rel.get("tag_name", "")
        if tag.startswith("app-v") and not rel.get("draft") and not rel.get("prerelease"):
            if newest is None or _version(tag[5:]) > _version(newest["tag_name"][5:]):
                newest = rel
    if newest is None:
        return {"current": APP_VERSION, "latest": APP_VERSION, "newer": False, "url": RELEASES}
    latest = newest["tag_name"][5:]
    url = _asset(newest.get("assets", [])) or newest.get("html_url") or RELEASES
    return {"current": APP_VERSION, "latest": latest, "newer": _version(latest) > _version(APP_VERSION), "url": url}
