package ch.lauzhack.app.stage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.net.URI
import java.util.concurrent.CompletableFuture
import javax.xml.parsers.DocumentBuilderFactory

val HOME = File(System.getProperty("user.home"))

/** Mounted read-only in the VM at the same paths (Vm.setup), so a path here is the same path there. */
val FOLDERS = listOf("Desktop", "Documents", "Downloads")
private const val MOUNT = "/Volumes/My Shared Files"
private const val CHROME = "test -d '/Applications/Google Chrome.app'"
private const val CHROME_DMG = "https://dl.google.com/chrome/mac/universal/stable/GGRO/googlechrome.dmg"
private val LOOK = mapOf(
    "NSGlobalDomain" to listOf("AppleInterfaceStyle", "AppleAccentColor", "AppleHighlightColor", "AppleIconAppearanceTheme",
        "AppleIconAppearanceTintColor", "AppleReduceDesktopTinting", "AppleShowScrollBars"),
    "com.apple.dock" to listOf("tilesize", "autohide", "orientation", "magnification", "largesize", "show-recents"),
)
private const val SCREEN_JS = """ObjC.import("CoreGraphics");
const d = $.CGMainDisplayID(), m = $.CGDisplayCopyDisplayMode(d);
[$.CGDisplayModeGetPixelWidth(m), $.CGDisplayModeGetPixelHeight(m), $.CGDisplayScreenSize(d).width].join(" ")"""
private const val WALLPAPER_JS = """ObjC.import("AppKit");
const ws = $.NSWorkspace.sharedWorkspace, screens = $.NSScreen.screens;
const url = %s;
for (let i = 0; i < screens.count; i++) if (url) ws.setDesktopImageURLForScreenOptionsError($.NSURL.URLWithString(url), screens.objectAtIndex(i), $({}), null);
ws.desktopImageURLForScreen($.NSScreen.mainScreen).absoluteString.js"""

/** The lines setup() changes in agent-desktop's VM host, keyed by how each starts: your folders shared beside its own
 * files, and a display with this Mac's main screen's pixels and density, so the VM looks the same at the same size. */
fun vmhostLines(width: Int, height: Int, ppi: Int, home: File = HOME): Map<String, String> {
    val folders = FOLDERS.joinToString(", ") { "\"$it\": VZSharedDirectory(url: URL(fileURLWithPath: \"${File(home, it)}\"), readOnly: true)" }
    return mapOf(
        "share.share = " to "share.share = VZMultipleDirectoryShare(directories: [\"agent-desktop\": VZSharedDirectory(url: inputs.bundle.share, readOnly: true), $folders])",
        "graphics.displays = " to "graphics.displays = [VZMacGraphicsDisplayConfiguration(widthInPixels: $width, heightInPixels: $height, pixelsPerInch: $ppi)]",
    )
}

/** vmhost.swift with the line that starts like each key of lines replaced by its value. */
fun patchVmhost(text: String, lines: Map<String, String>): String = lines.entries.fold(text) { t, (start, line) ->
    val old = t.lines().map { it.trim() }.firstOrNull { it.startsWith(start) }
        ?: error("agent-desktop's VM host has no line starting \"$start\" any more: update vmhostLines in Vm.kt")
    t.replace(old, line)
}

/** Shell lines that copy this Mac's appearance and Dock settings into the VM user's preferences. */
private fun lookScript(): String = LOOK.flatMap { (domain, keys) ->
    val mine = plist(exec(listOf("defaults", "export", domain, "-")))
    keys.map { key ->
        mine[key]?.let { (kind, value) -> "defaults write $domain $key -$kind ${quote(value)}" } ?: "defaults delete $domain $key 2>/dev/null"
    }
}.joinToString("; ")

/** The top-level keys of an XML plist, each with its `defaults write` type and value. */
private fun plist(xml: String): Map<String, Pair<String, String>> {
    val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    val dict = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).getElementsByTagName("dict").item(0) ?: return emptyMap()
    val items = (0 until dict.childNodes.length).map { dict.childNodes.item(it) }.filterIsInstance<Element>()
    return items.chunked(2).filter { it.size == 2 }.associate { (k, v) ->
        k.textContent to when (v.tagName) {
            "true", "false" -> "bool" to v.tagName
            "integer" -> "int" to v.textContent
            "real" -> "float" to v.textContent.toDouble().toString()  // as Python's str(float): 83 -> 83.0
            else -> "string" to v.textContent
        }
    }
}

