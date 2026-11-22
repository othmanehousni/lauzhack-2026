package ch.lauzhack.app.agent

import kotlin.math.sqrt

// Ported from brain.py at 8b2b549. The constants keep its names and values; its comments say why each is what it is.
private val SPACE = Regex("[\\s\\p{Z}\\x1c-\\x1f\\x85]+") // first: the word sets below are split with it
private val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]+")
internal const val CLASSIFY = "task: classification | query: "
internal const val QUERY = "task: search result | query: "
internal const val NGRAMS = 0.5f
internal const val BUCKETS = 4096
internal const val STEPS = 400
internal const val LR = 0.1f
internal const val L2 = 1e-5f
internal const val PARTIAL = 0.9
internal const val ASK = 0.95
internal const val FALLBACK_LOW = 0.25
internal const val JUMP = 0.02
internal const val MARGIN = 0.1
internal const val NAMES = 0.04
internal const val HERE_BONUS = 0.03
internal const val SUGGEST = 0.02
internal const val NAMED_MIN = 0.09
internal const val CLOSE = 0.02
internal const val LEAN = 0.05
internal const val FUZZY = 0.85

internal val SITES = linkedMapOf(
    "YouTube" to "https://www.youtube.com", "GitHub" to "https://github.com", "Google" to "https://www.google.com",
    "Gmail" to "https://mail.google.com", "Google Maps" to "https://www.google.com/maps", "Google Drive" to "https://drive.google.com",
    "Google Docs" to "https://docs.google.com", "Google Sheets" to "https://sheets.google.com", "LinkedIn" to "https://www.linkedin.com",
    "X (Twitter)" to "https://x.com", "Facebook" to "https://www.facebook.com", "Instagram" to "https://www.instagram.com",
    "Reddit" to "https://www.reddit.com", "Wikipedia" to "https://en.wikipedia.org", "Amazon" to "https://www.amazon.com",
    "Netflix" to "https://www.netflix.com", "Spotify" to "https://open.spotify.com", "Notion" to "https://www.notion.so",
    "Figma" to "https://www.figma.com", "Slack" to "https://app.slack.com", "Discord" to "https://discord.com/app",
    "ChatGPT" to "https://chatgpt.com", "Claude" to "https://claude.ai", "Hacker News" to "https://news.ycombinator.com",
    "Product Hunt" to "https://www.producthunt.com", "Stack Overflow" to "https://stackoverflow.com", "Devpost" to "https://devpost.com",
    "Hugging Face" to "https://huggingface.co", "arXiv" to "https://arxiv.org", "TikTok" to "https://www.tiktok.com",
    "Twitch" to "https://www.twitch.tv", "Medium" to "https://medium.com", "Apple" to "https://www.apple.com", "Vercel" to "https://vercel.com",
    "LauzHack" to "https://lauzhack.com", "EPFL" to "https://www.epfl.ch", "OpenAI" to "https://openai.com", "Anthropic" to "https://www.anthropic.com",
)
internal val WEB = setOf("website", "site", "page", "dot")
internal val NAMED = WEB + setOf("app", "application")
internal val GOVERNS = setOf("to", "open", "launch", "start", "into")
internal val TLDS = words("com org net edu gov io ai co ch fr de uk eu us app dev me tv so gg xyz info").toSet()
internal val NUMBERS: Map<String, Int> = words(
    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty",
).withIndex().associate { (i, w) -> w to i + 1 }.let { n -> n + n.entries.take(9).associate { (w, v) -> "twenty $w" to 20 + v } + ("thirty" to 30) }
internal val ORDINALS = mapOf("first" to 0, "top" to 0, "former" to 0, "second" to 1, "third" to 2, "fourth" to 3, "last" to -1, "bottom" to -1, "latter" to -1)
internal val KINDS = mapOf(
    ".pdf" to "PDF document", ".xlsx" to "spreadsheet", ".xls" to "spreadsheet", ".csv" to "spreadsheet", ".numbers" to "spreadsheet",
    ".pptx" to "presentation slides", ".key" to "presentation slides", ".docx" to "document", ".doc" to "document",
    ".pages" to "document", ".txt" to "notes", ".md" to "notes", ".jpg" to "photo", ".jpeg" to "photo", ".heic" to "photo",
    ".png" to "picture", ".gif" to "picture", ".mp4" to "video", ".mov" to "video", ".m4v" to "video", ".webm" to "video",
)
/** stage.py's ON_STAGE: files shown on the stage itself rather than in a tab of their own. */
internal val ON_STAGE = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp", ".avif", ".mp4", ".mov", ".m4v", ".webm")
internal val ENGLISH = words(
    """the a an and or but so to of in on at for with is are was were be been it it's this that these those i i'm
    you we they he she my our your their me us them not no yes can will would should could do does did have has had let's
    lets what how why where when which there here just like okay well now then if as from by about up out go going get want
    think see look know mean really very more one two all some""",
).toSet()
internal val FOREIGN = words(
    """le la les un une des du et est mais pas que qui je tu te il elle nous vous ils ce cette ça avec dans sur au aux
    très bien oui der das und ist nicht ich du er sie wir ein eine auf für zu den dem aber auch noch ja nein y es pero una
    por para muy también lo gli e è che sono""",
).toSet()

/** Python's str.split(). */
internal fun words(s: String): List<String> = s.split(SPACE).filter { it.isNotEmpty() }

/** Alphanumeric words, as written. */
internal fun alnum(s: String): List<String> = s.split(NOT_ALNUM).filter { it.isNotEmpty() }

