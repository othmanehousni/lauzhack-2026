package ch.lauzhack.app.stage

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

const val GUEST_PORT = 9222
const val HOST_PORT = 9333
const val TUNNEL_PORT = 9334
private val FLAGS = listOf(
    "--no-first-run", "--no-default-browser-check", "--autoplay-policy=no-user-gesture-required",
    "--disable-features=Translate,MediaRouter", "--hide-crash-restore-bubble",
)
val ON_STAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "mp4", "mov", "m4v", "webm")
val IN_A_TAB = setOf("pdf", "html", "htm", "txt", "md", "json", "csv")

// Run in the tab on screen, keyed by what a presenter would say. MEDIA_JS is the biggest video on the page, the one a
// presenter means by "the video". The decision model offers these by name, so the names are plain words.
private const val MEDIA_JS = "[...document.querySelectorAll('video')].sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight)[0]"
val CONTROLS = mapOf(
    "scroll down" to "scrollBy({top: innerHeight * 0.8, behavior: 'smooth'})",
    "scroll up" to "scrollBy({top: -innerHeight * 0.8, behavior: 'smooth'})",
    "scroll to the top" to "scrollTo({top: 0, behavior: 'smooth'})",
    "scroll to the bottom" to "scrollTo({top: document.body.scrollHeight, behavior: 'smooth'})",
    "zoom in" to "document.body.style.zoom = (parseFloat(document.body.style.zoom) || 1) * 1.25",
    "zoom out" to "document.body.style.zoom = (parseFloat(document.body.style.zoom) || 1) / 1.25",
    "reset the zoom" to "document.body.style.zoom = 1",
    "go back a page" to "history.back()",
    "go forward a page" to "history.forward()",
    "play the video" to "$MEDIA_JS?.play()",
    "pause the video" to "$MEDIA_JS?.pause()",
    "full screen" to "($MEDIA_JS || document.documentElement).requestFullscreen()",
    "exit full screen" to "document.fullscreenElement && document.exitFullscreen()",
)

private fun click(words: String) = """(t => {
  const hits = [...document.querySelectorAll('a, button, [role=button], [role=link], [role=tab], summary, input[type=submit]')]
    .filter(e => (e.innerText || e.value || e.getAttribute('aria-label') || '').toLowerCase().includes(t) && e.offsetParent)
    .sort((a, b) => (a.innerText || '').length - (b.innerText || '').length);
  hits[0]?.scrollIntoView({block: 'center'});
  hits[0]?.click();
  return hits.length > 0;
})(${json(words)})"""

private fun json(s: String) = JsonPrimitive(s).toString()
private fun json(files: List<File>) = JsonArray(files.map { JsonPrimitive(uri(it)) }).toString()
fun uri(f: File) = f.toPath().toUri().toString()  // file:///..., as Chrome lists it
private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content

/** Chrome with its DevTools port open: in the VM (kiosk, full screen) when vm is set, else a window on this Mac. With
 * dry, it prints what it would do. */
class Stage(private val vm: Vm?, data: File, private val port: Int, private val dry: Boolean = false) {
    val page: File = File(data, "stage.html")
    val wallpaper: File by lazy { wallpaper(data) }
    private val profile = File(HOME, "Library/Application Support/Moliere/Chrome")
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val sockets = mutableMapOf<String, DefaultClientWebSocketSession>()
    private val tabs = mutableMapOf<String, String>()  // what a tab shows (a URL) -> its DevTools target id
    internal var front = ""
    internal var home = ""
    var covered = false  // another app may be in front of Chrome
    private var shown: File? = null  // what the stage tab shows
    private var thumb: Pair<File, ByteArray>? = null
    private var calls = 0

    init {
        data.mkdirs()
        val html = Stage::class.java.getResource("/stage/stage.html")!!.readBytes()
        if (!page.exists() || !page.readBytes().contentEquals(html)) page.writeBytes(html)
    }

    // -- plumbing ---------------------------------------------------------------------------------------------------

    private suspend fun http(path: String, method: HttpMethod = HttpMethod.Get): HttpResponse =
        client.request("http://127.0.0.1:$port$path") { this.method = method }

    /** DevTools' HTTP endpoints: JSON, except /json/close and /json/activate, which answer in plain text. */
    internal suspend fun devtools(path: String, method: HttpMethod = HttpMethod.Get): JsonElement =
        http(path, method).bodyAsText().let { if (it.firstOrNull() in setOf('{', '[')) Json.parseToJsonElement(it) else JsonPrimitive(it) }

    private suspend fun answers() = runCatching { http("/json/version").status.isSuccess() }.getOrDefault(false)

    private fun shell(cmd: List<String>) {
        when {
            vm != null -> vm.guest(quote(cmd))
            dry -> println("   $ ${quote(cmd)}")
            else -> exec(cmd)
        }
    }

