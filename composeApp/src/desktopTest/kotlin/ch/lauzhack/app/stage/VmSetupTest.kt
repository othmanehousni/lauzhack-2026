package ch.lauzhack.app.stage

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Vm.setup()'s patch of agent-desktop's VM host, on copies of this Mac's in /tmp/mole-vmhost (the real ones are only
 * read), and a dry run of the whole setup:
 *
 *   MOLE_VM_DRY=base ./gradlew :composeApp:desktopTest --tests '*VmSetupTest*' -i */
class VmSetupTest {
    private val out = File("/tmp/mole-vmhost")

    // agent-desktop 1.3.1's own lines, which the patch replaces
    private val stock = mapOf(
        "share.share = " to "share.share = VZSingleDirectoryShare(directory: VZSharedDirectory(url: inputs.bundle.share, readOnly: true))",
        "graphics.displays = " to "graphics.displays = [VZMacGraphicsDisplayConfiguration(widthInPixels: 1920, heightInPixels: 1200, pixelsPerInch: 144)]",
        "application.setActivationPolicy(" to "application.setActivationPolicy(.regular)",
        "window.orderFrontRegardless()" to "application.activate(ignoringOtherApps: true)",
        "window.title = " to "window.title = title ?? bundle.sandboxName",
    )

    // agent-desktop 1.3.1's SandboxWindow.make, where the host shows its window
    private val window = """
        let application = NSApplication.shared
        application.setActivationPolicy(.regular)
        application.finishLaunching()
        window.title = title ?? bundle.sandboxName
        window.center()
        window.makeKeyAndOrderFront(nil)
        application.activate(ignoringOtherApps: true)
    """.trimIndent()

    @Test
    fun makesTheWindowMolieres() {
        val patched = patchVmhost(window, ACCESSORY)
        assertEquals("""
            let application = NSApplication.shared
            application.setActivationPolicy(.accessory)
            application.finishLaunching()
            window.title = title ?? "Molière screen"
            window.center()
            window.makeKeyAndOrderFront(nil)
            window.orderFrontRegardless()
        """.trimIndent(), patched)
        assertEquals(patched, patchVmhost(patched, ACCESSORY))  // a second setup changes nothing
        assertEquals(window, patchVmhost(patched, stock - "share.share = " - "graphics.displays = "))
    }

    @Test
    fun noticesAHostBuiltBeforeIt() {
        val providers = File("/tmp/mole-vmhost-accessory").apply { deleteRecursively() }
        val a = File(providers, "a/vmhost.swift").apply { parentFile.mkdirs(); writeText(patchVmhost(window, ACCESSORY)) }
        File(providers, "build-a").mkdirs()  // a build, without a source
        assertTrue(accessory(providers))
        val b = File(providers, "b/vmhost.swift").apply { parentFile.mkdirs(); writeText(window) }
        assertFalse(accessory(providers))  // the first start after this build sets it up
        b.writeText(patchVmhost(b.readText(), ACCESSORY))
        assertTrue(accessory(providers))  // later starts do nothing
        a.writeText(a.readText().replace(".accessory", ".regular"))
        assertFalse(accessory(providers))
    }

    private fun copies(to: File = out): List<File> {
        to.deleteRecursively()
        return File(HOME, ".agent-desktop/providers/macos").listFiles().orEmpty().map { File(it, "vmhost.swift") }.filter { it.isFile }
            .map { it.copyTo(File(to, "${it.parentFile.name}/vmhost.swift")) }
    }

    @Test
    fun patchesTheRealVmHost() {
        val copies = copies().ifEmpty { return println("skipped: agent-desktop's macOS provider is not installed") }
        for (copy in copies) {
            val text = copy.readText()
            val patchedBefore = "VZMultipleDirectoryShare" in text
            val screen = if (patchedBefore) Regex("""widthInPixels: (\d+), heightInPixels: (\d+), pixelsPerInch: (\d+)""").find(text)!!
                .groupValues.drop(1).map(String::toInt) else listOf(3024, 1964, 255)
            val lines = vmhostLines(screen[0], screen[1], screen[2])
            val unpatched = patchVmhost(text, stock)
            val patched = patchVmhost(unpatched, lines)
            File(copy.parentFile, "unpatched.swift").writeText(unpatched)
            File(copy.parentFile, "kotlin.swift").writeText(patched)
            File(copy.parentFile, "screen").writeText(screen.joinToString(" "))
            assertEquals(unpatched.lines().size, patched.lines().size)
            assertTrue(lines.values.all { it in patched } && stock.values.first() !in patched)
            assertEquals(patched, patchVmhost(patched, lines))
            if (patchedBefore && accessory(copy.parentFile.parentFile)) assertEquals(text, patched)  // what an earlier setup wrote on this Mac, from the same screen
        }
    }

    @Test
    fun failsOnAVmHostWithoutTheLines() {
        assertFailsWith<IllegalStateException> { patchVmhost("let graphics = VZMacGraphicsDeviceConfiguration()\n", vmhostLines(1, 1, 1)) }
    }

    @Test
    fun drySetup() = runBlocking {
        val sandbox = System.getenv("MOLE_VM_DRY") ?: return@runBlocking println("dry setup skipped: set MOLE_VM_DRY=<sandbox>")
        val vm = Vm(sandbox, agentDesktop!!, dry = true)
        println("-- dry setup of $sandbox:")
        vm.setup().forEach { println("   = $it") }
        println("-- dry patch of an unpatched copy:")
        val providers = File("/tmp/mole-vmhost-dry")
        for (copy in copies(providers)) copy.writeText(patchVmhost(copy.readText(), stock))
        println("   = changed: ${vm.patchVmhosts(providers)}")
    }
}
