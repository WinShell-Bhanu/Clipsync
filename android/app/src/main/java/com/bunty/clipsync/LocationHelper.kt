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
 * LocationHelper detects the user's country at first launch so ClipSync can automatically
 * select the closest Firestore region (US or IN) without asking the user.
 *
 * Uses the free [ip-api.com](http://ip-api.com) service to get a country code from the
 * device's public IP address. The request is made on [Dispatchers.IO] and has a 5-second
 * timeout to avoid blocking the UI.
 *
 * **Privacy note:** Only the `countryCode` field is read; no personal data is stored or sent.
 */
object LocationHelper {

    /**
     * Fetches the ISO 3166-1 alpha-2 country code for the device's current IP address.
     *
     * Makes an HTTP GET to `http://ip-api.com/json/` and parses the `countryCode` field.
     *
     * @return A 2-letter country code (e.g. `"IN"`, `"US"`, `"DE"`) or `null` if the
     *         request fails or the response cannot be parsed.
     */
    suspend fun detectCountryCode(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://ip-api.com/json/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod  = "GET"
                connection.connectTimeout = 5000  // 5-second connection timeout
                connection.readTimeout    = 5000  // 5-second read timeout

                if (connection.responseCode == 200) {
                    // Read the full JSON response body
                    val reader   = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    connection.disconnect()

                    // Parse and return only the countryCode field
                    val json = JSONObject(response.toString())
                    json.optString("countryCode", "")
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("LocationHelper", "Error detecting location", e)
                null
            }
        }
    }
}
