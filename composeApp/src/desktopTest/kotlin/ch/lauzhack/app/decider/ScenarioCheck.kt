package ch.lauzhack.app.decider

import ch.lauzhack.app.Heard
import ch.lauzhack.app.engine.Engines
import ch.lauzhack.app.engine.LiteRTClientConfig
import ch.lauzhack.app.engine.LiteRTLLMClient
import ch.lauzhack.app.stage.decks
import ch.lauzhack.app.stage.documents
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** A rehearsal scenario through [ModelAgent] on this Mac's two models, as --typed feeds it, without the shared screen:
 * MOLE_CHECK=scenario, MOLE_LIBRARY the decks and documents, MOLE_SCENARIO files of rehearsal/, comma-separated. Prints each line's
 * candidates, the answer and what is on screen after it. */
class ScenarioCheck {
    @Test
    fun scenario() = runBlocking {
        assumeTrue("MOLE_CHECK=scenario runs this", System.getenv("MOLE_CHECK") == "scenario")
        val library = File(System.getenv("MOLE_LIBRARY"))
        val engines = Engines("${System.getProperty("user.home")}/Library/Application Support/Moliere/models", createTempDirectory("check").toString())
        val embedder = engines.embedder()
        val pages = decks(library, createTempDirectory("decks").toFile(), width = 64)
        val apps = listOf("Notes", "Calculator", "Safari", "System Settings", "Maps", "Photos", "Preview", "Finder", "Keynote", "Mail", "Terminal", "Google Chrome")
        val catalog = Catalog.build(items(pages, apps, documents(library)), embedder, meeting = pages.map { it.deck }.toSet())
        val decide = decider(LiteRTLLMClient(LiteRTClientConfig(engines.modelsDir, engines.cacheDir, backends = listOf(Backend.CPU()))))
        for (scenario in System.getenv("MOLE_SCENARIO").split(',')) {
            val agent = ModelAgent(catalog, embedder, decide, note = { if (it.startsWith("candidates")) println("    " + it.substringAfter("  ").substringAfter("  ")) })
            val desk = FakeDesk()
            val said = mutableListOf<String>()
            for (line in File(scenario).readLines().filter { it.isNotBlank() && !it.startsWith("@") }) {
                if (line.startsWith("#")) { println(line); continue }
                val words = line.substringBefore(" || ")
                desk.apply(agent.anythingToDo(Heard(words, true, said.size + 1, said.takeLast(3)), desk.onScreen))
                println("${words.padEnd(60)} want ${line.substringAfter(" || ", "").padEnd(34)} ${agent.answer}  ->  ${desk.onScreen.target}")
                said += words
            }
        }
    }
}
