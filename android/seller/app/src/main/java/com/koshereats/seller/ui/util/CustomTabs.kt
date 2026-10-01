package com.koshereats.seller.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens [url] in a Chrome Custom Tab (Stripe payout onboarding, Partner
 * Agreement terms). Falls back to a plain ACTION_VIEW when no Custom Tabs
 * provider is installed, and to a Toast when there's no browser at all.
 * Mirrors the courier app's PayoutsSetupScreen.openCustomTab.
 */
fun openCustomTab(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser available to open this link", Toast.LENGTH_LONG).show()
        }
    }
}
