package ch.lauzhack.app.stage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture

val HOME = File(System.getProperty("user.home"))

/** Mounted read-only in the VM at the same paths (vm.py), so a path here is the same path there. */
val FOLDERS = listOf("Desktop", "Documents", "Downloads")

/** The app's data: rendered slides, the stage page, the ear. Under Documents, so the VM reads it at the same path. */
val DATA = File(HOME, "Documents/.mole")

const val TITLE = "Copilot screen"
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

    private val address: String by lazy {
        val vmhost = File(AD, "providers/macos").listFiles().orEmpty().filter { it.name.startsWith("build-") }
            .map { File(it, "vmhost") }.filter { it.canExecute() }.maxByOrNull { it.lastModified() }
            ?: error("agent-desktop's VM host is not built: agent-desktop sandbox install macos")
        val ip = Json.parseToJsonElement(exec(listOf(vmhost.path, "ip", File(box, "vm").path))).jsonObject["ip"]!!.jsonPrimitive.content
        "${File(box, "guest_user").readText().trim()}@$ip"
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
        if (dry) return "".also { println("   vm $ $script") }
        return exec(ssh() + script, stdin, check, what = "in the VM, ${script.take(80)}")
    }

    /** Makes 127.0.0.1:local here reach 127.0.0.1:remote in the VM, over the kept connection. Fails quietly when the
     * port is forwarded already. */
    fun forward(local: Int, remote: Int) {
        if (dry) return println("   vm forward 127.0.0.1:$local -> 127.0.0.1:$remote")
        exec(ssh().dropLast(1) + listOf("-O", "forward", "-L", "$local:127.0.0.1:$remote", address), check = false)
    }
}
