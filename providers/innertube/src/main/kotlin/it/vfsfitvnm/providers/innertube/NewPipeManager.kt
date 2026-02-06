package it.vfsfitvnm.providers.innertube

import it.vfsfitvnm.providers.innertube.models.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import it.vfsfitvnm.providers.innertube.models.UserAgents
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.*
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSignatureExtractor
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {

    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, dataToSend?.toRequestBody())
            .url(url)
            .addHeader("User-Agent", UserAgents.ANDROID)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()
        
        // NEWPIPE v0.25.0 RESPONSE FORMAT:
        return Response(
            response.code, 
            response.message, 
            response.headers.toMultimap(), 
            responseBodyToReturn,
            responseBodyToReturn?.toByteArray(),
            latestUrl
        )
    }

    override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
        // NewPipe v0.25.0 sudah support async
        val call = client.newCall(/* build request */)
        return object : CancellableCall {
            override fun cancel() = call.cancel()
            override fun isCanceled() = call.isCanceled()
        }
    }
}

object NewPipeUtils {

    init {
        // Initialize NewPipe v0.25.0
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            println("🔄 NewPipe v0.25.0 - Getting stream for: $videoId")
            
            // 1. Direct URL
            format.url?.let { directUrl ->
                println("✅ Direct URL found")
                return@runCatching directUrl
            }
            
            // 2. SignatureCipher - NEWPIPE v0.25.0 METHOD
            format.signatureCipher?.let { cipher ->
                println("🔐 Processing cipher...")
                val params = parseQueryString(cipher)
                
                val obfuscatedSignature = params["s"] 
                    ?: throw ParsingException("No signature in cipher")
                val signatureParam = params["sp"] ?: "signature"
                val baseUrl = params["url"] ?: throw ParsingException("No URL in cipher")
                
                // NEWPIPE v0.25.0 SIGNATURE DECIPHER:
                val signature = YoutubeSignatureExtractor.decipherSignature(
                    videoId, 
                    obfuscatedSignature,
                    null // playerUrl bisa null, NewPipe akan handle
                )
                
                val finalUrl = "$baseUrl&$signatureParam=$signature"
                println("✅ URL generated")
                return@runCatching finalUrl
            }
            
            throw ParsingException("No URL or cipher found")
        }.onFailure { e ->
            println("❌ NewPipe v0.25.0 Error: ${e.message}")
            e.printStackTrace()
        }
}
