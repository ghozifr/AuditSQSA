package com.suruhaja.ui.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import com.suruhaja.R
import com.suruhaja.databinding.FragmentHomeBinding
import com.suruhaja.ui.promo.PromoAdapter
import com.suruhaja.ui.promo.PromoCarousel
import com.suruhaja.util.LocationHelper
import com.suruhaja.util.PushTokenRegistrar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationGateDialog: AlertDialog? = null
    private lateinit var promoAdapter: PromoAdapter
    private lateinit var promoCarousel: PromoCarousel

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) checkLocationGate() }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* push tetap didaftarkan; notifikasi hanya tampil kalau diizinkan */ }

    companion object {
        private const val LOCATION_SETTINGS_REQUEST = 3001

        /** Heartbeat lokasi: 60 detik (dulu 30 detik) — hemat write Firestore. */
        private const val LOCATION_HEARTBEAT_MS = 60_000L

        /** Kirim ulang hanya kalau sudah bergerak ≥ 50 m. */
        private const val LOCATION_MIN_MOVE_M = 50f
    }

    private var lastPushedLat = 0.0
    private var lastPushedLng = 0.0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply status bar inset to header
            binding.root.findViewById<View>(binding.ivProfile.id).parent?.let { header ->
                (header as? ViewGroup)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = statusBars.top
                }
            }

            // Apply nav bar inset as padding to ScrollView
            binding.root.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        viewModel.userName.observe(viewLifecycleOwner) { name ->
            binding.tvGreeting.text = "${name.ifEmpty { "Teman" }} \uD83D\uDC4B"
        }

        viewModel.balance.observe(viewLifecycleOwner) { balance ->
            binding.tvBalance.text = "Rp %,d".format(balance)
        }

        // Active order tile: Ride/Send dan Food memakai sumber koleksi masing-masing.
        viewModel.activeOrder.observe(viewLifecycleOwner) { renderActiveCard() }
        viewModel.activeFoodOrder.observe(viewLifecycleOwner) { renderActiveCard() }

        binding.cardActiveOrder.setOnClickListener {
            val ride = viewModel.activeOrder.value
            val food = viewModel.activeFoodOrder.value
            if (food != null && (ride == null || food.createdAt > ride.createdAt)) {
                findNavController().navigate(R.id.foodTrackingFragment, Bundle().apply { putString("orderId", food.id) })
            } else {
                ride?.let { findNavController().navigate(R.id.orderTrackingFragment, Bundle().apply { putString("orderId", it.id) }) }
            }
        }

        binding.btnSearch.setOnClickListener { findNavController().navigate(R.id.mapFragment) }
        binding.btnSuruhRide.setOnClickListener { findNavController().navigate(R.id.mapFragment) }
        binding.btnBalance.setOnClickListener { findNavController().navigate(R.id.topupFragment) }
        binding.btnSuruhFood.setOnClickListener { findNavController().navigate(R.id.merchantListFragment) }

        binding.btnSuruhSend.setOnClickListener { findNavController().navigate(R.id.sendVehicleFragment) }

        (binding.ivRideIcon.drawable as? Animatable)?.start()

        setupPromoSection()
        setupNotificationsBell()
        setupPushNotifications()
    }

    // ─── Push notification admin (di luar app) ─────────────────────────────
    private fun setupPushNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Daftarkan FCM token customer ke backend (koleksi fcm_tokens, server-only).
        viewLifecycleOwner.lifecycleScope.launch { PushTokenRegistrar.registerCurrentToken() }
    }

    // ─── Penawaran Terbaik (konten dari admin web) ─────────────────────────
    private fun setupPromoSection() {
        promoAdapter = PromoAdapter { promo -> viewModel.openPromotion(promo) }
        binding.rvPromos.adapter = promoAdapter
        promoCarousel = PromoCarousel(binding.rvPromos, binding.layoutPromoIndicator)

        viewModel.promotions.observe(viewLifecycleOwner) { promos ->
            promoAdapter.submit(promos)
            binding.layoutPromoSection.visibility =
                if (promos.isEmpty()) View.GONE else View.VISIBLE
            // Dot dibangun ulang + auto-slide (5 detik) dimulai dari atas.
            promoCarousel.submit(promos.size, viewLifecycleOwner.lifecycleScope)
        }

        viewModel.promoTarget.observe(viewLifecycleOwner) { target ->
            if (target != null) {
                findNavController().navigate(R.id.merchantMenuFragment, target)
                viewModel.clearPromoTarget()
            }
        }

        viewModel.promoMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.consumePromoMessage()
            }
        }

        // Promo voucher → salin kodenya ke clipboard.
        viewModel.promoCopyCode.observe(viewLifecycleOwner) { code ->
            if (!code.isNullOrEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Kode voucher", code))
                Toast.makeText(requireContext(), "Kode voucher disalin: $code", Toast.LENGTH_SHORT).show()
                viewModel.consumePromoCopyCode()
            }
        }
    }

    // ─── Bell notifikasi admin ─────────────────────────────────────────────
    private fun setupNotificationsBell() {
        binding.btnNotifications.setOnClickListener {
            findNavController().navigate(R.id.notificationsFragment)
        }
        viewModel.hasUnreadNotifications.observe(viewLifecycleOwner) { unread ->
            binding.btnNotifications.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    if (unread) R.color.red_accent else R.color.text_white
                )
            )
            binding.btnNotifications.contentDescription =
                if (unread) "Notifikasi, ada yang belum dibaca" else "Notifikasi"
        }
    }

    private fun renderActiveCard() {
        val ride = viewModel.activeOrder.value
        val food = viewModel.activeFoodOrder.value
        if (ride == null && food == null) {
            binding.cardActiveOrder.visibility = View.GONE
            return
        }
        binding.cardActiveOrder.visibility = View.VISIBLE
        if (food != null && (ride == null || food.createdAt > ride.createdAt)) {
            binding.tvActiveStatus.text = com.suruhaja.data.model.FoodOrder.statusText(food.status)
            binding.tvActiveRoute.text = "${food.merchantName.ifEmpty { "Toko" }} → ${food.customerAddress.ifEmpty { "Alamat pengantaran" }}"
        } else if (ride != null) {
            binding.tvActiveStatus.text = statusText(ride.status)
            binding.tvActiveRoute.text = "${ride.pickup.ifEmpty { "Penjemputan" }} → ${ride.destination.ifEmpty { "Tujuan" }}"
        }
        binding.cardActiveOrder.animate().alpha(1f).setDuration(500).start()
    }

    private fun statusText(status: String): String = when (status) {
        "pending" -> "Mencari Driver..."
        "accepted" -> "Driver Menuju Penjemputan"
        "pickup" -> "Menuju Tujuan"
        "delivering" -> "Sedang Diantar"
        else -> status
    }

    // ─── Gate lokasi (wajib aktif sebelum pakai app) ───────────────────────
    private fun checkLocationGate() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val client = LocationServices.getSettingsClient(requireActivity())
        client.checkLocationSettings(locationSettingsRequest())
            .addOnSuccessListener {
                hideLocationGate()
                warmUpLocation()
                startLocationHeartbeat()
            }
            .addOnFailureListener {
                showLocationGate()
            }
    }

    private fun showLocationGate() {
        if (locationGateDialog?.isShowing == true) return
        locationGateDialog = AlertDialog.Builder(requireContext())
            .setTitle("Aktifkan Lokasi")
            .setMessage("Lokasi harus diaktifkan untuk menggunakan Suruhaja.")
            .setCancelable(false)
            .setPositiveButton("Aktifkan Lokasi") { _, _ -> openLocationSettings() }
            .show()
    }

    private fun hideLocationGate() {
        locationGateDialog?.dismiss()
        locationGateDialog = null
    }

    private fun openLocationSettings() {
        val client = LocationServices.getSettingsClient(requireActivity())
        client.checkLocationSettings(locationSettingsRequest())
            .addOnFailureListener { e ->
                if (e is ApiException && e.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                    try {
                        e.status.startResolutionForResult(requireActivity(), LOCATION_SETTINGS_REQUEST)
                        return@addOnFailureListener
                    } catch (se: Exception) { /* fallthrough */ }
                }
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) { /* no settings activity */ }
            }
    }

    private fun locationSettingsRequest(): LocationSettingsRequest =
        LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
            ).build()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun warmUpLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Ambil lokasi sekali di awal supaya promo toko kota lain tidak
            // sempat tampil sebelum heartbeat pertama jalan.
            LocationHelper.fetch(fusedLocationClient)?.let { loc ->
                viewModel.setCustomerLocation(loc.latitude, loc.longitude)
            }
        }
    }

    /**
     * Tulis lokasi customer ke Firestore selama di dashboard.
     *
     * Hemat write: interval 60 detik DAN hanya kalau sudah bergerak ≥ 50 m
     * (dulu: tulis setiap 30 detik walau diam).
     */
    private fun startLocationHeartbeat() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    LocationHelper.fetch(fusedLocationClient)?.let { loc ->
                        // Lokasi juga dipakai menyaring promo (toko kota lain tidak tampil).
                        viewModel.setCustomerLocation(loc.latitude, loc.longitude)
                        if (shouldPushLocation(loc.latitude, loc.longitude)) {
                            viewModel.updateLocation(loc.latitude, loc.longitude)
                            lastPushedLat = loc.latitude
                            lastPushedLng = loc.longitude
                        }
                    }
                } catch (_: Exception) {}
                delay(LOCATION_HEARTBEAT_MS)
            }
        }
    }

    /** Belum pernah kirim, atau sudah bergerak ≥ [LOCATION_MIN_MOVE_M] meter. */
    private fun shouldPushLocation(lat: Double, lng: Double): Boolean {
        if (lastPushedLat == 0.0 && lastPushedLng == 0.0) return true
        val hasil = FloatArray(1)
        android.location.Location.distanceBetween(lastPushedLat, lastPushedLng, lat, lng, hasil)
        return hasil[0] >= LOCATION_MIN_MOVE_M
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        checkLocationGate()
        // Penawaran bergeser otomatis lagi setelah kembali ke layar ini.
        if (::promoCarousel.isInitialized) {
            promoCarousel.resume(viewLifecycleOwner.lifecycleScope)
        }
    }

    override fun onPause() {
        super.onPause()
        // Jangan geser otomatis saat layar tidak aktif.
        if (::promoCarousel.isInitialized) promoCarousel.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationGateDialog?.dismiss()
        locationGateDialog = null
        _binding = null
    }
}
