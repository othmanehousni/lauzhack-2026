package ch.lauzhack.app.stage

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Showable files in the folders the VM mounts, so their paths are the same in the VM. Spotlight lists them once in the
 * background (about 13 s for 150k files); after that a lookup is a scan of names in memory. Until then, and for files
 * newer than the list, Spotlight answers directly in about 0.8 s. */
class Files {
    private val places = FOLDERS.flatMap { listOf("-onlyin", File(HOME, it).path) }
    private val repos = ConcurrentHashMap<String, Boolean>()
    private val names: CompletableFuture<List<Pair<String, String>>> = CompletableFuture.supplyAsync {
        spotlight(KINDS.joinToString(" || ") { "kMDItemContentTypeTree == \"$it\"" }).map { it.substringAfterLast('/').lowercase() to it }
    }

    private fun inRepository(dir: File?): Boolean =
        dir != null && repos.getOrPut(dir.path) { File(dir, ".git").exists() || inRepository(dir.parentFile) }

    /** Not inside a hidden folder, a package, a library of code or a code repository, where a `.ts` file is TypeScript,
     * not a video. */
    private fun wanted(path: String): Boolean {
        val parts = path.split('/').dropLast(1)
        return parts.none { it.startsWith(".") || it in setOf("node_modules", "site-packages", "Library") || it.endsWith(".app") || it.endsWith(".framework") || it.endsWith(".bundle") } &&
            !inRepository(File(path).parentFile)
    }

    private fun spotlight(query: String) = exec(listOf("mdfind") + places + query, check = false).lines().filter { it.isNotEmpty() && wanted(it) }

    /** The n most recently changed files, for the brain to search by meaning. Waits up to a minute for the list. */
    fun newest(n: Int = 1500): List<String> =
        // ponytail: newest by mtime only; a presenter's older file is still found by name through find()
        runCatching { names.get(60, TimeUnit.SECONDS) }.getOrDefault(emptyList())
            .map { it.second to File(it.second).lastModified() }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(n).map { it.first }

    /** The newest file whose name holds every word of the query (its first three). */
    fun find(query: String): String? {
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty).take(3)
        if (words.isEmpty()) return null
        val hits = names.getNow(emptyList()).filter { (name, _) -> words.all { it in name } }.map { it.second }
            .ifEmpty { spotlight(words.joinToString(" && ") { "kMDItemDisplayName == \"*$it*\"cd" }) }
        return hits.map(::File).filter(File::exists).maxByOrNull(File::lastModified)?.path
    }

    companion object {
        val KINDS = listOf("public.image", "public.movie", "com.adobe.pdf", "public.presentation", "public.spreadsheet", "public.composite-content", "public.html")
    }
}
