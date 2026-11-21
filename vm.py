"""Mirror this Mac into the agent-desktop sandbox VM, so the shared screen looks like yours and sees your files.

Your Desktop, Documents and Downloads are mounted read-only and sit at the same paths in the VM, and the VM gets
your wallpaper, dark mode and Dock, plus Google Chrome for the stage. Safe to rerun; rerun after updating agent-desktop.

  uv run vm.py [--sandbox base]
"""
import argparse
import functools
import json
import os
import plistlib
import shlex
import shutil
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

FOLDERS = ["Desktop", "Documents", "Downloads"]
MOUNT = "/Volumes/My Shared Files"
LOOK = {"NSGlobalDomain": ["AppleInterfaceStyle", "AppleAccentColor", "AppleHighlightColor", "AppleIconAppearanceTheme",
                           "AppleIconAppearanceTintColor", "AppleReduceDesktopTinting", "AppleShowScrollBars"],
        "com.apple.dock": ["tilesize", "autohide", "orientation", "magnification", "largesize", "show-recents"]}
AD = Path.home() / ".agent-desktop"
TITLE = "Copilot screen"
# The npm shim path hides agent-desktop-macos-helper, which `sandbox` needs beside the binary.
AGENT_DESKTOP = os.path.realpath(shutil.which("agent-desktop") or "agent-desktop")


def agent_desktop(*args: str) -> dict:
    p = subprocess.run([AGENT_DESKTOP, *args], capture_output=True, text=True, timeout=120)
    return json.loads(p.stdout or p.stderr or "{}")


@functools.cache
def address(sandbox: str) -> str:
    vmhost = next((AD / "providers/macos").glob("build-*/vmhost"))
    ip = json.loads(subprocess.run([vmhost, "ip", AD / "sandboxes" / sandbox / "vm"], capture_output=True, text=True).stdout)["ip"]
    return f"{(AD / 'sandboxes' / sandbox / 'guest_user').read_text().strip()}@{ip}"


def ssh(sandbox: str) -> list:
    box = AD / "sandboxes" / sandbox
    return ["ssh", "-F", "/dev/null", "-i", box / "id_ed25519", "-o", f"UserKnownHostsFile={box / 'known_hosts'}",
            "-o", "StrictHostKeyChecking=yes", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
            "-o", "ControlMaster=auto", "-o", f"ControlPath=/tmp/copilot-vm-{sandbox}", "-o", "ControlPersist=600",
            "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2", address(sandbox)]


def guest(sandbox: str, script: str, stdin: bytes | None = None, check: bool = True) -> str:
    """Run a shell script in the VM over the SSH login agent-desktop set up. One kept-open connection makes a call
    about 0.3 s, where `sandbox exec`, which only runs agent-desktop, takes about 0.85 s."""
    p = subprocess.run([*ssh(sandbox), script], input=stdin or b"", capture_output=True)
    if check and p.returncode:
        raise RuntimeError(f"in the VM, {script[:80]} failed: {p.stderr.decode().strip()}")
    return p.stdout.decode()


def forward(sandbox: str, local: int, remote: int) -> None:
    """Make 127.0.0.1:local on this Mac reach 127.0.0.1:remote in the VM, over the kept connection. Fails quietly when
    the port is already forwarded."""
    subprocess.run([*ssh(sandbox)[:-1], "-O", "forward", "-L", f"{local}:127.0.0.1:{remote}", ssh(sandbox)[-1]], capture_output=True)


CHROME_DMG = "https://dl.google.com/chrome/mac/universal/stable/GGRO/googlechrome.dmg"


def install_chrome(sandbox: str) -> None:
    """The stage is Chrome (stage.py). The VM's user is an admin, so it can copy into /Applications without a password."""
    if guest(sandbox, "test -d '/Applications/Google Chrome.app' && echo yes", check=False).strip() == "yes":
        return
    print("installing Google Chrome in the VM (about 290 MB)")
    guest(sandbox, f"curl -fsSL -o /tmp/chrome.dmg {CHROME_DMG} && hdiutil attach -nobrowse -quiet -mountpoint /tmp/chrome /tmp/chrome.dmg"
                   " && cp -R '/tmp/chrome/Google Chrome.app' /Applications/; hdiutil detach -quiet /tmp/chrome; rm -f /tmp/chrome.dmg")


