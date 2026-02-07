@file:Suppress("CONTEXT_RECEIVERS_DEPRECATED")

package it.vfsfitvnm.providers.innertube.models

import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.json
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.parameters
import io.ktor.http.userAgent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    val user: User? = User()
) {
    @Serializable
    data class Client(
        @Transient
        val clientId: Int = 0,
        val clientName: String,
        val clientVersion: String,
        val platform: String? = null,
        val hl: String = "en",
        val gl: String = "US",
        @SerialName("visitorData")
        val defaultVisitorData: String? = null, // Made this nullable
        val androidSdkVersion: Int? = null,
        val userAgent: String? = null,
        val referer: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val acceptHeader: String? = null,
        val timeZone: String? = "UTC",
        val utcOffsetMinutes: Int? = 0,
        @Transient
        val apiKey: String? = null,
        @Transient
        val music: Boolean = false
    ) {
        @Serializable
        data class Configuration(
            @SerialName("PLAYER_JS_URL")
            val playerUrl: String? = null,
            @SerialName("WEB_PLAYER_CONTEXT_CONFIGS")
            val contextConfigs: Map<String, ContextConfig>? = null,
            @SerialName("VISITOR_DATA")
            val visitorData: String? = null,
            @SerialName("INNERTUBE_CONTEXT")
            val innertubeContext: Context
        ) {
            @Serializable
            data class ContextConfig(
                val jsUrl: String? = null
            )
        }

        @Transient
        private val mutex = Mutex()

        @Transient
        private var ytcfg: Configuration? = null

        private val baseUrl
            get() = when {
                platform == "TV" -> "https://www.youtube.com/tv"
                music -> "https://music.youtube.com/"
                else -> "https://www.youtube.com/"
            }

        // This getter is now corrected to always try fetching a fresh token
        val visitorData: String?
            get() {
                val config = ytcfg
                return config?.visitorData
                    ?: config?.innertubeContext?.client?.defaultVisitorData
                    ?: defaultVisitorData
            }

        companion object {
            private val YTCFG_REGEX = "ytcfg\\.set\\s*\\(\\s*(\\{[\\s\\S]+?\\})\\s*\\)".toRegex()
        }

        context(HttpMessageBuilder)
        fun apply() {
            userAgent?.let { userAgent(it) }

            headers {
                referer?.let { set("Referer", it) }
                set("X-Youtube-Bootstrap-Logged-In", "false")
                set("X-YouTube-Client-Name", clientId.toString())
                set("X-YouTube-Client-Version", clientVersion)
                apiKey?.let { set("X-Goog-Api-Key", it) }
                visitorData?.let { set("X-Goog-Visitor-Id", it) }
            }

            parameters {
                apiKey?.let { set("key", it) }
            }
        }

        suspend fun getConfiguration(): Configuration? = mutex.withLock {
            ytcfg ?: runCatching {
                val playerPage = Innertube.client.get(baseUrl) {
                    userAgent?.let { header("User-Agent", it) }
                }.bodyAsText()

                val objStr = YTCFG_REGEX
                    .find(playerPage)
                    ?.groups
                    ?.get(1)
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@runCatching null

                json.decodeFromString<Configuration>(objStr).also { ytcfg = it }
            }.getOrElse {
                it.printStackTrace()
                null
            }
        }
    }

    @Serializable
    data class ThirdParty(
        val embedUrl: String
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false
    )

    context(HttpMessageBuilder)
    fun apply() = client.apply()

    companion object {
        private val Context.withLang: Context
            get() {
                val locale = Locale.getDefault()

                return copy(
                    client = client.copy(
                        hl = locale
                            .toLanguageTag()
                            .replace("-Hant", "")
                            .takeIf { it in validLanguageCodes } ?: "en",
                        gl = locale
                            .country
                            .takeIf { it in validCountryCodes } ?: "US"
                    )
                )
            }
        // Removed the DEFAULT_VISITOR_DATA constant
        // This is the correct way to fix the issue.

        val DefaultWeb get() = DefaultWebNoLang.withLang


val DefaultWebNoLang = Context(
    client = Client(
        clientId = 67,
        clientName = "WEB_REMIX",
        clientVersion = "1.20260124.01.00",
        platform = "DESKTOP",
        userAgent = UserAgents.DESKTOP,
        referer = "https://music.youtube.com/",
        music = true
    )
)

val DefaultIOS = Context(
    client = Client(
        clientId = 5,
        clientName = "IOS",
        clientVersion = "21.03.1",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
        acceptHeader = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        userAgent = UserAgents.IOS,
        music = true
    )
)

val DefaultAndroid = Context(
    client = Client(
        clientId = 3,
        clientName = "ANDROID",
        clientVersion = "21.03.38",
        osName = "Android",
        osVersion = "14",
        platform = "MOBILE",
        androidSdkVersion = 34,
        userAgent = UserAgents.ANDROID,
        music = true
    )
)

val OnlyWeb = Context(
    client = Client(
        clientName = "WEB",
        clientVersion = "2.20260124.00.00",
        clientId = 1,
        userAgent = UserAgents.DESKTOP,
        music = true
    )
)

val WebCreator = Context(
    client = Client(
        clientName = "WEB_CREATOR",
        clientVersion = "1.20260124.00.00",
        clientId = 62,
        userAgent = UserAgents.DESKTOP,
        music = true
    )
)

val DefaultTV = Context(
    client = Client(
        clientId = 85,
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",  // Tetap 2.0 (konsisten dengan Metrolist)
        userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
        referer = "https://www.youtube.com/",
        music = true
    )
)

val DefaultVR = Context(
    client = Client(
        clientName = "ANDROID_VR",
        clientVersion = "1.61.48",  // Versi dari Metrolist ANDROID_VR_1_61_48
        clientId = 28,
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
        music = false
    )
)
    }
}