    private suspend fun socket(tab: String) =
        sockets.getOrPut(tab) { client.webSocketSession("ws://127.0.0.1:$port/devtools/page/$tab") }

    internal suspend fun call(tab: String, method: String, params: JsonObject = JsonObject(emptyMap()), wait: Duration = 15.seconds): JsonObject {
        val ws = socket(tab)
        val id = ++calls
        ws.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
        return withTimeout(wait) {  // the VM can take seconds when it is busy
            var msg: JsonObject
            do msg = Json.parseToJsonElement((ws.incoming.receive() as Frame.Text).readText()).jsonObject
            while (msg["id"]?.jsonPrimitive?.intOrNull != id)
            msg["error"]?.let { error("$method: ${it.str("message")}") }
            msg["result"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    internal suspend fun evaluate(tab: String, js: String, await: Boolean = true): JsonElement? {
        val r = call(tab, "Runtime.evaluate", buildJsonObject {
            put("expression", js); put("awaitPromise", await); put("userGesture", true); put("returnByValue", true)
        })
        r["exceptionDetails"]?.jsonObject?.let { e ->
            error(e["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: e["text"]?.jsonPrimitive?.content ?: "$e")
        }
        return r["result"]?.jsonObject?.get("value")
    }

    private suspend fun newTab(url: String) =
        devtools("/json/new?" + URLEncoder.encode(url, Charsets.UTF_8).replace("+", "%20"), HttpMethod.Put).str("id")

    private fun launch() {
        val args = listOf("open", "-na", "Google Chrome", "--args", "--remote-debugging-port=${if (vm != null) GUEST_PORT else port}") +
            FLAGS + (if (vm != null) listOf("--kiosk") else listOf("--new-window", "--window-size=1440,900")) + uri(page)
        if (vm != null) vm.guest(quote(args) + " --user-data-dir=\$HOME/.copilot-chrome") else shell(args + "--user-data-dir=$profile")
    }

    /** Starts Chrome if its DevTools port does not answer, and finds or opens the stage tab. */
    suspend fun up() {
        if (vm != null) {
            // Also opens the kept SSH connection the tunnel rides on. A Chrome macOS reopened at login, without the
            // DevTools port, would come forward instead of the stage.
            vm.guest("for p in \$(pgrep -x 'Google Chrome'); do ps -o command= -p \$p | grep -q remote-debugging || kill \$p; done; true")
            vm.forward(port, GUEST_PORT)
        }
        if (dry) return launch()
        if (!answers()) {
            launch()
            val start = TimeSource.Monotonic.markNow()
            while (!answers()) {
                if (start.elapsedNow() > 30.seconds) error("Chrome's DevTools port did not open; is Google Chrome installed${if (vm != null) " in the VM" else ""}?")
                delay(300)
            }
        }
        val pages = devtools("/json/list").jsonArray.filter { it.str("type") == "page" }
        home = pages.firstOrNull { it.str("url").substringBefore('#') == uri(page) }?.str("id") ?: newTab(uri(page))
        for (t in pages) if (t.str("id") != home) http("/json/close/${t.str("id")}")  // tabs left from an earlier run only slow the VM down
        tabs.clear()
        val start = TimeSource.Monotonic.markNow()
        while (evaluate(home, "typeof show")?.jsonPrimitive?.content != "function") {  // a tab opened just now has not run stage.html yet
            if (start.elapsedNow() > 10.seconds) error("the stage page did not load: ${uri(page)}")
            delay(100)
        }
        bring(home)
    }

    private suspend fun bring(tab: String) {
        if (covered) {  // the screen agent or the OS opened another app over Chrome
            shell(listOf("open", "-a", "Google Chrome"))
            covered = false
        }
        if (tab == front && sockets[tab]?.closeReason?.isCompleted != true) return  // a closed socket: the tab may be gone
        val r = http("/json/activate/$tab")
        if (r.status == HttpStatusCode.NotFound) {  // closed behind the app's back, by hand or by the screen agent
            tabs.values.removeAll { it == tab }
            sockets.remove(tab)
            if (tab == home) up() else bring(home)
        } else {
            check(r.status.isSuccess()) { "activate $tab: ${r.status}" }
            front = tab
        }
    }

    // -- what the presenter asks for ---------------------------------------------------------------------------------

    /** A slide, picture or video, full screen on black. `upcoming` are decoded in the background for the next jump. */
    suspend fun show(image: File, upcoming: List<File> = emptyList()) {
        if (dry) return println("   stage: $image")
        bring(home)
        evaluate(home, "show(${json(uri(image))}, ${json(upcoming)})")
        shown = image
    }

    /** Decodes images in the stage tab ahead of time, so the first jump to each is as fast as the next. */
    suspend fun preload(images: List<File>) {
        if (!dry) evaluate(home, "${json(images)}.forEach(load)")
    }

    /** A URL or a file path. Returns where it went: stage, tab or app. */
    suspend fun open(target: String): String {
        val path = if (target.startsWith("/")) File(target) else null
        val ext = path?.extension?.lowercase()
        if (path != null && ext in ON_STAGE) return "stage".also { show(path) }
        if (path != null && ext !in IN_A_TAB) {
            shell(listOf("open", target))
            covered = true
            return "app"
        }
        val url = path?.let(::uri) ?: target
        if (dry) return "tab".also { println("   tab: $url") }
        tabs[url]?.let { bring(it) }
        if (tabs[url] != front) {  // not open yet, or closed since
            bring(front.ifEmpty { home })  // undoes `covered` before the new tab shows
            front = newTab(url)
            tabs[url] = front
            socket(front)  // now, so the first control on this tab does not wait for the handshake
        }
        return "tab"
    }

    /** Brings an app forward, launching it if needed. In the VM its window then fills the screen. */
    fun app(name: String) {
        when {
            vm != null -> {
                val ad = "~/.local/bin/agent-desktop"
                val app = quote(name)
                // The app shows as soon as `open` returns; filling the screen waits for its window, so it runs in the background.
                val fill = quote("$ad launch $app --activate; $ad maximize --app $app")
                vm.guest("open -a $app && (nohup sh -c $fill >/dev/null 2>&1 &)", check = false)
            }
            dry -> println("   app: $name")
            else -> exec(listOf("open", "-a", name), check = false)
        }
        covered = true
    }

    /** Closes the app or tab on screen and goes back to the stage; on the stage itself, shows the desktop picture. */
    suspend fun close() {
        if (dry) return println("   close")
        if (covered) bring(home)  // an app is in front: bringing Chrome back shows the stage
        else if (front.isNotEmpty() && front != home) {
            http("/json/close/$front")  // 404: closed already
            tabs.values.removeAll { it == front }
            sockets.remove(front)?.cancel()
            front = ""
            bring(home)
        } else show(wallpaper)
    }

    /** One of CONTROLS, or "click:<words>" for the link or button whose text best matches, in the tab on screen. */
    suspend fun control(action: String) {
        if (dry) return println("   control: $action")
        val verb = action.substringBefore(":")
        val js = if (verb == "click") click(action.substringAfter(":").lowercase())
        else CONTROLS[verb] ?: error("no control \"$verb\"; one of ${CONTROLS.keys.joinToString()} or click:<words>")
        // Not awaited: Chrome's smooth scrolls return a promise that settles half a second later, when the scroll ends.
        evaluate(front.ifEmpty { home }, js, await = false)
    }

    /** A small JPEG of what the audience sees: the stage's picture scaled down, or a screenshot of the tab on screen.
     * Null when an app covers Chrome. */
    suspend fun thumbnail(): ByteArray? {
        if (dry || covered || front.isEmpty()) return null
        val file = shown.takeIf { front == home }
        thumb?.let { (f, bytes) -> if (f == file) return bytes }  // a phone asks again and again while a slide stays up
        val still = file?.let { runCatching { ImageIO.read(it) }.getOrNull() }  // null for videos, SVG, WebP
        if (still != null) return small(still).also { thumb = file to it }
        return small(ImageIO.read(Base64.getDecoder().decode(
            call(front, "Page.captureScreenshot", buildJsonObject { put("format", "jpeg"); put("quality", 80) }, wait = 3.seconds).str("data")
        ).inputStream()))
    }
}

private fun small(image: BufferedImage, width: Int = 480): ByteArray {
    var img = image
    do {  // halving keeps text legible where one bilinear step would skip pixels; the first pass also drops alpha for JPEG
        val w = maxOf(img.width / 2, width)
        val next = BufferedImage(w, img.height * w / img.width, BufferedImage.TYPE_INT_RGB)
        next.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, next.width, next.height, null)
            dispose()
        }
        img = next
    } while (img.width > width)
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
}

/** This Mac's desktop picture as a JPEG the stage can show, for "close the slides". */
private fun wallpaper(data: File): File {
    val out = File(data, "wallpaper.jpg")
    if (!out.exists()) {
        val js = "ObjC.import(\"AppKit\"); \$.NSWorkspace.sharedWorkspace.desktopImageURLForScreen(\$.NSScreen.mainScreen).path.js"
        val source = exec(listOf("osascript", "-l", "JavaScript", "-e", js)).trim()
        exec(listOf("sips", "-s", "format", "jpeg", source, "--out", out.path), check = false)
    }
    return out
}
