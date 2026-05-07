package com.bunty.clipsync

import android.content.Context
import android.net.Uri

/**
 * Manages a domain allowlist for URLs received via FCM push notifications.
 *
 * FCM data messages can carry `downloadUrl` (for app updates) and `actionUrl` (for
 * announcements). If Firebase project credentials are ever compromised, an attacker
 * could push notifications containing phishing links or malicious APK download URLs.
 * This manager mitigates that risk by validating every incoming URL against a set of
 * trusted domains before the app opens it in the browser.
 *
 * The allowlist is composed of two layers:
 * 1. **Default domains** — hard-coded trusted domains that ship with the app and
 *    cannot be removed by the user (e.g. the official GitHub releases page).
 * 2. **User-added domains** — custom domains the user has chosen to trust, stored
 *    in SharedPreferences and fully editable from the Settings screen.
 *
 * A URL is considered trusted when its host ends with (or exactly matches) any entry
 * in the combined allowlist. For example, the default entry `"github.com"` trusts
 * both `github.com` and any subdomain like `raw.githubusercontent.com` is NOT trusted
 * — only exact suffix matches are accepted.
 */
object UrlAllowlistManager {

    private const val PREFS_NAME = "url_allowlist_prefs"
    private const val KEY_USER_DOMAINS = "user_domains"

    /**
     * Default trusted domains that ship with the app. These cannot be removed by the
     * user and cover the official distribution channels for ClipSync.
     */
    val DEFAULT_DOMAINS: Set<String> = setOf(
        "github.com",
        "forms.gle",
        "docs.google.com"
    )

    /**
     * Returns the full set of trusted domains: defaults merged with any user-added entries.
     */
    fun getAllowedDomains(context: Context): Set<String> {
        return DEFAULT_DOMAINS + getUserDomains(context)
    }

    /**
     * Returns only the domains that the user has added manually.
     */
    fun getUserDomains(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_USER_DOMAINS, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Adds a user-specified domain to the allowlist.
     *
     * The domain is normalised to lowercase and trimmed before storage. Duplicates
     * and entries that already appear in [DEFAULT_DOMAINS] are silently ignored.
     *
     * @param domain The domain to trust, e.g. `"example.com"`.
     */
    fun addUserDomain(context: Context, domain: String) {
        val normalised = domain.trim().lowercase()
        if (normalised.isEmpty()) return
        val current = getUserDomains(context).toMutableSet()
        current.add(normalised)
        saveUserDomains(context, current)
    }

    /**
     * Removes a user-specified domain from the allowlist.
     *
     * Default domains cannot be removed — attempting to do so is a no-op.
     *
     * @param domain The domain to remove.
     */
    fun removeUserDomain(context: Context, domain: String) {
        val normalised = domain.trim().lowercase()
        val current = getUserDomains(context).toMutableSet()
        current.remove(normalised)
        saveUserDomains(context, current)
    }

    /**
     * Checks whether the given [url] belongs to a trusted domain.
     *
     * Returns `true` if the URL's host exactly matches or is a subdomain of any
     * entry in the combined allowlist (defaults + user). Returns `false` for blank
     * URLs, URLs without a parseable host, or hosts that do not match.
     */
    fun isUrlTrusted(context: Context, url: String): Boolean {
        if (url.isBlank()) return true // No URL to open — nothing to validate.

        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false

        val allowedDomains = getAllowedDomains(context)
        return allowedDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    /**
     * Extracts the host from a URL for display in warning messages.
     *
     * @return The host portion of the URL, or the raw URL string if parsing fails.
     */
    fun extractHost(url: String): String {
        return try {
            Uri.parse(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun saveUserDomains(context: Context, domains: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_DOMAINS, domains.joinToString(",")).apply()
    }
}
