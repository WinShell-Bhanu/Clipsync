package com.bunty.clipsync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LocationHelper is a lightweight singleton that determines the user's country at runtime
 * by querying a public IP-geolocation API, enabling ClipSync to automatically connect to
 * the geographically closest Firebase project without any manual user input.
 *
 * **Why this is needed:** ClipSync maintains two independent Firestore databases — one in
 * India and one in the United States — to keep read/write latency low for users in each
 * region. On the very first launch, the app must decide which database to use. Rather than
 * presenting a region picker to the user, LocationHelper resolves the question silently by
 * mapping the device's public IP address to a country code.
 *
 * **How it works:** An HTTPS GET is sent to [ip-api.com](https://ip-api.com), a free and
 * widely-used IP intelligence service, with [api.country.is](https://api.country.is) as
 * a fallback. The JSON response is parsed and only the country code field (ISO 3166-1
 * alpha-2, e.g. `"IN"` or `"US"`) is retained. No other fields from the response are
 * read, stored, or transmitted.
 *
 * **Threading:** The network call is a suspending function that switches to [Dispatchers.IO],
 * so it never blocks the main thread. Both the TCP connection and the response-read are
 * capped at 5 seconds to keep the app responsive on slow or flaky networks.
 */
object LocationHelper {

    /**
     * Resolves the ISO 3166-1 alpha-2 country code for the device's current public IP address.
     *
     * Tries `https://ip-api.com/json/` first, then falls back to `https://api.country.is`.
     * Both requests use HTTPS and 5-second timeouts. Runs on [Dispatchers.IO].
     *
     * @return A two-letter country code such as `"IN"`, `"US"`, or `"DE"` on success,
     *         or `null` if both lookups fail.
     */
    suspend fun detectCountryCode(): String? {
        return withContext(Dispatchers.IO) {
            // Try primary endpoint first, fall back to api.country.is on failure
            detectFromIpApi() ?: detectFromCountryIs()
        }
    }

    private fun detectFromIpApi(): String? {
        return try {
            val url = URL("https://ip-api.com/json/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod  = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout    = 5000

            if (connection.responseCode == 200) {
                val reader   = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                connection.disconnect()

                val json = JSONObject(response.toString())
                json.optString("countryCode", "").ifEmpty { null }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error detecting location via ip-api", e)
            null
        }
    }

    private fun detectFromCountryIs(): String? {
        return try {
            val url = URL("https://api.country.is")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod  = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout    = 5000

            if (connection.responseCode == 200) {
                val reader   = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                connection.disconnect()

                val json = JSONObject(response.toString())
                json.optString("country", "").ifEmpty { null }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error detecting location via country.is", e)
            null
        }
    }
}
