package com.footprint.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.footprint.app.ui.FootprintApp

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("footprint", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val consented = preferences.getBoolean(KEY_AMAP_PRIVACY_ACCEPTED, false)
        if (consented) applyAmapPrivacyConsent()

        setContent {
            FootprintApp(
                initiallyConsented = consented,
                onAcceptPrivacy = {
                    applyAmapPrivacyConsent()
                    preferences.edit().putBoolean(KEY_AMAP_PRIVACY_ACCEPTED, true).apply()
                },
                onDeclinePrivacy = { finish() },
            )
        }
    }

    private fun applyAmapPrivacyConsent() {
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
    }

    companion object {
        private const val KEY_AMAP_PRIVACY_ACCEPTED = "amap_privacy_accepted_v2"
    }
}
