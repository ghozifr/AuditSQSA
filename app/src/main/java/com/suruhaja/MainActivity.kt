package com.suruhaja

import com.suruhaja.data.policy.ServiceRadiusPolicy
import com.suruhaja.data.relay.RelayClient
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.suruhaja.databinding.ActivityMainBinding
import com.suruhaja.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val appUpdateManager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private val playUpdateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    private var hardUpdateRequired = false
    private var hardUpdateDialog: AlertDialog? = null
    private var versioningListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var serviceRadiusListener: com.google.firebase.firestore.ListenerRegistration? = null

    @Inject
    lateinit var auth: FirebaseAuth

    private var notifPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 13+ (Redmi Note 11 Pro) — minta izin notifikasi supaya notif
        // chat driver + perubahan status order benar-benar tampil.
        requestNotificationPermission()

        // Handle Window Insets for the floating Bottom Navigation
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // Keep the 12dp horizontal margin, but make bottom margin dynamic
                // 16dp base floating margin + system navigation bar height
                val baseMargin = (16 * resources.displayMetrics.density).toInt()
                bottomMargin = insets.bottom + baseMargin
            }
            windowInsets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Auto-navigate to home ONLY if previously logged in (phone auth).
        // popUpTo(authFragment, inclusive) → hapus layar login dari back stack supaya
        // tombol back / pop back tidak "logout" user (bug auto-logout setelah order SuruhFood).
        if (savedInstanceState == null && auth.currentUser != null) {
            navController.navigate(
                R.id.homeFragment,
                null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.authFragment, true)
                    .build()
            )
        }

        // Auth gate: kalau sesi null (logout / kedaluwarsa / restore), paksa balik ke login.
        auth.addAuthStateListener { fa ->
            if (fa.currentUser == null) {
                val dest = navController.currentDestination?.id
                if (dest != null && dest != R.id.authFragment) {
                    navController.navigate(R.id.authFragment, null,
                        androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build())
                }
            }
        }

        binding.bottomNavigation.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNavigation.visibility = when (destination.id) {
                R.id.homeFragment, R.id.ordersFragment, R.id.profileFragment -> View.VISIBLE
                else -> View.GONE
            }
        }

        checkForcedUpdate { requestImmediatePlayUpdate() }
    }

    override fun onResume() {
        super.onResume()
        if (hardUpdateRequired) showForcedUpdateDialog() else resumeImmediatePlayUpdateIfNeeded()
        // Android < 13: pandu ke pengaturan bila notifikasi dinonaktifkan
        // (Android 13+ memakai dialog izin sistem di requestNotificationPermission).
        if (Build.VERSION.SDK_INT < 33 && !notifPromptShown) {
            notifPromptShown = true
            NotificationHelper.showPromptIfDisabled(this)
        }
    }

    private fun requestImmediatePlayUpdate(onUnavailable: (() -> Unit)? = null) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (shouldStartImmediatePlayUpdate(
                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    )) {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        playUpdateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } else {
                    onUnavailable?.invoke()
                }
            }
            .addOnFailureListener { onUnavailable?.invoke() }
    }

    private fun resumeImmediatePlayUpdateIfNeeded() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    playUpdateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001
            )
        }
    }

    private fun checkForcedUpdate(onNoHardBlock: () -> Unit) {
        if (BuildConfig.DEBUG) {
            onNoHardBlock()
            return
        }
        val prefs = getSharedPreferences("play_update_gate", MODE_PRIVATE)
        val cachedMinimum = prefs.getLong("customer_min_version", 0L)
        if (requiresHardPlayUpdate(BuildConfig.VERSION_CODE, cachedMinimum)) {
            hardUpdateRequired = true
            showForcedUpdateDialog()
            return
        }
        // Radius layanan dari config/discovery — diubah dari Firestore tanpa update app.
        if (serviceRadiusListener == null) serviceRadiusListener = ServiceRadiusPolicy.observe()
        versioningListener?.remove()
        versioningListener = FirebaseFirestore.getInstance()
            .collection("config").document("versioning")
            .addSnapshotListener { doc, error ->
                if (error != null || doc == null) {
                    // Jalur cadangan: kalau Google tak terjangkau (mis. operator by.U),
                    // gate versi diambil dari relay. Kalau relay juga gagal ⇒ tetap fail-open.
                    if (error != null && RelayClient.isNetworkError(error)) {
                        RelayClient.catatGagalJaringan()
                        Thread {
                            val minimum = try {
                                RelayClient.config()
                                    ?.optJSONObject("versioning")
                                    ?.optJSONObject("data")
                                    ?.optLong("customerMinVersionCode", 0L) ?: 0L
                            } catch (_: Exception) {
                                0L
                            }
                            if (minimum > 0L) {
                                prefs.edit().putLong("customer_min_version", minimum).apply()
                                hardUpdateRequired =
                                    requiresHardPlayUpdate(BuildConfig.VERSION_CODE, minimum)
                            }
                            runOnUiThread {
                                if (hardUpdateRequired) showForcedUpdateDialog() else onNoHardBlock()
                            }
                        }.start()
                    } else {
                        onNoHardBlock()
                    }
                    return@addSnapshotListener
                }
                RelayClient.catatSuksesLangsung()
                val minimum = doc.getLong("customerMinVersionCode") ?: run {
                    onNoHardBlock()
                    return@addSnapshotListener
                }
                prefs.edit().putLong("customer_min_version", minimum).apply()
                hardUpdateRequired = requiresHardPlayUpdate(BuildConfig.VERSION_CODE, minimum)
                if (hardUpdateRequired) showForcedUpdateDialog() else onNoHardBlock()
            }
    }

    override fun onDestroy() {
        versioningListener?.remove()
        versioningListener = null
        serviceRadiusListener?.remove()
        serviceRadiusListener = null
        super.onDestroy()
    }

    private fun showForcedUpdateDialog() {
        if (!hardUpdateRequired || isFinishing || isDestroyed || hardUpdateDialog?.isShowing == true) return
        hardUpdateDialog = AlertDialog.Builder(this)
            .setTitle("Pembaruan Wajib")
            .setMessage("Versi Suruhaja ini tidak lagi didukung. Perbarui dari Google Play untuk melanjutkan.")
            .setCancelable(false)
            .setPositiveButton("Perbarui", null)
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openPlayStore() }
                }
                dialog.setOnDismissListener {
                    hardUpdateDialog = null
                    if (hardUpdateRequired && !isFinishing && !isDestroyed) {
                        window.decorView.post { showForcedUpdateDialog() }
                    }
                }
                dialog.show()
            }
    }

    private fun openPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            } catch (_: Exception) { /* tidak ada store */ }
        }
    }
}
