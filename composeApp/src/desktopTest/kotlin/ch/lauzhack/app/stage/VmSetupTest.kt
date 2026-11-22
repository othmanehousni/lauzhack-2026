package ch.lauzhack.app.stage

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    )

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
            if (patchedBefore) assertEquals(text, patched)  // what an earlier setup wrote on this Mac, from the same screen
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
