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
            "GET" -> Unit
            "POST" -> {
                val body = request.dataToSend()?.toRequestBody() ?: "".toRequestBody()
                requestBuilder.post(body)
                requestBuilder.addHeader("Content-Type", "application/x-www-form-urlencoded")
            }
            else -> {
                val body = request.dataToSend()?.toRequestBody() ?: "".toRequestBody()
                requestBuilder.method(request.httpMethod(), body)
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
        
        // v0.25.2: Response dengan 5 parameter (tanpa byte array)
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
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
        println("[NewPipe] Initialized for NewPipe v0.25.2")
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            // 1. Direct URL
            if (format.url != null) {
                println("[NewPipe] ✅ Direct URL for $videoId")
                return@runCatching format.url!!
            }

            // 2. Signature cipher
            val cipher = format.signatureCipher ?: throw ParsingException("No URL or cipher found")
            
            val params = parseQueryString(cipher)
            val obfuscatedSignature = params["s"] ?: throw ParsingException("No 's' in cipher")
            val signatureParam = params["sp"] ?: "signature"
            val baseUrl = params["url"] ?: throw ParsingException("No 'url' in cipher")

            println("[NewPipe] 🔐 Deciphering for $videoId")

            // v0.25.2: deobfuscateSignature dengan 2 PARAMETER!
            val signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                videoId,              // VIDEO ID
                obfuscatedSignature   // OBFUSCATED SIGNATURE
            )

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
            println("[NewPipe] 🔗 URL with signature: ${urlWithSignature.take(100)}...")

            // v0.25.2: getUrlWithThrottlingParameterDeobfuscated
            val finalUrl = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                urlWithSignature
            )
            
            println("[NewPipe] ✅ Final URL: ${finalUrl.take(100)}...")
            return@runCatching finalUrl
        }.onFailure { e ->
            println("[NewPipe] ❌ ERROR for $videoId: ${e.javaClass.simpleName}")
            println("  Message: ${e.message}")
            e.printStackTrace()
        }
}
