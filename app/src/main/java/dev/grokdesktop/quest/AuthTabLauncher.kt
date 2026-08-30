package dev.grokdesktop.quest

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Custom Tabs for grok.com device-auth (http/https only; mirror server/externalUrl.js). */
object AuthTabLauncher {
    fun isSafeExternalUrl(href: String?): Boolean {
        if (href.isNullOrBlank()) return false
        val uri = try {
            Uri.parse(href.trim())
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank()) return false
        return true
    }

    fun open(context: Context, url: String): Boolean {
        if (!isSafeExternalUrl(url)) return false
        val uri = Uri.parse(url.trim())
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Custom Tabs often no-op on Quest. Prefer the system/Quest browser.
        for (pkg in arrayOf("com.oculus.browser", "com.android.chrome")) {
            try {
                context.startActivity(Intent(view).setPackage(pkg))
                return true
            } catch (_: Exception) {
            }
        }
        try {
            context.startActivity(view)
            return true
        } catch (_: Exception) {
        }
        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
            true
        } catch (_: Exception) {
            false
        }
    }
}
