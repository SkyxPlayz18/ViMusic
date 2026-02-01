package it.vfsfitvnm.providers.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.NewPipeUtils
import it.vfsfitvnm.providers.innertube.models.Context
import it.vfsfitvnm.providers.innertube.models.PlayerResponse
import it.vfsfitvnm.providers.innertube.models.bodies.PlayerBody
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest

// ✅ Define main and fallback contexts
private val MAIN_CONTEXT = Context.DefaultWeb
private val FALLBACK_CONTEXTS = listOf(
    Context.DefaultAndroid,
    Context.DefaultIOS,
    Context.DefaultTV,
    Context.DefaultVR,
    Context.DefaultWebNoLang,
    Context.DefaultWebCreator
)

// ✅ Extension to check if PlayerResponse is valid
private val PlayerResponse.isValid: Boolean
    get() = playabilityStatus?.status == "OK" && streamingData != null

// ✅ Extension to get highest quality format
private val PlayerResponse.StreamingData.highestQualityFormat: Format?
    get() = adaptiveFormats
        ?.filter { it.mimeType?.startsWith("audio/") == true }
        ?.maxByOrNull { it.bitrate ?: 0 }
        ?: formats?.firstOrNull { it.mimeType?.startsWith("audio/") == true }

// ✅ Function to validate stream URL
private fun validateStreamUrl(url: String): Boolean {
    return runCatching {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .build()
        
        val request = OkHttpRequest.Builder()
            .url(url)
            .head()
            .build()
        
        val response = client.newCall(request).execute()
        val isValid = response.isSuccessful
        response.close()
        isValid
    }.getOrElse { false }
}

suspend fun Innertube.player(body: PlayerBody): Result<PlayerResponse>? = runCatching {
    // ✅ Step 1: Fetch main player response
    val mainPlayerResponse = client.post("/player") {
        setBody(body.copy(context = MAIN_CONTEXT))
        mask("playabilityStatus.status,playerConfig.audioConfig,streamingData.adaptiveFormats,streamingData.formats,videoDetails.videoId")
    }.body<PlayerResponse>()

    // ✅ Step 2: If main response is invalid, return it
    if (!mainPlayerResponse.isValid) {
        return@runCatching mainPlayerResponse
    }

    // ✅ Step 3: Try to find a working stream URL
    val contextsToTry = listOf(MAIN_CONTEXT) + FALLBACK_CONTEXTS

    for (context in contextsToTry) {
        runCatching {
            // Fetch player response for current context
            val playerResponse = if (context == MAIN_CONTEXT) {
                mainPlayerResponse
            } else {
                client.post("/player") {
                    setBody(body.copy(context = context))
                    mask("streamingData.adaptiveFormats,streamingData.formats")
                }.body<PlayerResponse>()
            }

            // Skip if invalid or no suitable format
            if (!playerResponse.isValid) return@runCatching

            val format = playerResponse.streamingData?.highestQualityFormat
                ?: return@runCatching

            // Resolve stream URL
            val streamUrl = NewPipeUtils.getStreamUrl(format, body.videoId)
                .getOrNull() ?: return@runCatching

            // Validate stream URL
            if (!validateStreamUrl(streamUrl)) return@runCatching

            // ✅ Success! Return main response with working streamingData
            return@runCatching if (context == MAIN_CONTEXT) {
                mainPlayerResponse
            } else {
                mainPlayerResponse.copy(
                    streamingData = playerResponse.streamingData
                )
            }
        }.onFailure {
            // Continue to next context on failure
            continue
        }.onSuccess {
            // Return successful result
            return@runCatching it
        }
    }

    // ✅ No working stream found, return null
    return@runCatching null
}.getOrNull()
