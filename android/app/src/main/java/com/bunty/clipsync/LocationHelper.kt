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
 * **How it works:** An HTTP GET is sent to [ip-api.com](http://ip-api.com), a free and
 * widely-used IP intelligence service. The JSON response is parsed and only the
 * `countryCode` field (ISO 3166-1 alpha-2, e.g. `"IN"` or `"US"`) is retained. No
 * other fields from the response are read, stored, or transmitted.
 *
 * **Threading:** The network call is a suspending function that switches to [Dispatchers.IO],
 * so it never blocks the main thread. Both the TCP connection and the response-read are
 * capped at 5 seconds to keep the app responsive on slow or flaky networks.
 */
object LocationHelper {

    /**
     * Resolves the ISO 3166-1 alpha-2 country code for the device's current public IP address.
     *
     * Execution steps:
     *  1. Suspends and resumes on [Dispatchers.IO] to keep blocking I/O off the main thread.
     *  2. Opens an HTTP GET connection to `https://freeipapi.com/api/json/` with 5-second timeouts
     *     on both the TCP connect and the response read phases.
     *  3. On HTTP 200, reads the full response body into a [StringBuilder], parses it as
     *     JSON, and returns the value of the `countryCode` field.
     *  4. On any non-200 HTTP status, returns `null`.
     *  5. On any exception (network error, JSON parse error, timeout), logs the error
     *     via [android.util.Log] and returns `null`.
     *
     * Callers should treat a `null` return as "region unknown" and fall back to a sensible
     * default (typically India, the primary Firebase project region).
     *
     * @return A two-letter country code such as `"IN"`, `"US"`, or `"DE"` on success,
     *         or `null` if the lookup fails for any reason.
     */
    suspend fun detectCountryCode(): String? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://freeipapi.com/api/json/")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod  = "GET"
                connection.connectTimeout = 5000  // 5-second connection timeout
                connection.readTimeout    = 5000  // 5-second read timeout

                if (connection.responseCode == 200) {
                    // Stream the response body line-by-line to handle arbitrarily sized payloads.
                    val reader   = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    // Parse the JSON and extract only the countryCode field; all other
                    // fields returned by freeipapi.com (city, ISP, lat/lon, etc.) are discarded.
                    val json = JSONObject(response.toString())
                    json.optString("countryCode", "")
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("LocationHelper", "Error detecting location", e)
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