/** The app's data: rendered slides, the stage page, the ear. Under Documents, so the VM reads it at the same path. */
val DATA = File(HOME, "Documents/.mole")

const val TITLE = "Molière screen" // a VM started before the rename keeps "Copilot screen" until its next start
private val AD = File(HOME, ".agent-desktop")

/** Runs a command and returns its stdout. With check, a non-zero exit throws with its stderr. */
fun exec(cmd: List<String>, input: ByteArray = ByteArray(0), check: Boolean = true, what: String = cmd.joinToString(" ").take(80)): String {
    val p = ProcessBuilder(cmd).start()
    val err = CompletableFuture.supplyAsync { p.errorStream.readBytes() }  // read alongside, so a full stderr pipe cannot stall it
    p.outputStream.use { it.write(input) }
    val out = p.inputStream.readBytes().decodeToString()
    if (p.waitFor() != 0 && check) error("$what failed: ${err.get().decodeToString().trim()}")
    return out
}

/** As Python's shlex.quote. */
fun quote(s: String): String =
    if (s.isNotEmpty() && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "@%+=:,./-_" }) s
    else "'" + s.replace("'", "'\"'\"'") + "'"

fun quote(cmd: List<String>) = cmd.joinToString(" ") { quote(it) }

/** agent-desktop's real path: the npm shim's folder lacks agent-desktop-macos-helper, which `sandbox` needs beside the
 * binary. An app started from the Dock has no shell PATH, so a login shell is asked too. */
val agentDesktop: String? by lazy {
    val onPath = System.getenv("PATH").orEmpty().split(":").map { File(it, "agent-desktop") }.firstOrNull { it.canExecute() }
    val shell = System.getenv("SHELL") ?: "/bin/zsh"
    (onPath ?: exec(listOf(shell, "-ilc", "command -v agent-desktop"), check = false).lines().lastOrNull { it.startsWith("/") }?.let(::File))
        ?.canonicalPath
}

val JsonObject.state get() = this["state"]?.jsonPrimitive?.content

/** The agent-desktop sandbox VM. With dry, commands that would change it are printed instead. */
class Vm(val sandbox: String, private val ad: String, private val dry: Boolean = false) {
    private val box = File(AD, "sandboxes/$sandbox")

    private fun cli(vararg args: String): JsonObject =
        Json.parseToJsonElement(exec(listOf(ad, *args), check = false).ifBlank { "{}" }).jsonObject

    /** `data` of `sandbox status`, or its `error` (no state) when the sandbox does not exist. */
    fun status(): JsonObject = cli("sandbox", "status", sandbox).let { (it["data"] ?: it["error"])?.jsonObject ?: it }

    /** Starts the VM in its window if it is stopped, and waits until agent-desktop can drive it. */
    fun up(): JsonObject {
        var vm = status()
        var start: Process? = null
        val log = File(DATA, "vm-start.log")
        if (vm.state == "stopped") {
            val cmd = listOf(ad, "sandbox", "start", sandbox, "--window", "--set", "title=$TITLE")
            if (dry) return vm.also { println("   $ ${quote(cmd)}") }
            DATA.mkdirs()
            // Ignoring Ctrl-C, which a terminal sends this whole process group, so it never reaches the VM. A log, not a
            // pipe, so the VM's start does not die of a closed pipe when this app quits.
            start = ProcessBuilder(listOf("/bin/sh", "-c", "trap '' INT; exec \"$@\"", "sh") + cmd)
                .redirectErrorStream(true).redirectOutput(log).start()
        }
        val deadline = System.nanoTime() + 180_000_000_000
        while (vm.state in setOf("stopped", "working") && System.nanoTime() < deadline) {
            if (start != null && !start.isAlive && start.exitValue() != 0) error("agent-desktop sandbox start $sandbox failed: ${log.readText().trim()}")
            Thread.sleep(2000)
            vm = status()
        }
        return vm
    }

