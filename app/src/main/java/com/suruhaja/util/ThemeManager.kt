package com.suruhaja.util

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import com.suruhaja.R

object ThemeManager {

    private const val PREFS_NAME = "suruhaja_theme"
    private const val KEY_DARK_MODE = "dark_mode"

    // ── Tema gelap DIMATIKAN SEMENTARA (permintaan 2026-09-24) ───────────────
    // Aplikasi customer selalu terang, walau device dalam mode gelap.
    // Cara menghidupkan lagi:
    //   1. ubah DARK_THEME_SUPPORTED jadi true
    //   2. hapus android:visibility="gone" pada btn_theme_toggle di
    //      res/layout/fragment_profile.xml
    //   3. pasang kembali wiring switch_theme di ProfileFragment
    //   4. kembalikan folder res/values-night/ dari git (dihapus supaya warna
    //      gelap tidak bisa bocor ke tampilan terang)
    private const val DARK_THEME_SUPPORTED = false

    // 0 = auto (ikut sistem), 1 = light, 2 = dark
    fun getMode(context: Context): Int {
        // Selama tema gelap dimatikan, mode yang dilaporkan selalu light —
        // nilai lama di SharedPreferences tidak pernah dipakai.
        if (!DARK_THEME_SUPPORTED) return 1
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DARK_MODE, 1)
    }

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DARK_MODE, mode).apply()
    }

    fun apply(context: Context) {
        val mode = if (!DARK_THEME_SUPPORTED) {
            AppCompatDelegate.MODE_NIGHT_NO            // selalu terang
        } else {
            when (getMode(context)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO      // Light
                2 -> AppCompatDelegate.MODE_NIGHT_YES     // Dark
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM  // Auto
            }
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun isDarkMode(context: Context): Boolean {
        if (!DARK_THEME_SUPPORTED) return false
        return when (getMode(context)) {
            1 -> false
            2 -> true
            else -> {
                val nightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun applyMapStyle(context: Context, map: GoogleMap) {
        if (isDarkMode(context)) {
            try {
                val success = map.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
                )
                if (!success) Log.e("ThemeManager", "Style parsing failed.")
            } catch (e: Resources.NotFoundException) {
                Log.e("ThemeManager", "Can't find style. Error: ", e)
            }
        } else {
            // Reset to default day style
            map.setMapStyle(null)
        }
    }
}