def up(sandbox: str) -> dict:
    """Start the VM in its window if it is stopped, and wait until agent-desktop can drive it."""
    status = lambda: agent_desktop("sandbox", "status", sandbox).get("data") or {}
    vm, start = status(), None
    if vm.get("state") == "stopped":
        print(f"starting the {sandbox} sandbox VM")
        # Its own session, so Ctrl-C here never reaches the VM even if `start` stays attached to it.
        start = subprocess.Popen([AGENT_DESKTOP, "sandbox", "start", sandbox, "--window", "--set", f"title={TITLE}"],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
    deadline = time.monotonic() + 180
    while vm.get("state") in ("stopped", "working") and time.monotonic() < deadline:
        if start and start.poll():
            sys.exit(f"agent-desktop sandbox start {sandbox} failed: {start.stdout.read().strip()}")
        time.sleep(2)
        vm = status()
    return vm


def vmhost_lines() -> dict[str, str]:
    """The lines vm.py changes in agent-desktop's VM host, keyed by how each starts: your folders shared beside its own
    files, and a display with this Mac's main screen's pixels and density, so the VM looks the same at the same size."""
    import Quartz
    screen = Quartz.CGMainDisplayID()
    mode = Quartz.CGDisplayCopyDisplayMode(screen)
    width, height = Quartz.CGDisplayModeGetPixelWidth(mode), Quartz.CGDisplayModeGetPixelHeight(mode)
    ppi = round(width / (Quartz.CGDisplayScreenSize(screen).width / 25.4))
    folders = ", ".join(f'"{f}": VZSharedDirectory(url: URL(fileURLWithPath: "{Path.home() / f}"), readOnly: true)' for f in FOLDERS)
    return {
        "share.share = ": f'share.share = VZMultipleDirectoryShare(directories: ["agent-desktop": VZSharedDirectory(url: inputs.bundle.share, readOnly: true), {folders}])',
        "graphics.displays = ": f"graphics.displays = [VZMacGraphicsDisplayConfiguration(widthInPixels: {width}, heightInPixels: {height}, pixelsPerInch: {ppi})]",
    }


def patch_vmhost() -> bool:
    """Rewrite those lines and rebuild the VM host. Returns True when it changed, which takes a VM restart."""
    lines, changed = vmhost_lines(), []
    for source in (AD / "providers/macos").glob("*/vmhost.swift"):
        text = source.read_text()
        for start, line in lines.items():
            old = next((l.strip() for l in text.splitlines() if l.strip().startswith(start)), None)
            if old is None:
                sys.exit(f"{source} has no line starting {start!r} any more; update vmhost_lines() in vm.py")
            if old != line:
                text = text.replace(old, line)
                changed.append(source)
        source.write_text(text)
    if changed:
        built = agent_desktop("sandbox", "install", "macos")
        if built.get("data", {}).get("state") != "ready" or any(l not in s.read_text() for s in changed for l in lines.values()):
            sys.exit(f"rebuilding the VM host failed: {built}")
    return bool(changed)


WALLPAPER_JS = """ObjC.import("AppKit");
const ws = $.NSWorkspace.sharedWorkspace, screens = $.NSScreen.screens;
const url = %s;
for (let i = 0; i < screens.count; i++) if (url) ws.setDesktopImageURLForScreenOptionsError($.NSURL.URLWithString(url), screens.objectAtIndex(i), $({}), null);
ws.desktopImageURLForScreen($.NSScreen.mainScreen).absoluteString.js"""


def wallpaper(sandbox: str) -> str:
    """Copy this Mac's wallpaper image to the same path in the VM and set it there. Downloaded wallpapers live in your
    Library, which the VM does not have, so pointing it at the same path alone shows nothing."""
    url = subprocess.run(["osascript", "-l", "JavaScript", "-e", WALLPAPER_JS % "null"], capture_output=True, text=True, check=True).stdout.strip()
    path = Path(urllib.parse.unquote(urllib.parse.urlsplit(url).path))
    if not path.is_relative_to("/System"):
        guest(sandbox, f"mkdir -p {shlex.quote(str(path.parent))} && cat > {shlex.quote(str(path))}", stdin=path.read_bytes())
    return guest(sandbox, "osascript -l JavaScript -e " + shlex.quote(WALLPAPER_JS % json.dumps(url))).strip()


def look_script() -> str:
    """Shell lines that copy this Mac's appearance and Dock settings into the VM user's preferences."""
    lines = []
    for domain, keys in LOOK.items():
        mine = plistlib.loads(subprocess.run(["defaults", "export", domain, "-"], capture_output=True, check=True).stdout)
        for key in keys:
            value = mine.get(key)
            if value is None:
                lines.append(f"defaults delete {domain} {key} 2>/dev/null")
            else:
                kind = {bool: "bool", int: "int", float: "float"}.get(type(value), "string")
                lines.append(f"defaults write {domain} {key} -{kind} {shlex.quote(str(value).lower() if kind == 'bool' else str(value))}")
    return "; ".join(lines)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sandbox", default="base")
    sandbox = p.parse_args().sandbox
    if address(sandbox).split("@")[0] != Path.home().name:
        print(f"the VM user is not {Path.home().name}, so paths under {Path.home()} will not match inside the VM")
    rebuilt = patch_vmhost()
    if up(sandbox).get("state") != "running":
        sys.exit(f"sandbox {sandbox} is not running; finish the one-time VM setup in README.md first")
    install_chrome(sandbox)
    guest(sandbox, look_script() + "; killall Dock 2>/dev/null; true")
    # Each folder becomes a link into the share; the VM's own empty folder is kept as ~/.Name (VM). The ACL is
    # macOS's "everyone deny delete" guard on home folders, which otherwise blocks the rename.
    guest(sandbox, "; ".join(f'[ -L ~/{f} ] || {{ chmod -N ~/{f} && mv ~/{f} ~/".{f} (VM)"; }}; ln -sfn "{MOUNT}/{f}" ~/{f}' for f in FOLDERS))
    if rebuilt or "Desktop" not in guest(sandbox, f"ls {shlex.quote(MOUNT)}"):
        print("restarting the VM so it mounts your folders at your screen's size")
        agent_desktop("sandbox", "stop", sandbox)
        address.cache_clear()
        if up(sandbox).get("state") != "running":
            sys.exit(f"sandbox {sandbox} did not come back up: agent-desktop sandbox status {sandbox}")
    print("wallpaper:", wallpaper(sandbox))
    for f in FOLDERS:
        print(f"~/{f}: {len(guest(sandbox, f'ls ~/{f}/').split())} items visible in the VM")


if __name__ == "__main__":
    main()
