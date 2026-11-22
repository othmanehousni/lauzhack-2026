package ch.lauzhack.app.decider

import ch.lauzhack.app.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** tune/golden.jsonl: every prompt and grammar byte for byte as tune/prompt.py builds them. */
class GoldenTest {
    @Test
    fun reproducesEveryGoldenCase() {
        val cases = File("../tune/golden.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(243, cases.size)
        cases.forEachIndexed { i, c ->
            val s = state(c.getValue("input").jsonObject)
            assertEquals(c.str("prompt"), prompt(s), "prompt of case ${i + 1}")
            assertEquals(c.str("grammar"), grammar(s), "grammar of case ${i + 1}")
        }
    }

    private fun state(o: JsonObject) = State(
        screen = o.item("screen"),
        behind = o.item("behind") as Item.Slide?,
        next = o.item("next") as Item.Slide?,
        offered = o.getValue("offered").jsonArray.map { it.jsonPrimitive.int },
        shown = o.item("shown"),
        heard = o.getValue("heard").jsonArray.map { it.jsonPrimitive.content },
        now = o.str("now"),
        done = o.str("done") == "true",
        candidates = o.getValue("candidates").jsonArray.map { item(it.jsonObject) },
    )

    private fun JsonObject.item(key: String) = (get(key) as? JsonObject)?.let(::item)

    private fun item(o: JsonObject): Item = when (o.str("kind")) {
        "slide" -> Item.Slide(o.str("deck"), o.int("page"), o.int("pages"), o.str("title"), o.opt("text"), Target.Slide(o.str("deck"), o.int("page")))
        "file" -> Item.File(o.str("name"), o.opt("folder"), Target.File(o.str("name")))
        "site" -> Item.Site(o.str("name"), o.str("url"), Target.Site("https://" + o.str("url"), o.str("name")))
        else -> Item.App(o.str("name"), Target.App(o.str("name")))
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.opt(key: String) = get(key)?.jsonPrimitive?.content ?: ""
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
}