    private var resolved: String? = null  // forgotten on a restart, which may give the VM another address
    private val address: String get() = resolved ?: run {
        val vmhost = File(AD, "providers/macos").listFiles().orEmpty().filter { it.name.startsWith("build-") }
            .map { File(it, "vmhost") }.filter { it.canExecute() }.maxByOrNull { it.lastModified() }
            ?: error("agent-desktop's VM host is not built: agent-desktop sandbox install macos")
        val ip = Json.parseToJsonElement(exec(listOf(vmhost.path, "ip", File(box, "vm").path))).jsonObject["ip"]!!.jsonPrimitive.content
        "${File(box, "guest_user").readText().trim()}@$ip".also { resolved = it }
    }

    fun ssh(): List<String> = listOf(
        "ssh", "-F", "/dev/null", "-i", "$box/id_ed25519", "-o", "UserKnownHostsFile=$box/known_hosts",
        "-o", "StrictHostKeyChecking=yes", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
        "-o", "ControlMaster=auto", "-o", "ControlPath=/tmp/copilot-vm-$sandbox", "-o", "ControlPersist=600",
        "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2", address,
    )

    /** Runs a shell script in the VM over the SSH login agent-desktop set up. One kept-open connection makes a call
     * about 0.3 s, where `sandbox exec`, which only runs agent-desktop, takes about 0.85 s. */
    fun guest(script: String, stdin: ByteArray = ByteArray(0), check: Boolean = true): String {
        if (dry) return "".also { println("   vm $ $script" + if (stdin.isEmpty()) "" else " < ${stdin.size} bytes") }
        return exec(ssh() + script, stdin, check, what = "in the VM, ${script.take(80)}")
    }

    /** Makes 127.0.0.1:local here reach 127.0.0.1:remote in the VM, over the kept connection. Fails quietly when the
     * port is forwarded already. */
    fun forward(local: Int, remote: Int) {
        if (dry) return println("   vm forward 127.0.0.1:$local -> 127.0.0.1:$remote")
        exec(ssh().dropLast(1) + listOf("-O", "forward", "-L", "$local:127.0.0.1:$remote", address), check = false)
    }

    /** Whether the VM can read the page and has Chrome, the two things the stage needs from setup(). */
    fun ready(page: File) = guest("test -r ${quote(page.path)} && $CHROME && echo ok", check = false).trim() == "ok"

    /** The one-time setup of a running VM: your Desktop, Documents and Downloads read-only at the same paths, your
     * screen's size and density, wallpaper, dark mode and Dock, and Google Chrome for the stage, restarting the VM when
     * it must. Safe to rerun; rerun after updating agent-desktop, whose VM host it patches. Returns what it did, one
     * line per step. */
    suspend fun setup(): List<String> = withContext(Dispatchers.IO) {
        val did = mutableListOf<String>()
        val user = File(box, "guest_user").readText().trim()
        if (user != HOME.name) did += "the VM user is $user, not ${HOME.name}, so paths under $HOME will not match inside the VM"
        val rebuilt = patchVmhosts()
        did += if (rebuilt) "patched agent-desktop's VM host to share your folders at your screen's size, and rebuilt it"
        else "agent-desktop's VM host shares your folders at your screen's size already"
        if (up().state != "running" && !dry) error("sandbox $sandbox is not running: finish the One-time VM setup in README.md")
        did += if (guest("$CHROME && echo yes", check = false).trim() == "yes") "Google Chrome is in the VM already" else {
            // The VM's user is an admin, so it can copy into /Applications without a password.
            guest("curl -fsSL -o /tmp/chrome.dmg $CHROME_DMG && hdiutil attach -nobrowse -quiet -mountpoint /tmp/chrome /tmp/chrome.dmg" +
                " && cp -R '/tmp/chrome/Google Chrome.app' /Applications/; hdiutil detach -quiet /tmp/chrome; rm -f /tmp/chrome.dmg; $CHROME")
            "installed Google Chrome in the VM (about 290 MB)"
        }
        val prefs = "{ defaults export NSGlobalDomain -; defaults export com.apple.dock -; } | md5"
        did += if (guest("a=$($prefs); ${lookScript()}; [ \"$($prefs)\" = \"\$a\" ] || { killall Dock 2>/dev/null; echo changed; }; true").isBlank())
            "the VM has your dark mode, colours and Dock settings already" else "copied your dark mode, colours and Dock settings to the VM"
        // Each folder becomes a link into the share; the VM's own empty folder is kept as ~/.Name (VM). The ACL is
        // macOS's "everyone deny delete" guard on home folders, which otherwise blocks the rename.
        guest(FOLDERS.joinToString(" && ") { "{ [ -L ~/$it ] || { chmod -N ~/$it && mv ~/$it ~/\".$it (VM)\"; }; } && ln -sfn \"$MOUNT/$it\" ~/$it" })
        did += "linked ${FOLDERS.joinToString { "~/$it" }} in the VM to the shared folders"
        if (rebuilt || "Desktop" !in guest("ls ${quote(MOUNT)}", check = false)) {
            restart()
            did += "restarted the VM so it mounts your folders at your screen's size"
        }
        did += "wallpaper: ${wallpaper()}"
        did += FOLDERS.map { f -> "~/$f: ${guest("ls ~/$f/").lines().count { it.isNotEmpty() }} items visible in the VM" }
        did
    }

