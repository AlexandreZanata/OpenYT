package com.openyt.data

import com.openyt.core.automation.FeedEpisode
import com.openyt.core.automation.FeedReader
import com.openyt.core.application.UrlValidation
import com.openyt.core.application.UrlValidator
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads user-selected RSS/Atom feeds without external entities or unbounded input. */
class RssFeedReader internal constructor(private val client: OkHttpClient) : FeedReader {
    constructor() : this(httpClient)
    override suspend fun episodes(url: String): List<FeedEpisode> {
        require(UrlValidator().validate(url) is UrlValidation.Valid)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", "OpenYT").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val episodes = response.use {
                            check(it.isSuccessful)
                            val output = ByteArrayOutputStream()
                            checkNotNull(it.body).byteStream().use { input ->
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    continuation.context.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    require(output.size() + read <= MAX_FEED_BYTES) { "feed is too large" }
                                    output.write(buffer, 0, read)
                                }
                            }
                            continuation.context.ensureActive()
                            parse(output.toByteArray().toString(Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(episodes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        private val httpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
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
                val enclosureElement = (0 until enclosures.length).mapNotNull { enclosures.item(it) as? Element }.firstOrNull()
                val enclosure = enclosureElement?.getAttribute("url")
                    ?: (0 until links.length).mapNotNull { links.item(it) as? Element }
                        .firstOrNull { it.getAttribute("rel") == "enclosure" }?.getAttribute("href")
                    ?: return@mapNotNull null
                val valid = UrlValidator().validate(enclosure) as? UrlValidation.Valid ?: return@mapNotNull null
                fun text(name: String): String? = element.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
                val duration = text("duration")?.let { value ->
                    val pieces = value.split(':')
                    val numbers = pieces.map { it.toLongOrNull()?.takeIf { number -> number >= 0 } }
                    if (pieces.size in 1..3 && numbers.all { it != null } && numbers.drop(1).all { it!! < 60 }) {
                        numbers.fold(0L as Long?) { total, part -> total?.takeIf { it <= (Long.MAX_VALUE - part!!) / 60 }?.let { it * 60 + part!! } }
                    } else null
                }
                val length = enclosureElement?.getAttribute("length")?.toLongOrNull()?.takeIf { it > 0 }
                FeedEpisode(text("guid") ?: text("id") ?: valid.url, text("title") ?: "Podcast", valid.url, duration, length)
            }.distinctBy { it.id }
        }
    }
}
