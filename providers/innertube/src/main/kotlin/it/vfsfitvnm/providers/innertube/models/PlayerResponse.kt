package it.vfsfitvnm.providers.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus?,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String?,
        val reason: String?
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig?
    ) {
        @Serializable
        data class AudioConfig(
            val loudnessDb: Double?
        )
    }

    @Serializable
    data class StreamingData(
        val adaptiveFormats: List<Format>?,
        val formats: List<Format>?,
        val expiresInSeconds: Int?
    ) {
        @Serializable
        data class Format(
            val itag: Int?,
            val mimeType: String?,
            val bitrate: Int?,
            val averageBitrate: Int?,
            val contentLength: Long?,
            val audioQuality: String?,
            val audioSampleRate: Int?,
            val audioChannels: Int?,
            val loudnessDb: Double?,
            val url: String?,
            val signatureCipher: String?
        ) {
            val isAudio: Boolean
                get() = mimeType?.startsWith("audio/") == true
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String?,
        val title: String?,
        val lengthSeconds: String?,
        val channelId: String?,
        val author: String?
    )
}