    /** Rewrites vmhostLines into every copy of agent-desktop's VM host and rebuilds it. True when it changed, which
     * takes a VM restart. */
    internal fun patchVmhosts(providers: File = File(AD, "providers/macos")): Boolean {
        val (width, height, mm) = exec(listOf("osascript", "-l", "JavaScript", "-e", SCREEN_JS)).trim().split(" ")
        val ppi = mm.toDouble().takeIf { it > 0 }?.let { Math.rint(width.toInt() / (it / 25.4)).toInt() } ?: 144 // a projector may report 0 mm
        val lines = vmhostLines(width.toInt(), height.toInt(), ppi)
        val changed = providers.listFiles().orEmpty().map { File(it, "vmhost.swift") }.filter { it.isFile }.filter { source ->
            val old = source.readText()
            val new = patchVmhost(old, lines)
            if (new == old) return@filter false
            if (!dry) source.writeText(new) else {
                println("   write $source:")
                old.lines().zip(new.lines()).filter { (a, b) -> a != b }.forEach { (a, b) -> println("     - ${a.trim()}\n     + ${b.trim()}") }
            }
            true
        }
        if (changed.isEmpty()) return false
        if (dry) return true.also { println("   $ ${quote(listOf(ad, "sandbox", "install", "macos"))}") }
        val built = cli("sandbox", "install", "macos")
        if (built["data"]?.jsonObject?.state != "ready" || changed.any { s -> lines.values.any { it !in s.readText() } })
            error("rebuilding agent-desktop's VM host failed: $built")
        return true
    }

    private fun restart() {
        if (dry) {
            println("   $ ${quote(listOf(ad, "sandbox", "stop", sandbox))}")
            return println("   $ ${quote(listOf(ad, "sandbox", "start", sandbox, "--window", "--set", "title=$TITLE"))}")
        }
        exec(ssh().dropLast(1) + listOf("-O", "exit", address), check = false)  // the kept connection would outlive the VM
        val stopped = cli("sandbox", "stop", sandbox)
        if (stopped["data"]?.jsonObject?.state != "stopped") error("agent-desktop sandbox stop $sandbox failed: $stopped")
        resolved = null
        if (up().state != "running") error("sandbox $sandbox did not come back up: agent-desktop sandbox status $sandbox")
    }

    /** Copies this Mac's wallpaper image to the same path in the VM and sets it there. Downloaded wallpapers live in your
     * Library, which the VM does not have, so pointing it at the same path alone shows nothing. */
    private fun wallpaper(): String {
        val url = exec(listOf("osascript", "-l", "JavaScript", "-e", WALLPAPER_JS.format("null"))).trim()
        val path = File(URI(url).path)
        if (!path.startsWith("/System")) guest("mkdir -p ${quote(path.parent)} && cat > ${quote(path.path)}", path.readBytes())
        return guest("osascript -l JavaScript -e " + quote(WALLPAPER_JS.format(JsonPrimitive(url).toString()))).trim()
    }
}
