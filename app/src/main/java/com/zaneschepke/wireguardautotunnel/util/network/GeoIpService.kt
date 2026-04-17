package com.zaneschepke.wireguardautotunnel.util.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import java.net.InetAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber

data class GeoIpResult(
    val ip: String,
    val countryName: String?,
    val countryCode: String?,
)

class GeoIpService(
    private val httpClient: HttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) {

    @Serializable
    private data class IpApiResponse(
        @SerialName("status") val status: String? = null,
        @SerialName("country") val country: String? = null,
        @SerialName("countryCode") val countryCode: String? = null,
        @SerialName("query") val query: String? = null,
        @SerialName("message") val message: String? = null,
    )

    /**
     * Resolves the host (domain or IP) to an IP and then fetches country/flag metadata from
     * ip-api.com (free, no key, 45 req/min per source IP). Returns null if lookup fails for any
     * reason — caller should treat as "unknown".
     */
    suspend fun lookup(host: String): GeoIpResult? =
        withContext(ioDispatcher) {
            runCatching {
                    val ip = InetAddress.getByName(host).hostAddress ?: host
                    val response: HttpResponse =
                        httpClient.get("http://ip-api.com/json/$ip?fields=status,message,country,countryCode,query")
                    val body: IpApiResponse = response.body()
                    if (body.status == "success") {
                        GeoIpResult(
                            ip = body.query ?: ip,
                            countryName = body.country,
                            countryCode = body.countryCode,
                        )
                    } else {
                        Timber.w("GeoIP lookup failed for $host: ${body.message}")
                        null
                    }
                }
                .onFailure { Timber.w(it, "GeoIP lookup error for $host") }
                .getOrNull()
        }

    companion object {
        /**
         * Converts a two-letter ISO country code to its flag emoji by mapping each letter to the
         * matching regional indicator codepoint. Returns empty string for invalid input.
         */
        fun codeToFlag(countryCode: String?): String {
            if (countryCode == null || countryCode.length != 2) return ""
            val upper = countryCode.uppercase()
            val first = upper[0]
            val second = upper[1]
            if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
            val base = 0x1F1E6 - 'A'.code
            val cp1 = base + first.code
            val cp2 = base + second.code
            return String(Character.toChars(cp1)) + String(Character.toChars(cp2))
        }
    }
}
