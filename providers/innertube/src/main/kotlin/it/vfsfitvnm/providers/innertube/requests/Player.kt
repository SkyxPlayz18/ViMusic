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

// ✅ Define main and fallback contexts (hanya yang ada di ViMusic)
private val MAIN_CONTEXT = Context.DefaultWeb
private val FALLBACK_CONTEXTS = listOf(
    Context.DefaultAndroid,
    Context.DefaultIOS,
    Context.DefaultTV,
    Context.DefaultVR,
    Context.DefaultWebNoLang
    // DefaultWebCreator dihapus karena ga ada di ViMusic
)

// ✅ Extension to check if PlayerResponse is valid
private val PlayerResponse.isValid: Boolean
    get() = playabilityStatus?.status == "OK" && streamingData != null

// ✅ Extension to get highest quality format
private val PlayerResponse.StreamingData.highestQualityFormat: PlayerResponse.StreamingData.Format?
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

suspend fun Innertube.player(body: PlayerBody): PlayerResponse? {
    return runCatching {
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
            val result = runCatching {
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
                if (!playerResponse.isValid) return@runCatching null

                val format = playerResponse.streamingData?.highestQualityFormat
                    ?: return@runCatching null

                // Resolve stream URL
                val streamUrl = NewPipeUtils.getStreamUrl(format, body.videoId)
                    .getOrNull() ?: return@runCatching null

                // Validate stream URL
                if (!validateStreamUrl(streamUrl)) return@runCatching null

                // ✅ Success! Return main response with working streamingData
                if (context == MAIN_CONTEXT) {
                    mainPlayerResponse
                } else {
                    mainPlayerResponse.copy(
                        streamingData = playerResponse.streamingData
                    )
                }
            }

            result.getOrNull()?.let { return@runCatching it }
        }

        // ✅ No working stream found, return null
        null
    }.getOrNull()
}
