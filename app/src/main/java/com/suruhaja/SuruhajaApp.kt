package com.suruhaja

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.suruhaja.util.ThemeManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SuruhajaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.apply(this)
        FirebaseApp.initializeApp(this)

        val factory: AppCheckProviderFactory = if (BuildConfig.DEBUG) {
            try {
                val clazz = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                clazz.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
            } catch (e: Exception) {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
    }
}
