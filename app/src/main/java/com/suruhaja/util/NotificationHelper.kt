package com.suruhaja.util

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Helper notifikasi: cek izin, minta izin, dan buka pengaturan. */
object NotificationHelper {

    fun areEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 13+: minta POST_NOTIFICATIONS. Return true kalau sudah granted / tak perlu minta. */
    fun requestPermissionIfNeeded(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001
            )
            return false
        }
        return true
    }

    /** Buka pengaturan notifikasi aplikasi. */
    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            } catch (_: Exception) {}
        }
    }

    /** Dialog ajakan mengaktifkan notifikasi (tampil hanya bila notifikasi nonaktif). */
    fun showPromptIfDisabled(context: Context) {
        if (areEnabled(context)) return
        try {
            AlertDialog.Builder(context)
                .setTitle("Aktifkan Notifikasi")
                .setMessage("Nyalakan notifikasi agar tidak ketinggalan pesanan & update status pesanan.")
                .setPositiveButton("Aktifkan") { _, _ -> openSettings(context) }
                .setNegativeButton("Nanti", null)
                .show()
        } catch (_: Exception) {}
    }
}
