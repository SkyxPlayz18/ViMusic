package it.vfsfitvnm.providers.innertube

import it.vfsfitvnm.providers.innertube.models.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import it.vfsfitvnm.providers.innertube.models.UserAgents
import okhttp3.OkHttpClient
import okhttp3.RequestBody
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

        // FIX 1: Handle nullable RequestBody untuk v0.25.2
        when (request.httpMethod()) {
            "GET" -> Unit // default
            "POST" -> {
                val body = request.dataToSend()?.toRequestBody()
                requestBuilder.post(body ?: "".toRequestBody()) // NON-NULL FIX
                requestBuilder.addHeader("Content-Type", "application/x-www-form-urlencoded")
            }
            else -> {
                val body = request.dataToSend()?.toRequestBody()
                requestBuilder.method(request.httpMethod(), body ?: "".toRequestBody())
            }
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
        
        // FIX 2: Response constructor untuk v0.25.2
        // Coba constructor dengan 5 parameter (tanpa byte array)
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString()
        )
    }
}

object NewPipeUtils {

    init {
        // Init NewPipe dengan proxy
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
        
        // FIX 3: v0.25.2 TIDAK PERLU init() untuk YoutubeJavaScriptPlayerManager
        // Class ini auto-init atau init via NewPipe.init()
        println("[NewPipe] Initialized for NewPipe v0.25.2")
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            // 1. Direct URL
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

            // FIX 4: Method deobfuscateSignature mungkin berubah di v0.25.2
            val signature = try {
                // Coba dengan 2 parameter (videoId, signature)
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, obfuscatedSignature)
            } catch (e: NoSuchMethodError) {
                // Fallback ke method lama (1 parameter)
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(obfuscatedSignature)
            }

            // Build URL
            val urlBuilder = URLBuilder(baseUrl)
            urlBuilder.parameters[signatureParam] = signature

            // Add other params
            params.entries().forEach { (key, values) ->
                if (key !in listOf("s", "sp", "url")) {
                    values.forEach { value -> urlBuilder.parameters.append(key, value) }
                }
            }

            val urlWithSignature = urlBuilder.toString()

            // FIX 5: getUrlWithThrottlingParameterDeobfuscated mungkin juga berubah
            return@runCatching try {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                    videoId,
                    urlWithSignature
                )
            } catch (e: Exception) {
                // Fallback ke URL tanpa throttling parameter
                println("[NewPipe] Warning: Failed to add throttling parameter: ${e.message}")
                urlWithSignature
            }
        }.onFailure { e ->
            println("[NewPipe] ERROR getting stream URL for $videoId: ${e.message}")
        }
}
