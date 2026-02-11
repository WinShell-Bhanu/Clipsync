package com.bunty.clipsync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

object LocationHelper {

    // --- IP Location Detection ---
    // Used to route user to the nearest Cloud Functions region (US vs IN)
    suspend fun detectCountryCode(): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Using ip-api.com (free, non-commercial use)
                // Note: In production, consider a paid service or robust fallback
                val url = URL("http://ip-api.com/json/") 
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String? 
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    connection.disconnect()

                    val json = JSONObject(response.toString())
                    val countryCode = json.optString("countryCode", "")
                    countryCode
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
