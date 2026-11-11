package com.openyt.data

import com.openyt.core.automation.FeedEpisode
import com.openyt.core.automation.FeedReader
import com.openyt.core.application.UrlValidation
import com.openyt.core.application.UrlValidator
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads user-selected RSS/Atom feeds without external entities or unbounded input. */
class RssFeedReader : FeedReader {
    override suspend fun episodes(url: String): List<FeedEpisode> = withContext(Dispatchers.IO) {
        require(UrlValidator().validate(url) is UrlValidation.Valid)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "OpenYT")
        try {
            check(connection.responseCode in 200..299)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_FEED_BYTES) { "feed is too large" }
                    output.write(buffer, 0, read)
                }
            }
            parse(output.toByteArray().toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    companion object {
        private const val MAX_FEED_BYTES = 4 * 1024 * 1024
        fun parse(xml: String): List<FeedEpisode> {
            require(xml.length <= MAX_FEED_BYTES && !xml.contains("<!DOCTYPE", ignoreCase = true)) { "unsafe or oversized feed" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(InputSource(StringReader(xml)))
            val items = document.getElementsByTagName("item")
            val atom = document.getElementsByTagNameNS("*", "entry")
            val nodes = if (items.length > 0) items else atom
            return (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as? Element ?: return@mapNotNull null
                val enclosures = element.getElementsByTagName("enclosure")
                val links = element.getElementsByTagNameNS("*", "link")
                val enclosure = (0 until enclosures.length).mapNotNull { enclosures.item(it) as? Element }
                    .firstOrNull()?.getAttribute("url")
                    ?: (0 until links.length).mapNotNull { links.item(it) as? Element }
                        .firstOrNull { it.getAttribute("rel") == "enclosure" }?.getAttribute("href")
                    ?: return@mapNotNull null
                val valid = UrlValidator().validate(enclosure) as? UrlValidation.Valid ?: return@mapNotNull null
                fun text(name: String): String? = element.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
                FeedEpisode(text("guid") ?: text("id") ?: valid.url, text("title") ?: "Podcast", valid.url)
            }.distinctBy { it.id }
        }
    }
}
