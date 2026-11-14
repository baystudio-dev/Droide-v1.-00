package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor

internal data class AgentWebLink(val url: String, val title: String, val kind: String = "link")
internal data class AgentWebText(val title: String, val lines: List<String>, val links: List<AgentWebLink>,
    val textTruncated: Boolean, val linksTruncated: Boolean, val javascriptLikely: Boolean)

internal object AgentWebDocuments {
    const val MAX_TEXT_CHARS = 300_000
    const val MAX_LINKS = 500
    const val MAX_OUTPUT_CHARS = 24_000

    fun extract(response: AgentWebResponse): AgentWebText {
        val raw = response.text()
        if (response.contentType.isNotEmpty() && !response.contentType.contains("html", true) &&
            !response.contentType.startsWith("text/", true) && !response.contentType.contains("json", true) &&
            !response.contentType.contains("xml", true) && !response.contentType.contains("javascript", true)) {
            error("Unsupported web document type: ${response.contentType}; use the browser or an approved download workflow")
        }
        val html = response.contentType.contains("html", true) ||
            (response.contentType.isBlank() && Regex("(?is)^\\s*(?:<!doctype\\s+html|<html|<head|<body)").containsMatchIn(raw))
        if (!html) return text(raw, response.finalUrl.substringAfterLast('/').take(512))
        val doc = Jsoup.parse(raw, response.finalUrl)
        val hadScripts = doc.select("script").isNotEmpty()
        val links = mutableListOf<AgentWebLink>()
        val seen = mutableSetOf<String>()
        var linksTruncated = false
        var linkChars = 0
        // Resolve against the final URL and the document's base URL, never against the original redirect URL.
        for (element in doc.select("a[href],img[src]")) {
            val image = element.tagName() == "img"
            val resolved = AgentWebUrl.resolve(element.baseUri(), element.attr(if (image) "src" else "href")) ?: continue
            if (!seen.add(resolved)) continue
            if (links.size == MAX_LINKS || linkChars + resolved.length + 256 > 600_000) { linksTruncated = true; break }
            val label = if (image) element.attr("alt") else element.text()
            links += AgentWebLink(resolved, label.ifBlank { resolved }.take(256), if (image) "image" else "link")
            linkChars += resolved.length + 256
        }
        doc.select("script,style,noscript,template,svg,[hidden],[aria-hidden=true]").remove()
        val main = doc.selectFirst("main,article,[role=main]")?.takeIf { it.text().length > 100 } ?: doc.body()
        val output = StringBuilder()
        fun append(value: String) {
            val remaining = MAX_TEXT_CHARS + 1 - output.length
            if (remaining > 0) output.append(value.take(remaining))
        }
        var codeDepth = 0
        var visited = 0
        var structureTruncated = false
        val blocks = setOf("h1","h2","h3","h4","h5","h6","p","div","section","article","main","li","pre","blockquote","tr","table","br","hr")
        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                if (depth > 256) { structureTruncated = true; return NodeFilter.FilterResult.SKIP_ENTIRELY }
                if (++visited > 75_000 || output.length > MAX_TEXT_CHARS) {
                    structureTruncated = true; return NodeFilter.FilterResult.STOP
                }
                if (node is org.jsoup.nodes.TextNode) append(if (codeDepth > 0) node.wholeText else node.text())
                if (node is Element) {
                    if (node.tagName() in blocks) append("\n")
                    if (node.tagName() == "pre") codeDepth++
                    if (node.tagName() == "li") append("- ")
                    if (node.tagName() in setOf("td", "th") && output.isNotEmpty() && output.last() != '\n') append(" | ")
                }
                return NodeFilter.FilterResult.CONTINUE
            }
            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element) {
                    if (node.tagName() == "pre") codeDepth--
                    if (node.tagName() in blocks) append("\n")
                    else if (node.tagName() in setOf("span", "a", "strong", "em")) append(" ")
                }
                return NodeFilter.FilterResult.CONTINUE
            }
        }, main)
        val extracted = text(output.toString(), doc.title().take(512))
        return extracted.copy(links = links, linksTruncated = linksTruncated, textTruncated = extracted.textTruncated || structureTruncated,
            javascriptLikely = (hadScripts && extracted.lines.sumOf { it.length } < 120) ||
                Regex("(?i)(?:enable javascript|javascript (?:is )?required|you need to enable javascript)").containsMatchIn(extracted.lines.take(20).joinToString(" ")))
    }

    fun text(raw: String, title: String = ""): AgentWebText {
        val clipped = raw.take(MAX_TEXT_CHARS)
        val lines = clipped.replace("\r\n", "\n").replace('\r','\n').split('\n').flatMap { line ->
            // Wrapped lines make continuation and find offsets useful even for minified/very long text.
            val clean = line.replace('\u0000', ' ').trimEnd()
            if (clean.length <= 180) listOf(clean) else clean.chunked(180)
        }.filter { it.isNotBlank() }
        return AgentWebText(title, lines, emptyList(), raw.length > MAX_TEXT_CHARS, false, false)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal data class AgentWebReference(val id: String, val owner: String, val url: String, val title: String, val kind: String, val retrievedAt: String)
internal data class AgentWebDocument(val reference: AgentWebReference, val content: AgentWebText,
    val contentType: String, val status: Int, val digest: String, val bytesTruncated: Boolean,
    val file: File? = null, val pageCount: Int? = null) {
    val weight: Int get() = content.lines.sumOf { it.length } + content.links.sumOf { it.url.length + it.title.length }
}

internal class AgentWebStore {
    private val seed = UUID.randomUUID().toString().substring(0, 8)
    private var counter = 0L
    private val refs = LinkedHashMap<String, AgentWebReference>(32, 0.75f, true)
    private val documents = LinkedHashMap<String, AgentWebDocument>(24, 0.75f, true)
    private val filePins = mutableMapOf<File, Int>()
    private val retiredFiles = mutableSetOf<File>()
    private var closed = false

    @Synchronized fun reference(owner: String, url: String, title: String, kind: String, time: String): AgentWebReference {
        check(!closed) { "Web reference store is closed; reopen its source URL in a new session" }
        val ref = AgentWebReference("web_${seed}_${++counter}", owner, url, title.take(512), kind, time)
        refs[ref.id] = ref
        while (refs.size > 256) {
            val id = refs.keys.first(); refs.remove(id); removeDocument(id)
        }
        return ref
    }

    @Synchronized fun resolve(owner: String, id: String): AgentWebReference =
        refs[id]?.takeIf { it.owner == owner } ?: error("Web reference is expired or belongs to another session. Reopen its source URL.")

    @Synchronized fun document(owner: String, id: String): AgentWebDocument? {
        resolve(owner, id)
        return documents[id]
    }

    @Synchronized fun put(document: AgentWebDocument) {
        check(!closed) { "Web reference store is closed" }
        documents[document.reference.id] = document
        while (documents.size > 24 || documents.values.sumOf { it.weight } > 2_000_000 ||
            documents.values.sumOf { it.file?.length() ?: 0L } > 24L * 1024 * 1024) {
            removeDocument(documents.keys.first())
        }
    }

    /** A binary render may outlive cache eviction; its file is deleted when that render releases it. */
    @Synchronized fun lease(owner: String, id: String): AgentWebDocumentLease? =
        document(owner, id)?.let(::pin)

    @Synchronized fun putAndLease(document: AgentWebDocument): AgentWebDocumentLease {
        put(document)
        return pin(document)
    }

    private fun pin(document: AgentWebDocument): AgentWebDocumentLease {
        document.file?.let { filePins[it] = (filePins[it] ?: 0) + 1 }
        return AgentWebDocumentLease(document) { release(document.file) }
    }

    @Synchronized private fun release(file: File?) {
        if (file == null) return
        val remaining = (filePins[file] ?: 1) - 1
        if (remaining > 0) filePins[file] = remaining else {
            filePins.remove(file)
            if (retiredFiles.remove(file)) file.delete()
        }
    }

    @Synchronized fun isPinned(file: File): Boolean = file in filePins
    private fun removeDocument(id: String) {
        documents.remove(id)?.file?.let { if (it in filePins) retiredFiles += it else it.delete() }
    }
    @Synchronized fun close() { closed = true; documents.keys.toList().forEach(::removeDocument); refs.clear() }
    @Synchronized fun status(owner: String): JsonObject = buildJsonObject {
        put("success", true); put("kind", "status")
        put("references", refs.values.count { it.owner == owner })
        put("cached_documents", documents.values.count { it.reference.owner == owner })
        put("note", "References are session-scoped and bounded. After expiry/app restart, reopen the source URL.")
    }
}

internal class AgentWebDocumentLease(val document: AgentWebDocument, private val release: () -> Unit) : AutoCloseable {
    private var released = false
    @Synchronized override fun close() { if (!released) { released = true; release() } }
}
