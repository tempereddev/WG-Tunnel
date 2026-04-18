package com.zaneschepke.wireguardautotunnel.util.network

import android.net.Network
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

data class GeoIpResult(
    val ip: String,
    val countryName: String?,
    val countryCode: String?,
)

/**
 * Resolves the *exit* IP of an active tunnel by calling a geolocation API **through the tunnel's
 * Network object**. This is the only correct way to detect a tunnel's country: the endpoint
 * hostname in a WireGuard config often resolves to a load-balancer or relay IP that does not match
 * the actual egress IP.
 */
class GeoIpService(private val ioDispatcher: CoroutineDispatcher) {

    @Serializable
    private data class IpWhoIsResponse(
        @SerialName("success") val success: Boolean = false,
        @SerialName("ip") val ip: String? = null,
        @SerialName("country") val country: String? = null,
        @SerialName("country_code") val countryCode: String? = null,
        @SerialName("message") val message: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Looks up geographic info for the caller's *current* egress IP as seen by the API. When
     * [network] is provided the HTTPS request is bound to that Android [Network] so it traverses
     * the VPN tunnel. Returns null on any failure (API unreachable, JSON shape changed, timeout).
     *
     * Designed to degrade silently: tunnels that cannot reach the public internet (e.g. an Iran
     * tunnel behind domestic filtering) simply get no country info rather than crashing or
     * blocking the UI.
     */
    suspend fun lookupExitIp(
        network: Network?,
        timeoutMillis: Long = 8_000,
    ): GeoIpResult? =
        withContext(ioDispatcher) {
            try {
                withTimeout(timeoutMillis) { requestIpwhois(network) }
                    ?: withTimeout(timeoutMillis) { requestIpApi(network) }
            } catch (_: TimeoutCancellationException) {
                Timber.w("GeoIP lookup timed out")
                null
            } catch (t: Throwable) {
                Timber.w(t, "GeoIP lookup failed")
                null
            }
        }

    private fun requestIpwhois(network: Network?): GeoIpResult? {
        val body = httpGet(network, "https://ipwho.is/") ?: return null
        val parsed = runCatching { json.decodeFromString(IpWhoIsResponse.serializer(), body) }
            .getOrNull()
            ?: return null
        if (!parsed.success || parsed.ip == null) return null
        return GeoIpResult(
            ip = parsed.ip,
            countryName = parsed.country,
            countryCode = parsed.countryCode,
        )
    }

    @Serializable
    private data class IpApiResponse(
        @SerialName("status") val status: String? = null,
        @SerialName("country") val country: String? = null,
        @SerialName("countryCode") val countryCode: String? = null,
        @SerialName("query") val query: String? = null,
    )

    /** Fallback provider in case ipwho.is is unreachable. */
    private fun requestIpApi(network: Network?): GeoIpResult? {
        val body =
            httpGet(
                network,
                "https://ipapi.co/json/",
            ) ?: return null
        val parsed =
            runCatching {
                    val root = json.parseToJsonElement(body)
                    // ipapi.co uses different field names; parse minimally
                    val obj = root as? kotlinx.serialization.json.JsonObject ?: return null
                    GeoIpResult(
                        ip = obj["ip"]?.toString()?.trim('"') ?: return null,
                        countryName = obj["country_name"]?.toString()?.trim('"'),
                        countryCode = obj["country_code"]?.toString()?.trim('"'),
                    )
                }
                .getOrNull()
        return parsed
    }

    private fun httpGet(network: Network?, urlString: String): String? {
        val url = URL(urlString)
        val conn =
            if (network != null) {
                network.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "wgtunnel")
            val code = conn.responseCode
            if (code !in 200..299) {
                Timber.w("GeoIP HTTP $code for $urlString")
                null
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * Converts a two-letter ISO country code into its flag emoji. Returns empty string when
         * the code is missing or malformed so callers can concatenate safely.
         */
        fun codeToFlag(countryCode: String?): String {
            if (countryCode == null || countryCode.length != 2) return ""
            val upper = countryCode.uppercase()
            val first = upper[0]
            val second = upper[1]
            if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
            val base = 0x1F1E6 - 'A'.code
            return String(Character.toChars(base + first.code)) +
                String(Character.toChars(base + second.code))
        }
    }
}
