"""The shared screen's stage: a Chrome window driven over the DevTools protocol, in the VM or on this Mac.

Slides are PNGs rendered once and swapped in a full-screen page, websites and other files get tabs of their own, and
controls run as JavaScript in the tab on screen. Each change is one message of a few milliseconds.

  uv run stage.py [--host]   open the stage and time a few slide jumps, a website and a control
"""
import contextlib
import json
import shlex
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from websockets.sync.client import connect

import vm

HERE = Path(__file__).parent
CACHE = HERE / ".stage"  # under a folder vm.py mounts, so the VM reads the same files at the same paths
PAGE = (HERE / "stage.html").resolve().as_uri()
GUEST_PORT, HOST_PORT, TUNNEL_PORT = 9222, 9333, 9334
FLAGS = ["--no-first-run", "--no-default-browser-check", "--autoplay-policy=no-user-gesture-required",
         "--disable-features=Translate,MediaRouter", "--hide-crash-restore-bubble"]
ON_STAGE = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp", ".avif", ".mp4", ".mov", ".m4v", ".webm"}
IN_A_TAB = {".pdf", ".html", ".htm", ".txt", ".md", ".json", ".csv"}

# Run in the tab on screen, keyed by what a presenter would say. `MEDIA_JS` is the biggest video on the page, the one a
# presenter means by "the video". The decision model offers these by name, so the names are plain words.
MEDIA_JS = "[...document.querySelectorAll('video')].sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight)[0]"
CONTROLS = {
    "scroll down": "scrollBy({top: innerHeight * 0.8, behavior: 'smooth'})",
    "scroll up": "scrollBy({top: -innerHeight * 0.8, behavior: 'smooth'})",
    "scroll to the top": "scrollTo({top: 0, behavior: 'smooth'})",
    "scroll to the bottom": "scrollTo({top: document.body.scrollHeight, behavior: 'smooth'})",
    "zoom in": "document.body.style.zoom = (parseFloat(document.body.style.zoom) || 1) * 1.25",
    "zoom out": "document.body.style.zoom = (parseFloat(document.body.style.zoom) || 1) / 1.25",
    "reset the zoom": "document.body.style.zoom = 1",
    "go back a page": "history.back()",
    "go forward a page": "history.forward()",
    "play the video": f"{MEDIA_JS}?.play()",
    "pause the video": f"{MEDIA_JS}?.pause()",
    "full screen": f"({MEDIA_JS} || document.documentElement).requestFullscreen()",
    "exit full screen": "document.fullscreenElement && document.exitFullscreen()",
}

CLICK_JS = """(t => {
  const hits = [...document.querySelectorAll('a, button, [role=button], [role=link], [role=tab], summary, input[type=submit]')]
    .filter(e => (e.innerText || e.value || e.getAttribute('aria-label') || '').toLowerCase().includes(t) && e.offsetParent)
    .sort((a, b) => (a.innerText || '').length - (b.innerText || '').length);
  hits[0]?.scrollIntoView({block: 'center'});
  hits[0]?.click();
  return hits.length > 0;
})(%s)"""