/** Lowercase alphanumeric words. */
internal fun tokens(text: String): List<String> = alnum(text.lowercase())

internal fun english(text: String): Boolean {
    val w = words(text.lowercase().replace("-", " ")).map { it.trim('.', ',', ';', ':', '!', '?', '"', '(', ')') }
    return w.count { it in ENGLISH } >= w.count { it in FOREIGN }
}

/** How a word sounds, roughly, so a name the transcriber spells by ear ("lozac") sounds like the name meant ("LauzHack"). */
internal fun sound(name: String): String {
    var s = name.lowercase()
    for ((a, b) in listOf("ck" to "k", "ph" to "f", "x" to "ks", "c" to "k", "q" to "k", "z" to "s", "h" to "", "w" to "")) s = s.replace(a, b)
    val out = StringBuilder()
    for (ch in s) {
        val c = if (ch in "aeiouy") 'a' else ch
        if (out.isEmpty() || out.last() != c) out.append(c)
    }
    return out.toString()
}

/** The words cut to five letters, so "financials" matches "finance". */
internal fun stems(text: String): Set<String> = tokens(text).filter { it.length > 2 }.map { it.take(5) }.toSet()

/** A sentence's hashed words, word pairs and first and last words: the buckets set, and the value each holds. */
internal class Grams(val buckets: IntArray, val value: Float)

internal fun grams(text: String): Grams {
    val w = tokens(text)
    val ends = if (w.isEmpty()) emptyList() else listOf("^${w[0]}", "${w.last()}$", "${w.takeLast(2).joinToString(" ")}$")
    val all = w + w.zipWithNext { a, b -> "$a $b" } + ends
    val buckets = all.map { (crc32(it.encodeToByteArray()) % BUCKETS).toInt() }.distinct().sorted().toIntArray()
    return Grams(buckets, if (buckets.isEmpty()) 0f else NGRAMS / sqrt(buckets.size.toFloat()))
}

private val CRC = IntArray(256) { n -> (0 until 8).fold(n) { c, _ -> if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 } }

/** zlib.crc32. */
internal fun crc32(bytes: ByteArray): Long {
    var c = -1
    for (b in bytes) c = CRC[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return c.inv().toLong() and 0xffffffffL
}

/** A web address in the sentence, spoken ("lauzhack dot com") or written ("lauzhack.com"), as a URL. */
internal fun domain(said: String): String? {
    for (x in words(said.lowercase().replace("w w w ", "www ").replace(" dot ", "."))) {
        val host = x.trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')').split("//").last().split("/")[0]
        val dot = host.lastIndexOf('.')
        if (dot > 0 && host.substring(dot + 1) in TLDS) return "https://$host"
    }
    return null
}

/** The sentence split after a stop, question mark or comma; a clause of one or two words stays with its neighbour. */
internal fun clauses(said: String): List<String> {
    val parts = mutableListOf(mutableListOf<String>())
    for (w in words(said)) {
        parts.last() += w
        if (w.last() in ".?!,;:" && parts.last().size > 2) parts += mutableListOf<String>()
    }
    if (parts.size > 1 && parts.last().size <= 2) parts[parts.lastIndex - 1] += parts.removeAt(parts.lastIndex)
    return parts.filter { it.isNotEmpty() }.map { it.joinToString(" ") }
}

/** The texts a sentence is read by: itself, then each of its clauses if it has more than one. */
internal fun readings(text: String): List<String> = clauses(text).let { if (it.size > 1) listOf(text) + it else listOf(text) }

/** What a sentence embeds: each reading to classify, and the whole sentence to look things up with. */
internal fun prompts(text: String): List<String> = readings(text).map { CLASSIFY + it } + (QUERY + text)

private fun String.isDigits() = isNotEmpty() && all { it.isDigit() }

/** The slide number the sentence names ("slide 3", "page twenty one"), 1 for "first slide", -1 for "last slide". */
internal fun slideNumber(said: String): Int? {
    val w = tokens(said).filter { it != "number" }
    val slide = setOf("slide", "page")
    for (i in 0 until w.size - 1) {
        val x = w[i]
        val next = w[i + 1]
        if ((x == "first" || x == "last") && next in slide) return if (x == "first") 1 else -1
        if (x.dropLast(2).isDigits() && x.takeLast(2) in setOf("st", "nd", "rd", "th") && next in slide) return x.dropLast(2).toIntOrNull() ?: Int.MAX_VALUE
        if (x in slide) {
            val n = NUMBERS[w.subList(i + 1, minOf(i + 3, w.size)).joinToString(" ")] ?: NUMBERS[next]
                ?: if (next.isDigits()) next.toIntOrNull() ?: Int.MAX_VALUE else null
            if (n != null && n != 0) return n
        }
    }
    return null
}

/** pathlib's suffix: the file name's extension, with its dot. */
internal fun suffix(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.length - 1) name.substring(dot) else ""
}

/** A file as a document to search: its name, what kind of file it is, and the folders it is in. */
internal fun fileDoc(path: String): String {
    val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
    val parts = if (path.startsWith("/")) listOf("/") + segments else segments
    val folders = if (parts.getOrNull(1) == "Users") parts.drop(3).dropLast(1) else parts.drop(1).dropLast(1)
    val name = parts.lastOrNull() ?: ""
    val stem = name.removeSuffix(suffix(path))
    return "title: $stem | text: ${KINDS[suffix(path).lowercase()] ?: "file"} in ${folders.joinToString(" / ")}"
}

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0.0
    for (i in a.indices) s += a[i].toDouble() * b[i]
    return s.toFloat()
}