// @formatter:off
@Suppress("MaximumLineLength")
val validLanguageCodes =
    listOf("af", "az", "id", "ms", "ca", "cs", "da", "de", "et", "en-GB", "en", "es", "es-419", "eu", "fil", "fr", "fr-CA", "gl", "hr", "zu", "is", "it", "sw", "lt", "hu", "nl", "nl-NL", "no", "or", "uz", "pl", "pt-PT", "pt", "ro", "sq", "sk", "sl", "fi", "sv", "bo", "vi", "tr", "bg", "ky", "kk", "mk", "mn", "ru", "sr", "uk", "el", "hy", "iw", "ur", "ar", "fa", "ne", "mr", "hi", "bn", "pa", "gu", "ta", "te", "kn", "ml", "si", "th", "lo", "my", "ka", "am", "km", "zh-CN", "zh-TW", "zh-HK", "ja", "ko")

@Suppress("MaximumLineLength")
val validCountryCodes =
    listOf("DZ", "AR", "AU", "AT", "AZ", "BH", "BD", "BY", "BE", "BO", "BA", "BR", "BG", "KH", "CA", "CL", "HK", "CO", "CR", "HR", "CY", "CZ", "DK", "DO", "EC", "EG", "SV", "EE", "FI", "FR", "GE", "GH", "GR", "GT", "HN", "HU", "IS", "IN", "ID", "IQ", "IE", "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KR", "KW", "LA", "LV", "LB", "LY", "LI", "LT", "LU", "MK", "MY", "MT", "MX", "ME", "MA", "NP", "NL", "NZ", "NI", "NG", "NO", "OM", "PK", "PA", "PG", "PY", "PE", "PH", "PL", "PT", "PR", "QA", "RO", "RU", "SA", "SN", "RS", "SG", "SK", "SI", "ZA", "ES", "LK", "SE", "CH", "TW", "TZ", "TH", "TN", "TR", "UG", "UA", "AE", "GB", "US", "UY", "VE", "VN", "YE", "ZW")
// @formatter:on

@Suppress("MaximumLineLength")
object UserAgents {
    const val DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val ANDROID = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
    const val IOS = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
    const val TV = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"
}