def render(pdf: Path, width: int = 1920) -> list[Path]:
    """Every page of a PDF as a PNG under .stage/, drawn again only when the PDF changes."""
    import Quartz  # macOS only; the rest of this file also loads on Linux, where brain.py is evaluated
    out = CACHE / pdf.stem
    pages = sorted(out.glob("*.png"), key=lambda p: int(p.stem))
    if pages and min(p.stat().st_mtime for p in pages) >= pdf.stat().st_mtime:
        return pages
    out.mkdir(parents=True, exist_ok=True)
    for old in pages:
        old.unlink()
    doc = Quartz.CGPDFDocumentCreateWithURL(Quartz.NSURL.fileURLWithPath_(str(pdf)))
    for n in range(1, Quartz.CGPDFDocumentGetNumberOfPages(doc) + 1):
        page = Quartz.CGPDFDocumentGetPage(doc, n)
        box = Quartz.CGPDFPageGetBoxRect(page, Quartz.kCGPDFCropBox)  # ponytail: ignores /Rotate, add when a rotated deck shows up
        scale = width / box.size.width
        w, h = width, round(box.size.height * scale)
        ctx = Quartz.CGBitmapContextCreate(None, w, h, 8, 0, Quartz.CGColorSpaceCreateDeviceRGB(), Quartz.kCGImageAlphaNoneSkipLast)
        Quartz.CGContextSetRGBFillColor(ctx, 1, 1, 1, 1)
        Quartz.CGContextFillRect(ctx, Quartz.CGRectMake(0, 0, w, h))
        Quartz.CGContextScaleCTM(ctx, scale, scale)
        Quartz.CGContextTranslateCTM(ctx, -box.origin.x, -box.origin.y)
        Quartz.CGContextDrawPDFPage(ctx, page)
        dest = Quartz.CGImageDestinationCreateWithURL(Quartz.NSURL.fileURLWithPath_(str(out / f"{n}.png")), "public.png", 1, None)
        Quartz.CGImageDestinationAddImage(dest, Quartz.CGBitmapContextCreateImage(ctx), None)
        Quartz.CGImageDestinationFinalize(dest)
    return sorted(out.glob("*.png"), key=lambda p: int(p.stem))


def wallpaper() -> Path:
    """This Mac's desktop picture as a JPEG the stage can show, for "close the slides"."""
    out = CACHE / "wallpaper.jpg"
    if not out.exists():
        js = 'ObjC.import("AppKit"); $.NSWorkspace.sharedWorkspace.desktopImageURLForScreen($.NSScreen.mainScreen).path.js'
        source = subprocess.run(["osascript", "-l", "JavaScript", "-e", js], capture_output=True, text=True).stdout.strip()
        CACHE.mkdir(exist_ok=True)
        subprocess.run(["sips", "-s", "format", "jpeg", source, "--out", str(out)], capture_output=True)
    return out


