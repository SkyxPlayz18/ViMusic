package it.vfsfitvnm.providers.innertube

import it.vfsfitvnm.providers.innertube.models.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import it.vfsfitvnm.providers.innertube.models.UserAgents
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {

    private val client = OkHttpClient.Builder()
        .apply { if (proxy != null) proxy(proxy) }
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .addHeader("User-Agent", UserAgents.DESKTOP)

        when (request.httpMethod()) {
            "GET" -> Unit // default is GET
            "POST" -> {
                requestBuilder.post(request.dataToSend()?.toRequestBody())
                requestBuilder.addHeader("Content-Type", "application/x-www-form-urlencoded")
            }
            else -> requestBuilder.method(request.httpMethod(), request.dataToSend()?.toRequestBody())
        }

        request.headers().forEach { (name, values) ->
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }

        requestBuilder.addHeader("Accept-Language", "en-US,en;q=0.9")

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val responseBody = response.body?.string()
        
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            responseBody?.toByteArray(),
            response.request.url.toString()
        )
    }
}

object NewPipeUtils {

    init {
        // Init NewPipe dengan proxy support
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
        
        // Juga init YoutubeJavaScriptPlayerManager untuk signature decipher
        try {
            YoutubeJavaScriptPlayerManager.init(YouTube.proxy)
        } catch (e: Exception) {
            println("[NewPipe] Warning: Failed to init YoutubeJavaScriptPlayerManager: ${e.message}")
        }
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            // 1. Direct URL (no cipher)
            if (format.url != null) {
                println("[NewPipe] Using direct URL for $videoId")
                return@runCatching format.url!!
            }

            // 2. Signature cipher
            val cipher = format.signatureCipher ?: throw ParsingException("No URL or cipher found")
            
            val params = parseQueryString(cipher)
            val obfuscatedSignature = params["s"] ?: throw ParsingException("No 's' in cipher")
            val signatureParam = params["sp"] ?: "signature"
            val baseUrl = params["url"] ?: throw ParsingException("No 'url' in cipher")

            println("[NewPipe] Deciphering signature for $videoId")

            // DECIPHER SIGNATURE menggunakan NewPipe v0.25.0+
            val signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                videoId,
                obfuscatedSignature
            )

            // Build URL dengan signature
            val urlBuilder = URLBuilder(baseUrl)
            urlBuilder.parameters[signatureParam] = signature

            // Add other cipher parameters
            params.entries().forEach { (key, values) ->
                if (key !in listOf("s", "sp", "url")) {
                    values.forEach { value -> urlBuilder.parameters.append(key, value) }
                }
            }

            val urlWithSignature = urlBuilder.toString()

            // ADD THROTTLING PARAMETER (penting untuk v0.25.0+)
            return@runCatching YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                urlWithSignature
            )
        }.onFailure { e ->
            println("[NewPipe] ERROR getting stream URL for $videoId: ${e.message}")
            e.printStackTrace()
        }
}
