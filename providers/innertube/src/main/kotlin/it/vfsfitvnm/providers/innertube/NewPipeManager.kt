package it.vfsfitvnm.providers.innertube

import it.vfsfitvnm.providers.innertube.models.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import it.vfsfitvnm.providers.innertube.models.UserAgents
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptExtractor
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
        TODO("Placeholder")
    }
}

object NewPipeUtils {

    init {
        // Inisialisasi NewPipe dengan timeout 30 detik
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy), 30000L)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            // 1. Coba URL langsung (tanpa cipher)
            format.url?.let { directUrl ->
                println("DEBUG: Using direct URL: $directUrl")
                return@runCatching directUrl
            }
            
            // 2. Jika ada signatureCipher, decode dengan NewPipe v0.22.6
            format.signatureCipher?.let { cipher ->
                println("DEBUG: Processing cipher for video: $videoId")
                val params = parseQueryString(cipher)
                val obfuscatedSignature = params["s"] 
                    ?: throw ParsingException("No signature in cipher")
                val signatureParam = params["sp"] ?: "signature"
                val baseUrl = params["url"] ?: throw ParsingException("No URL in cipher")
                
                // NewPipe v0.22.6 masih pake YoutubeJavaScriptExtractor
                val signature = YoutubeJavaScriptExtractor.decipherSignature(videoId, obfuscatedSignature)
                val finalUrl = "$baseUrl&$signatureParam=$signature"
                println("DEBUG: Generated URL with signature: $finalUrl")
                return@runCatching finalUrl
            }
            
            throw ParsingException("No URL or cipher found")
        }.onFailure { e ->
            // Simpan error untuk debugging
            println("ERROR in getStreamUrl: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
}
