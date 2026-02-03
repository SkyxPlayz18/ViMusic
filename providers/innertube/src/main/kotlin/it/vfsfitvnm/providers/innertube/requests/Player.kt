package it.vfsfitvnm.providers.innertube.requests

import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.util.generateNonce
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.NewPipeUtils
import it.vfsfitvnm.providers.innertube.YouTube
import it.vfsfitvnm.providers.innertube.json
import it.vfsfitvnm.providers.innertube.models.Context
import it.vfsfitvnm.providers.innertube.models.PlayerResponse
import it.vfsfitvnm.providers.innertube.models.bodies.PlayerBody
import it.vfsfitvnm.providers.utils.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request

// A dedicated OkHttpClient for validating stream URLs.
private val streamValidatorClient by lazy {
    OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()
}

/**
 * The main client is used for metadata and initial streams.
 * WEB is preferred for its reliability with metadata like loudnessDb.
 */
private val MAIN_CONTEXT = Context.DefaultWebNoLang

/**
 * Clients used for fallback streams in case the streams of the main client do not work.
 */
private val FALLBACK_CONTEXTS = arrayOf(
    Context.DefaultAndroid,
    Context.DefaultIOS,
    Context.DefaultTV,
    Context.DefaultVR,
    Context.OnlyWeb,
    Context.WebCreator
)

/**
 * An extension property to check if a PlayerResponse is initially valid for playback.
 */
private val PlayerResponse.isValid
    get() = playabilityStatus.status == "OK" &&
        streamingData?.adaptiveFormats?.any { it.url != null || it.signatureCipher != null } == true

/**
 * Finds the highest quality audio format from the available streams.
 */
private val PlayerResponse.StreamingData.highestQualityFormat: PlayerResponse.StreamingData.Format?
    get() = (adaptiveFormats + formats.orEmpty())
        .filter { it.isAudio }
        .maxByOrNull { it.bitrate }

/**
 * Sends a HEAD request to the given URL to check if it's a valid, working stream link.
 */
private fun validateStreamUrl(url: String): Boolean {
    return try {
        val request = Request.Builder().url(url).head().build()
        val response = streamValidatorClient.newCall(request).execute()
        response.isSuccessful.also { response.close() }
    } catch (_: Exception) {
        // Any exception during the request means it's not a valid stream.
        false
    }
}

/**
 * A helper to fetch a PlayerResponse for a specific context.
 */
private suspend fun Innertube.getPlayerResponse(
    body: PlayerBody,
    context: Context
): Result<PlayerResponse> = runCatching {
    logger.info("Trying ${context.client.clientName} ${context.client.clientVersion} ${context.client.platform}")

    // This call is necessary to check for client configuration errors, even if the result is not used.
    context.client.getConfiguration()
        ?: error("Failed to get configuration for client ${context.client.clientName}")

    val cpn = generateNonce(16).decodeToString()

    val httpResponse = client.post(if (context.client.music) PLAYER_MUSIC else PLAYER) {
        setBody(
            body.copy(
                context = context,
                cpn = cpn
            )
        )
        context.apply()
        parameter("t", generateNonce(12))
        header("X-Goog-Api-Format-Version", "2")
        parameter("id", body.videoId)
    }

    val responseAsText = httpResponse.bodyAsText()
    json.decodeFromString<PlayerResponse>(responseAsText)
}

/**
 * Fetches the PlayerResponse for a given video by trying a main client, then fallbacks.
 * It ensures metadata is consistent while finding a working stream URL.
 * Note: getSignatureTimestamp is not needed here because the selected clients work without it.
 */
suspend fun Innertube.player(body: PlayerBody): Result<PlayerResponse?>? = runCatchingCancellable {
    // 1. Fetch the main response to get reliable metadata
    val mainPlayerResponse = getPlayerResponse(body, MAIN_CONTEXT).getOrThrow()

    if (!mainPlayerResponse.isValid) {
        logger.warn("Main client response is not valid: ${mainPlayerResponse.playabilityStatus.reason}")
        return@runCatchingCancellable mainPlayerResponse // Return the invalid response to be handled by the caller
    }

    // Combine all available contexts, with the main one first.
    val allContexts = listOf(MAIN_CONTEXT) + FALLBACK_CONTEXTS

    for (context in allContexts) {
        if (!currentCoroutineContext().isActive) return@runCatchingCancellable null

        // For subsequent contexts, fetch their player responses.
        val currentPlayerResponse = if (context == MAIN_CONTEXT) {
            mainPlayerResponse
        } else {
            getPlayerResponse(body, context).getOrNull()
        }

        if (currentPlayerResponse == null || !currentPlayerResponse.isValid) {
            logger.warn("Skipping invalid response from ${context.client.clientName}")
            continue
        }

        // 2. Find the best format and try to get a URL
        val format = currentPlayerResponse.streamingData?.highestQualityFormat
        if (format == null) {
            logger.warn("No suitable format found for client: ${context.client.clientName}")
            continue
        }

        val streamUrl = NewPipeUtils.getStreamUrl(format, body.videoId).getOrNull()
        if (streamUrl == null) {
            logger.warn("Could not resolve stream URL for client: ${context.client.clientName}")
            continue
        }

        // 3. Validate the stream URL
        logger.info("Validating stream from ${context.client.clientName}...")
        if (validateStreamUrl(streamUrl)) {
            logger.info("Success! Found working stream with ${context.client.clientName}")

            // If we used a fallback, combine its stream data with the main client's metadata
            return@runCatchingCancellable if (context != MAIN_CONTEXT) {
                mainPlayerResponse.copy(streamingData = currentPlayerResponse.streamingData)
            } else {
                mainPlayerResponse
            }
        } else {
            logger.warn("Stream validation failed for ${context.client.clientName}")
        }
    }

    // 4. If no working stream was found after trying all clients, return null or the original invalid response
    logger.error("Could not find any working stream for videoId: ${body.videoId}")
    return@runCatchingCancellable null
}
