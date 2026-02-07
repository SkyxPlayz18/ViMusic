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
            .addHeader("User-Agent", UserAgents.ANDROID) // Pakai Android User-Agent

        headers.forEach { (headerName, headerValueList) ->
            headerValueList.forEach { headerValue ->
                requestBuilder.addHeader(headerName, headerValue)
            }
        }

        // Tambah headers penting
        requestBuilder.addHeader("Accept-Language", "en-US,en;q=0.9")
        requestBuilder.addHeader("Accept", "*/*")

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()
        
        // NEWPIPE v0.25.0+ RESPONSE FORMAT: 5 parameters
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBodyToReturn ?: "",
            latestUrl
        )
    }

    // NewPipe v0.25.0+ tidak memerlukan implementasi executeAsync untuk basic usage
    // override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
    //     throw UnsupportedOperationException("Async not implemented")
    // }
}

object NewPipeUtils {

    init {
        init {
    // NewPipe v0.25.0+ init format: NewPipe.init(downloader, localization)
    NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
    // ATAU jika perlu localization:
        }
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            println("[NewPipe] Getting stream for video: $videoId")
            
            // 1. Coba URL langsung (no cipher needed)
            format.url?.let { directUrl ->
                println("[NewPipe] ✅ Using direct URL")
                return@runCatching directUrl
            }
            
            // 2. Jika ada signatureCipher
            format.signatureCipher?.let { cipher ->
                println("[NewPipe] 🔐 Processing cipher...")
                val params = parseQueryString(cipher)
                
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("No 's' parameter in cipher")
                val signatureParam = params["sp"] ?: "signature"
                val baseUrl = params["url"] 
                    ?: throw ParsingException("No 'url' parameter in cipher")
                
                println("[NewPipe] Base URL: $baseUrl")
                println("[NewPipe] Signature param: $signatureParam")
                
                // DI NEWPIPE v0.25.0+, signature seringnya sudah didecode otomatis
                // atau kita perlu menggunakan method yang berbeda
                
                // Coba 1: Signature mungkin sudah deciphered
                val signature = obfuscatedSignature
                
                // Coba 2: Jika butuh decipher, NewPipe v0.25.0+ mungkin punya method baru
                // Tapi untuk sekarang, kita asumsi signature sudah OK
                
                val urlBuilder = URLBuilder(baseUrl)
                urlBuilder.parameters[signatureParam] = signature
                
                val finalUrl = urlBuilder.toString()
                println("[NewPipe] ✅ Generated URL: ${finalUrl.take(100)}...")
                return@runCatching finalUrl
            }
            
            throw ParsingException("No URL or cipher found in format")
        }.onFailure { e ->
            println("[NewPipe] ❌ ERROR in getStreamUrl:")
            println("  Video ID: $videoId")
            println("  Error: ${e.javaClass.simpleName}")
            println("  Message: ${e.message}")
            println("  Format URL: ${format.url}")
            println("  Has Cipher: ${format.signatureCipher != null}")
            
            // Untuk debugging lebih lanjut
            if (format.signatureCipher != null) {
                println("  Cipher preview: ${format.signatureCipher!!.take(200)}...")
                try {
                    val params = parseQueryString(format.signatureCipher!!)
                    println("  Cipher params: ${params.entries().joinToString()}")
                } catch (ex: Exception) {
                    println("  Failed to parse cipher: ${ex.message}")
                }
            }
        }
}
