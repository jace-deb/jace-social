"""Publish a desktop app GitHub release to Jace Store as download links.

    python packaging/publish_app.py app-v1.0.0 [--dry-run]

Files stay on GitHub; the store lists them as direct downloads. The changelog
comes from app/CHANGELOG.md. Needs Node and JACE_STORE_TOKEN. If the Jace Store
project doesn't exist yet, it's skipped with a warning.
"""
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLI = ROOT / "packaging" / "jace-store.mjs"
REPO = "jace-deb/jace-social"
SLUG = "jace-social"
FILES = [   # (file ending, label); this order is the order on the store
    ("-windows-x64-setup.exe", "Windows"),
    ("-macos-arm64.app.zip", "macOS (Apple Silicon)"),
    ("-macos-x86_64.app.zip", "macOS (Intel)"),
    ("-x86_64.AppImage", "Linux"),
]


def main():
    tag = sys.argv[1]
    dry = "--dry-run" in sys.argv
    version = tag.removeprefix("app-v")
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/tags/{tag}",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "jace-social-publish"})
    with urllib.request.urlopen(req, timeout=30) as r:
        assets = json.load(r).get("assets", [])
    links = [f"{a['browser_download_url']}|{label}" for end, label in FILES for a in assets if a["name"].endswith(end)]
    if not links:
        sys.exit(f"{tag}: no downloads in the GitHub release")
    text = (ROOT / "app" / "CHANGELOG.md").read_text(encoding="utf-8")
    m = re.search(rf"^## {re.escape(version)}\s*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    changelog = m.group(1).strip() if m else f"https://github.com/{REPO}/releases/tag/{tag}"

    res = subprocess.run(["node", str(CLI), "project", SLUG, "--json"], capture_output=True, text=True)
    if res.returncode != 0:
        print(f"::warning::Jace Store project '{SLUG}' not found - create it first. Skipping.")
        return
    if version in {v["version_number"] for v in json.loads(res.stdout).get("versions", [])}:
        print(f"{version} is already on Jace Store")
        return
    cmd = ["node", str(CLI), "publish", SLUG, "--version", version, "--name", f"Jace Social {version}",
           "--changelog", changelog, "--primary-link", "--json"]
    for link in links:
        cmd += ["--link", link]
    print(" ".join(cmd[:8]), f"... {len(links)} downloads")
    if not dry:
        subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
