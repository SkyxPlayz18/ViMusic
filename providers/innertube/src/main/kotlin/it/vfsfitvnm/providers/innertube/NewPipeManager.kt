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
            .addHeader("User-Agent", UserAgents.ANDROID) // ← PAKAI ANDROID, BUKAN DESKTOP

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
        
        // ← FIX INI! 5 PARAMETERS SEPERTI OUTERTUNE
        return Response(response.code, response.message, response.headers.toMultimap(), 
                        responseBodyToReturn, latestUrl)
    }

    // Outertune gak implement executeAsync, kita juga gak perlu
    // override fun executeAsync(...) { TODO() }
}

object NewPipeUtils {

    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            println("🔍 DEBUG: Getting stream for video: $videoId")
            println("🔍 DEBUG: Format URL: ${format.url}")
            println("🔍 DEBUG: Has cipher: ${format.signatureCipher != null}")
            
            val url = format.url ?: format.signatureCipher?.let { signatureCipher ->
                println("🔍 DEBUG: Processing cipher...")
                val params = parseQueryString(signatureCipher)
                
                val obfuscatedSignature = params["s"]
                    ?: {
                        println("❌ DEBUG: No 's' parameter in cipher")
                        throw ParsingException("Could not parse cipher signature")
                    }()
                    
                val signatureParam = params["sp"]
                    ?: {
                        println("❌ DEBUG: No 'sp' parameter in cipher")
                        throw ParsingException("Could not parse cipher signature parameter")
                    }()
                    
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: {
                        println("❌ DEBUG: No 'url' parameter in cipher")
                        throw ParsingException("Could not parse cipher url")
                    }()
                
                println("🔍 DEBUG: Deciphering signature...")
                val signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                    videoId,
                    obfuscatedSignature
                )
                
                url.parameters[signatureParam] = signature
                val resultUrl = url.toString()
                println("✅ DEBUG: Generated URL: ${resultUrl.take(100)}...")
                resultUrl
            } ?: {
                println("❌ DEBUG: No URL or cipher found")
                throw ParsingException("Could not find format url")
            }()

            println("✅ DEBUG: Final URL obtained")
            return@runCatching YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url
            )
        }.onFailure { e ->
            println("🔥 CRITICAL ERROR in getStreamUrl:")
            println("   Video ID: $videoId")
            println("   Error: ${e.javaClass.simpleName}")
            println("   Message: ${e.message}")
            e.printStackTrace()
        }
}
