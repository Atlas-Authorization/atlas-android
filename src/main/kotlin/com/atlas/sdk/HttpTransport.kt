package com.atlas.sdk

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI

/** A single HTTP request, transport-agnostic. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** A single HTTP response. `headers` are lower-cased keys → the raw values. */
data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    /** All `Set-Cookie` values, however the server cased the header. */
    fun setCookies(): List<String> =
        headers.entries.firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }?.value ?: emptyList()
}

/**
 * The seam between the client and the network. `AtlasClient` depends only on
 * this, so tests inject a fake transport and run with no network and no
 * Gradle-fetched HTTP dependency (MockWebServer et al).
 */
interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * The production transport: `HttpURLConnection` from the JDK, so the SDK carries
 * no third-party HTTP dependency. Blocking; `AtlasClient` moves it to a
 * background dispatcher.
 */
class UrlConnectionTransport(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
        } catch (e: java.net.ProtocolException) {
            // A plain JVM's HttpURLConnection has no PATCH (Android's does); send it through the JDK client instead.
            if (request.method != "PATCH") throw AtlasException(AtlasError.Transport("We could not reach the server: ${e.message}"))
            return JdkHttpClientTransport(connectTimeoutMs, readTimeoutMs).execute(request)
        }
        connection.apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            // HttpURLConnection returns a null key for the status line; drop it so
            // downstream header lookups never NPE on a null key.
            val headers = connection.headerFields.entries.mapNotNull { entry ->
                entry.key?.let { it to entry.value }
            }.toMap()
            return HttpResponse(status = status, body = body, headers = headers)
        } catch (e: Exception) {
            throw AtlasException(AtlasError.Transport("We could not reach the server: ${e.message}"))
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * `java.net.http.HttpClient` (JDK 11+), used only for PATCH on a plain JVM. Kept in its own class so Android,
 * which has no `java.net.http`, never loads it. No redirects are followed, like [UrlConnectionTransport].
 */
internal class JdkHttpClientTransport(private val connectTimeoutMs: Int, private val readTimeoutMs: Int) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse = try {
        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(connectTimeoutMs.toLong()))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build()
        val body = request.body?.let { java.net.http.HttpRequest.BodyPublishers.ofString(it, Charsets.UTF_8) }
            ?: java.net.http.HttpRequest.BodyPublishers.noBody()
        val builder = java.net.http.HttpRequest.newBuilder(URI(request.url))
            .timeout(java.time.Duration.ofMillis(readTimeoutMs.toLong()))
            .method(request.method, body)
        // The JDK client sets these itself and refuses them as request headers.
        val restricted = setOf("connection", "content-length", "expect", "host", "upgrade")
        request.headers.forEach { (k, v) -> if (k.lowercase() !in restricted) builder.header(k, v) }
        val res = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        HttpResponse(status = res.statusCode(), body = res.body() ?: "", headers = res.headers().map())
    } catch (e: Exception) {
        throw AtlasException(AtlasError.Transport("We could not reach the server: ${e.message}"))
    }
}