class Stage:
    """Chrome with its DevTools port open: in the VM (kiosk, full screen) when sandbox is set, else a window on this Mac."""

    def __init__(self, sandbox: str | None, dry: bool = False):
        self.sandbox, self.dry = sandbox, dry
        self.port = TUNNEL_PORT if sandbox else HOST_PORT
        self.sockets: dict[str, Any] = {}
        self.closing = contextlib.ExitStack()
        self.tabs: dict[str, str] = {}  # what a tab shows (a URL) -> its DevTools target id
        self.front = self.home = ""
        self.covered = False  # another app may be in front of Chrome
        self.calls = 0

    # -- plumbing ---------------------------------------------------------------------------------------------------

    def http(self, path: str, method: str = "GET") -> Any:
        """DevTools' HTTP endpoints: JSON, except /json/close and /json/activate, which answer in plain text."""
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method)
        with urllib.request.urlopen(req, timeout=15) as r:  # the VM can take seconds when it is busy
            body = r.read()
        return json.loads(body) if body[:1] in (b"{", b"[") else body.decode()

    def shell(self, cmd: list[str]) -> None:
        if self.sandbox:
            vm.guest(self.sandbox, shlex.join(cmd))
        else:
            subprocess.run(cmd, check=True)

    def socket(self, tab: str):
        if tab not in self.sockets:
            self.sockets[tab] = self.closing.enter_context(
                connect(f"ws://127.0.0.1:{self.port}/devtools/page/{tab}", max_size=None, open_timeout=5))
        return self.sockets[tab]

    def call(self, tab: str, method: str, **params) -> dict:
        ws = self.socket(tab)
        self.calls += 1
        ws.send(json.dumps({"id": self.calls, "method": method, "params": params}))
        while True:
            msg = json.loads(ws.recv(timeout=15))
            if msg.get("id") == self.calls:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error'].get('message')}")
                return msg.get("result", {})

    def run(self, tab: str, js: str):
        r = self.call(tab, "Runtime.evaluate", expression=js, awaitPromise=True, userGesture=True, returnByValue=True)
        if "exceptionDetails" in r:
            raise RuntimeError(r["exceptionDetails"].get("exception", {}).get("description") or r["exceptionDetails"].get("text"))
        return r.get("result", {}).get("value")

    def up(self) -> None:
        """Start Chrome if its DevTools port does not answer, and find or open the stage tab."""
        if self.dry:
            return
        if self.sandbox:
            # Also opens the kept SSH connection the tunnel rides on. A Chrome macOS reopened at login, without the
            # DevTools port, would come forward instead of the stage.
            vm.guest(self.sandbox, "for p in $(pgrep -x 'Google Chrome'); do ps -o command= -p $p | grep -q remote-debugging || kill $p; done; true")
            vm.forward(self.sandbox, self.port, GUEST_PORT)
        try:
            self.http("/json/version")
        except OSError:
            port = GUEST_PORT if self.sandbox else HOST_PORT
            profile = "$HOME/.copilot-chrome" if self.sandbox else str(Path.home() / ".copilot-chrome")
            size = ["--kiosk"] if self.sandbox else ["--new-window", "--window-size=1440,900"]
            args = ["open", "-na", "Google Chrome", "--args", f"--remote-debugging-port={port}", *FLAGS, *size, PAGE]
            if self.sandbox:
                vm.guest(self.sandbox, shlex.join(args) + f" --user-data-dir={profile}")
            else:
                subprocess.run(args + [f"--user-data-dir={profile}"], check=True)
            deadline = time.monotonic() + 30
            while True:
                try:
                    self.http("/json/version")
                    break
                except OSError:
                    if time.monotonic() > deadline:
                        raise RuntimeError("Chrome's DevTools port did not open; is Google Chrome installed? (uv run vm.py)")
                    time.sleep(0.3)
        pages = [t for t in self.http("/json/list") if t["type"] == "page"]
        stage = next((t for t in pages if t["url"].split("#")[0] == PAGE), None)
        self.home = stage["id"] if stage else self.http(f"/json/new?{PAGE}", "PUT")["id"]
        for t in pages:  # tabs left from an earlier run only slow the VM down
            if t["id"] != self.home:
                self.http(f"/json/close/{t['id']}")
        self.tabs.clear()
        deadline = time.monotonic() + 10
        while self.run(self.home, "typeof show") != "function":  # a tab opened just now has not run stage.html yet
            if time.monotonic() > deadline:
                raise RuntimeError(f"the stage page did not load: {PAGE}")
            time.sleep(0.1)
        self.bring(self.home)

    def bring(self, tab: str) -> None:
        if self.covered:  # the screen agent or the OS opened another app over Chrome
            self.shell(["open", "-a", "Google Chrome"])
            self.covered = False
        if tab == self.front:
            return
        try:
            self.http(f"/json/activate/{tab}")
            self.front = tab
        except urllib.error.HTTPError as e:  # closed behind the copilot's back, by hand or by the screen agent
            if e.code != 404:
                raise
            self.tabs = {u: t for u, t in self.tabs.items() if t != tab}
            self.up() if tab == self.home else self.bring(self.home)

    # -- what the presenter asks for ---------------------------------------------------------------------------------

    def show(self, image: Path, upcoming: list[Path] | None = None) -> None:
        """A slide, picture or video, full screen on black. `upcoming` are decoded in the background for the next jump."""
        if self.dry:
            print("   stage:", image)
            return
        self.bring(self.home)
        self.run(self.home, f"show({json.dumps(image.as_uri())}, {json.dumps([p.as_uri() for p in upcoming or []])})")

    def preload(self, images: list[Path]) -> None:
        """Decode images in the stage tab ahead of time, so the first jump to each is as fast as the next."""
        if not self.dry:
            self.run(self.home, f"{json.dumps([p.as_uri() for p in images])}.forEach(load)")

    def open(self, target: str) -> str:
        """A URL or a file path. Returns where it went: stage, tab or app."""
        path = Path(target) if target.startswith("/") else None
        if path and path.suffix.lower() in ON_STAGE:
            self.show(path)
            return "stage"
        if path and path.suffix.lower() not in IN_A_TAB:
            if self.dry:
                print("   $ open", target)
            else:
                self.shell(["open", target])
                self.covered = True
            return "app"
        url = path.as_uri() if path else target
        if self.dry:
            print("   tab:", url)
            return "tab"
        if url in self.tabs:
            self.bring(self.tabs[url])
        if self.tabs.get(url) != self.front:  # not open yet, or closed since
            self.bring(self.front or self.home)  # undoes `covered` before the new tab shows
            self.tabs[url] = self.front = self.http(f"/json/new?{urllib.parse.quote(url, safe=':/?&=#%+@,;~')}", "PUT")["id"]
            self.socket(self.front)  # now, so the first control on this tab does not wait for the handshake
        return "tab"

    def app(self, name: str) -> None:
        """Bring an app forward, launching it if needed. In the VM its window then fills the screen."""
        if self.dry:
            print("   app:", name)
            return
        if self.sandbox:
            ad, app = "~/.local/bin/agent-desktop", shlex.quote(name)
            # The app shows as soon as `open` returns; filling the screen waits for its window, so it runs in the background.
            fill = shlex.quote(f"{ad} launch {app} --activate; {ad} maximize --app {app}")
            vm.guest(self.sandbox, f"open -a {app} && (nohup sh -c {fill} >/dev/null 2>&1 &)", check=False)
        else:
            subprocess.run(["open", "-a", name], check=False)
        self.covered = True

    def close(self) -> None:
        """Close the app or tab on screen and go back to the stage; on the stage itself, show the desktop picture."""
        if self.dry:
            print("   close")
            return
        if self.covered:  # an app is in front: bringing Chrome back shows the stage
            self.bring(self.home)
        elif self.front and self.front != self.home:
            with contextlib.suppress(urllib.error.HTTPError):  # 404: closed already
                self.http(f"/json/close/{self.front}")
            self.tabs = {u: t for u, t in self.tabs.items() if t != self.front}
            self.sockets.pop(self.front, None)
            self.front = ""
            self.bring(self.home)
        else:
            self.show(wallpaper())

    def control(self, action: str) -> None:
        """One of CONTROLS, or "click:<text>" for the link or button whose text best matches, in the tab on screen."""
        if self.dry:
            print("   control:", action)
            return
        verb, _, text = action.partition(":")
        self.run(self.front or self.home, CLICK_JS % json.dumps(text.lower()) if verb == "click" else CONTROLS[verb])


if __name__ == "__main__":
    import sys
    stage = Stage(None if "--host" in sys.argv else "base")
    t = time.perf_counter()
    stage.up()
    print(f"stage up {time.perf_counter() - t:.2f}s")
    decks = sorted((HERE / "library").glob("*.pdf"))
    t = time.perf_counter()
    slides = {d.stem: render(d) for d in decks}
    print(f"rendered {sum(map(len, slides.values()))} slides {time.perf_counter() - t:.2f}s")
    first, second = list(slides.values())[:2]
    for pages, n in [(first, 3), (first, 1), (second, 4), (first, 3)]:
        t = time.perf_counter()
        stage.show(pages[n], [pages[n - 1], pages[min(n + 1, len(pages) - 1)]])
        print(f"slide {pages[n].parent.name} {n + 1}: {(time.perf_counter() - t) * 1e3:.0f}ms")
    for target in ["https://en.wikipedia.org/wiki/Warehouse", "scroll down", "zoom in", str(first[2])]:
        t = time.perf_counter()
        stage.control(target) if target in CONTROLS else stage.open(target)
        print(f"{target[-40:]}: {(time.perf_counter() - t) * 1e3:.0f}ms")
