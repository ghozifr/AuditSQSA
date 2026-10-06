package com.suruhaja.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.core.app.ActivityCompat
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.suruhaja.ui.voucher.VoucherPickerDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.suruhaja.R
import com.suruhaja.data.policy.OrderPolicy
import androidx.appcompat.app.AlertDialog
import com.suruhaja.data.repository.OrderRepository
import com.suruhaja.data.repository.VoucherRepository
import com.suruhaja.databinding.FragmentLocationPickerBinding
import com.suruhaja.service.OrderNotificationService
import com.suruhaja.util.LocationHelper
import com.suruhaja.util.MapPinIcon
import com.suruhaja.util.NearbyDriverOverlay
import com.suruhaja.util.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LocationPickerFragment : Fragment(), OnMapReadyCallback {

    @Inject lateinit var orderRepository: OrderRepository

    private var _binding: FragmentLocationPickerBinding? = null
    private val binding get() = _binding!!

    private var googleMap: GoogleMap? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var pickupMarker: Marker? = null
    private var destMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var driverOverlay: NearbyDriverOverlay? = null

    private var pickupLatLng: LatLng? = null
    private var destLatLng: LatLng? = null
    private var currentPrice: Long = 0L
    private var currentDistanceKm: Double = 0.0
    private var currentDurationMin: Int = 0
    private var selectedPaymentMethod = "cash"

    private var mapsApiKey: String = ""
    private enum class PickerStep { DESTINATION, PICKUP, CHECKOUT }
    private var pickerStep = PickerStep.DESTINATION
    private var isUserMovingMap = false
    private var locationSearchJob: Job? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val GPS_ENABLE_REQUEST = 1002
        private const val TAG = "LocationPicker"
        // Matches the collapsed selection sheet height in fragment_location_picker.xml.
        private const val SHEET_PEEK_DP = 358f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.btnBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (20 * resources.displayMetrics.density).toInt()
            }
            // Confirm button terpisah di root — beri jarak dari navbar.
            binding.btnConfirmRide.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBars.bottom
            }
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.locationSheetScroll.updatePadding(
                bottom = if (imeVisible) imeInsets.bottom + (16 * resources.displayMetrics.density).toInt() else (104 * resources.displayMetrics.density).toInt()
            )
            if (pickerStep != PickerStep.CHECKOUT) {
                binding.btnSetDestination.visibility = if (imeVisible) View.GONE else View.VISIBLE
                if (imeVisible) showSearchAboveKeyboard()
            }
            insets
        }

        // Get Maps API key from manifest
        try {
            val appInfo = requireContext().packageManager
                .getApplicationInfo(requireContext().packageName, PackageManager.GET_META_DATA)
            mapsApiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key", e)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_view) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        // Mode pilih lokasi: titik jemput (custom) vs titik tujuan.
        binding.rowPickup.setOnClickListener {
            if (destLatLng == null) {
                Toast.makeText(requireContext(), "Konfirmasi tujuan dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickerStep == PickerStep.CHECKOUT) {
                pickerStep = PickerStep.PICKUP
                setCheckoutVisible(false)
                binding.ivCenterPin.visibility = View.VISIBLE
                binding.btnSetDestination.visibility = View.VISIBLE
                updateModeUI()
            }
            pickupLatLng?.let { googleMap?.animateCamera(CameraUpdateFactory.newLatLng(it)) }
        }
        binding.rowDestination.setOnClickListener {
            if (pickerStep == PickerStep.CHECKOUT) {
                // A changed destination invalidates the prior pickup, route, and server quote.
                pickupLatLng = null
                pickupMarker?.remove()
                pickupMarker = null
                binding.tvPickupAddress.text = "Tentukan titik jemput"
                pickerStep = PickerStep.DESTINATION
                setCheckoutVisible(false)
                binding.ivCenterPin.visibility = View.VISIBLE
                binding.btnSetDestination.visibility = View.VISIBLE
                updateModeUI()
            }
            destLatLng?.let { googleMap?.animateCamera(CameraUpdateFactory.newLatLng(it)) }
        }

        setupLocationSearch()
        updateModeUI()

        // Payment method selector: cash (tunai) vs saldo (QRIS di akhir trip)
        binding.btnPayCash.setOnClickListener { setPaymentMethod("cash") }
        binding.btnPaySaldo.setOnClickListener { setPaymentMethod("saldo") }
        // Tombol "Pakai voucher": pilih voucher atau lanjut tanpa voucher.
        binding.btnVoucher.setOnClickListener { openVoucherPicker() }
        setPaymentMethod("cash")
        setCheckoutVisible(false)

        binding.btnConfirmRide.setOnClickListener {
            if (pickupLatLng == null || destLatLng == null) {
                Toast.makeText(requireContext(), "Pilih tujuan dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Validate distance: must be > 0 (matches Firestore rule distanceKm > 0)
            if (currentDistanceKm <= 0.0) {
                Toast.makeText(requireContext(), "Pilih tujuan yang valid", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Anti-double-tap: tombol jadi abu-abu + teks proses, biar user tahu app merespons (bukan hang).
            disableConfirmButton("MEMBUAT PESANAN...")
            // Create order in Firestore → driver app picks it up
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"

                    // Policy check: allow multiple orders only for certain service types.
                    // For now SuruhRide is limited to 1 active order.
                    val serviceType = OrderPolicy.TYPE_RIDE
                    if (!OrderPolicy.canCreateOrder(serviceType, orderRepository.countActiveOrders(uid, serviceType))) {
                        Toast.makeText(requireContext(), "Masih ada pesanan ride aktif. Selesaikan dulu.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    // Fetch real customer name from Firestore
                    var customerName = "Pelanggan"
                    var customerPhone = ""
                    try {
                        val userDoc = FirebaseFirestore.getInstance()
                            .collection("users").document(uid).get().await()
                        customerPhone = userDoc.getString("phone") ?: ""
                        customerName = userDoc.getString("name")?.ifBlank {
                            customerPhone.ifBlank { "Pelanggan" }
                        } ?: "Pelanggan"
                    } catch (_: Exception) {}

                    val order = OrderRepository.OrderRequest(
                        userId = uid,
                        customerName = customerName,
                        customerPhone = customerPhone,
                        serviceType = serviceType,
                        paymentMethod = selectedPaymentMethod,
                        pickup = _binding?.tvPickupAddress?.text.toString(),
                        pickupLat = pickupLatLng?.latitude ?: 0.0,
                        pickupLng = pickupLatLng?.longitude ?: 0.0,
                        destination = _binding?.tvDestAddress?.text?.toString() ?: "",
                        destLat = destLatLng?.latitude ?: 0.0,
                        destLng = destLatLng?.longitude ?: 0.0,
                        distanceKm = currentDistanceKm,
                        durationMin = currentDurationMin,
                        price = currentPrice
                    )
                    val orderId = orderRepository.createOrder(order, selectedVoucherCode)
                    OrderNotificationService.start(requireContext())
                    Toast.makeText(requireContext(), "Pesanan dibuat! Mencari driver...", Toast.LENGTH_SHORT).show()
                    // Navigate to tracking screen
                    val bundle = Bundle().apply { putString("orderId", orderId) }
                    findNavController().navigate(R.id.orderTrackingFragment, bundle)
                } catch (e: Exception) {
                    enableConfirmButton()
                    Toast.makeText(requireContext(), "Gagal buat pesanan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // My location button — ambil posisi terbaru
        binding.btnMyLocation.setOnClickListener {
            if (!isGpsEnabled()) {
                promptEnableGps()
                return@setOnClickListener
            }
            fetchCurrentLocation()
        }

        binding.btnSetDestination.setOnClickListener {
            val center = googleMap?.cameraPosition?.target ?: return@setOnClickListener
            when (pickerStep) {
                PickerStep.DESTINATION -> {
                    setDestination(center)
                    pickerStep = PickerStep.PICKUP
                }
                PickerStep.PICKUP -> {
                    setPickup(center)
                    pickerStep = PickerStep.CHECKOUT
                    fetchDirectionsRoute(center, destLatLng ?: return@setOnClickListener)
                }
                PickerStep.CHECKOUT -> return@setOnClickListener
            }
            if (pickerStep != PickerStep.CHECKOUT) showPickerPin()
            else binding.ivCenterPin.visibility = View.INVISIBLE
            binding.btnSetDestination.visibility = if (pickerStep == PickerStep.CHECKOUT) View.GONE else View.VISIBLE
            updateModeUI()
        }

        // Setup Bottom Sheet Behavior
        val behavior = BottomSheetBehavior.from(binding.bottomSheet)
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) = Unit
            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })

        // Re-request layout once after address content settles so the collapsed sheet is measured correctly.
        binding.bottomSheet.post {
            binding.bottomSheet.requestLayout()
        }
    }

    private fun setPaymentMethod(method: String) {
        selectedPaymentMethod = method
        val cash = method == "cash"
        binding.btnPayCash.setBackgroundResource(if (cash) R.drawable.bg_location_primary_teal else R.drawable.bg_activity_history_card)
        binding.btnPayCash.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
        binding.btnPaySaldo.setBackgroundResource(if (!cash) R.drawable.bg_location_primary_teal else R.drawable.bg_activity_history_card)
        binding.btnPaySaldo.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchFreshLocation(forceFresh = true)
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                // GPS scopes camera/nearby Drivers only. Pickup is committed by the second stage.
                driverOverlay?.setCenter(latLng)
                _binding?.let { Toast.makeText(requireContext(), "Lokasi diperbarui", Toast.LENGTH_SHORT).show() }
            } else {
                _binding?.let { Toast.makeText(requireContext(), "Lokasi belum tersedia, coba lagi", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** Ambil lokasi: cache segar → one-shot → requestLocationUpdates. Lihat LocationHelper. */
    @SuppressLint("MissingPermission")
    private suspend fun fetchFreshLocation(forceFresh: Boolean = false): Location? {
        return LocationHelper.fetch(fusedLocationClient, forceFresh)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        driverOverlay = NearbyDriverOverlay(viewLifecycleOwner.lifecycleScope, map, orderRepository.observeNearbyDrivers())
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        // Bottom sheet (peek 430dp) menutupi peta bagian bawah. Pad peta ke bawah
        // supaya titik tengah kamera = tengah area VISIBLE, lalu geser pin + tombol
        // "Pilih Lokasi Ini" ke tengah area visible (di atas sheet), bukan tengah layar.
        map.setPadding(0, 0, 0, (SHEET_PEEK_DP * resources.displayMetrics.density).toInt())
        val centerOffsetDp = -SHEET_PEEK_DP / 2f
        binding.btnSetDestination.translationY =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, centerOffsetDp - 86f, resources.displayMetrics)
        binding.ivCenterPin.translationY =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, centerOffsetDp, resources.displayMetrics)

        // Apply theme-based map style
        ThemeManager.applyMapStyle(requireContext(), map)

        // Cek GPS nyala? Kalau mati → prompt user
        if (!isGpsEnabled()) {
            promptEnableGps()
        } else if (hasLocationPermission()) {
            enableMyLocationSafely()
        } else {
            requestLocationPermission()
        }

        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isUserMovingMap = true
                if (pickerStep != PickerStep.CHECKOUT) {
                    showPickerPin(lifted = true)
                    binding.btnSetDestination.visibility = View.GONE
                }
            }
        }

        map.setOnCameraIdleListener {
            if (isUserMovingMap) {
                val center = map.cameraPosition.target
                // Camera center is only a pending candidate. It never commits either location.
                if (pickerStep == PickerStep.PICKUP) {
                    getAddress(center) { _binding?.tvPickupAddress?.text = it }
                } else if (pickerStep == PickerStep.DESTINATION) {
                    getAddress(center) { _binding?.tvDestAddress?.text = it }
                }
                isUserMovingMap = false
                if (pickerStep != PickerStep.CHECKOUT) {
                    showPickerPin(lifted = false)
                    binding.btnSetDestination.visibility = View.VISIBLE
                }
            }
        }

        map.setOnMapClickListener { latLng ->
            map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            isUserMovingMap = true
        }
    }

    private fun isGpsEnabled(): Boolean {
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun promptEnableGps() {
        Toast.makeText(requireContext(), "Nyalakan GPS untuk deteksi lokasi", Toast.LENGTH_LONG).show()
        try {
            startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), GPS_ENABLE_REQUEST)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open GPS settings", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationSafely() {
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchFreshLocation()
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                driverOverlay?.setCenter(latLng)
            } else {
                // GPS mati / belum ada lokasi — fallback ke Jakarta
                val defaultLatLng = LatLng(-6.2088, 106.8456)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(defaultLatLng, 15f))
                driverOverlay?.setCenter(defaultLatLng)
                _binding?.let { Toast.makeText(requireContext(), "Lokasi tidak terdeteksi — pakai default", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GPS_ENABLE_REQUEST) {
            if (isGpsEnabled() && hasLocationPermission()) {
                enableMyLocationSafely()
                fetchCurrentLocation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reset UI when returning from tracking screen
        _binding?.tvDestAddress?.text = "Ke mana hari ini?"
        _binding?.tvDestAddress?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        _binding?.layoutEstimate?.visibility = View.GONE
        _binding?.layoutPayment?.visibility = View.GONE
        _binding?.btnVoucher?.visibility = View.GONE
        _binding?.tvEstimatePrice?.text = "Rp -"
        _binding?.tvEstimateDist?.text = "- km"
        _binding?.ivCenterPin?.visibility = View.VISIBLE // Ensure screen pin is visible
        _binding?.ivCenterPin?.translationY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, -SHEET_PEEK_DP / 2f, resources.displayMetrics)
        disableConfirmButton()
        pickerStep = PickerStep.DESTINATION
        updateModeUI()
        currentDistanceKm = 0.0
        currentPrice = 0L
        currentDurationMin = 0
        // Clear committed markers/coordinates from the previous trip session.
        destMarker?.remove()
        destMarker = null
        destLatLng = null
        pickupMarker?.remove()
        pickupMarker = null
        pickupLatLng = null
        _binding?.tvPickupAddress?.text = "Tentukan titik jemput"
        routePolyline?.remove()
        routePolyline = null
    }

    private fun setPickupMarker(latLng: LatLng) {
        pickupMarker?.remove()
        pickupMarker = googleMap?.addMarker(
            MarkerOptions().position(latLng).title("Penjemputan")
                .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_brand))
                .anchor(0.5f, 1f)
        )
    }

    /** Confirm explicit pickup: place a marker and request route/quote with both committed points. */
    private fun setPickup(latLng: LatLng) {
        pickupLatLng = latLng
        setPickupMarker(latLng)
        getAddress(latLng) { _binding?.tvPickupAddress?.text = it }
        destLatLng?.let { fetchDirectionsRoute(latLng, it) }
    }

    /** Keeps one explicit selection stage visible; checkout appears only after both commits. */
    private fun updateModeUI() {
        val step = pickerStep
        val isDestination = step == PickerStep.DESTINATION
        val isPickup = step == PickerStep.PICKUP
        val isCheckout = step == PickerStep.CHECKOUT

        binding.ivCenterPin.setImageResource(
            if (isPickup) R.drawable.ic_map_pin_web_brand else R.drawable.ic_map_pin_web_coral
        )
        binding.btnSetDestination.text = when (step) {
            PickerStep.DESTINATION -> "Konfirmasi tujuan"
            PickerStep.PICKUP -> "Konfirmasi titik jemput"
            PickerStep.CHECKOUT -> "Harga dari server"
        }
        binding.btnSetDestination.setBackgroundResource(
            if (isPickup) R.drawable.bg_location_primary_teal else R.drawable.bg_location_primary_coral
        )
        binding.tvPickerStep.text = when (step) {
            PickerStep.DESTINATION -> "1/2 · Tentukan tujuan"
            PickerStep.PICKUP -> "2/2 · Tentukan titik jemput"
            PickerStep.CHECKOUT -> "Rute & pembayaran"
        }
        binding.tvPickerHint.text = when (step) {
            PickerStep.DESTINATION -> "Cari alamat atau geser peta, lalu konfirmasi titiknya."
            PickerStep.PICKUP -> "Cari alamat atau geser peta, lalu konfirmasi titik jemput."
            PickerStep.CHECKOUT -> "Harga berasal dari server. Pilih metode pembayaran."
        }
        binding.layoutLocationSearch.visibility = if (isCheckout) View.GONE else View.VISIBLE
        if (isCheckout) clearSearchSuggestions()
        binding.btnConfirmRide.visibility = if (isCheckout) View.VISIBLE else View.GONE
        binding.etLocationSearch.hint = if (isPickup) "Cari titik jemput" else "Cari tujuan"
        if (!isCheckout) binding.etLocationSearch.text?.clear()
        binding.rowDestination.visibility = if (isDestination || isCheckout) View.VISIBLE else View.GONE
        binding.rowPickup.visibility = if (isDestination) View.GONE else View.VISIBLE
        binding.tvPickupAddress.alpha = if (isPickup || isCheckout) 1f else 0.65f
        binding.tvDestAddress.alpha = if (isDestination || isCheckout) 1f else 0.65f
    }

    private fun showPickerPin(lifted: Boolean = false) {
        binding.ivCenterPin.animate().cancel()
        binding.ivCenterPin.visibility = View.VISIBLE
        binding.ivCenterPin.alpha = 1f
        val y = -SHEET_PEEK_DP / 2f + if (lifted) -15f else 0f
        binding.ivCenterPin.animate()
            .translationY(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, resources.displayMetrics))
            .setDuration(160)
            .start()
    }

    private fun setCheckoutVisible(visible: Boolean) {
        binding.layoutEstimate.visibility = if (visible) View.VISIBLE else View.GONE
        binding.layoutPayment.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnVoucher.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) disableConfirmButton("Pilih lokasi")
    }

    private fun setDestination(latLng: LatLng, fromUser: Boolean = false) {
        destLatLng = latLng
        destMarker?.remove()
        destMarker = googleMap?.addMarker(
            MarkerOptions().position(latLng).title("Tujuan")
                .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_coral))
                .anchor(0.5f, 1f)
        )

        getAddress(latLng) { addr ->
            _binding?.tvDestAddress?.text = addr
            _binding?.tvDestAddress?.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
        }

        val origin = pickupLatLng ?: return
        if (fromUser) fetchDirectionsRoute(origin, latLng)
    }

    // ─── Directions API ─────────────────────────────────────────────────────

    private fun fetchDirectionsRoute(origin: LatLng, destination: LatLng) {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            _binding?.layoutEstimate?.visibility = View.VISIBLE
            _binding?.tvEstimatePrice?.text = "Mencari rute..."
            _binding?.tvEstimateDist?.text = "..."

            try {
                val result = withContext(Dispatchers.IO) {
                    callDirectionsApi(origin, destination)
                }
                if (result != null) {
                    drawRoutePolyline(result.polyline)
                    val distKm = result.distance / 1000f
                    currentDurationMin = (result.durationSec / 60).toInt().coerceAtLeast(1)
                    applyServerQuote(origin, destination, distKm.toDouble(), currentDurationMin, "%.1f km · %s".format(distKm, result.duration))
                } else {
                    // Fallback: straight line
                    drawStraightLine(origin, destination)
                    val dist = estimateDistance(origin, destination)
                    currentDurationMin = 0
                    applyServerQuote(origin, destination, dist.toDouble(), 0, "%.1f km".format(dist))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Directions API error", e)
                drawStraightLine(origin, destination)
                val dist = estimateDistance(origin, destination)
                currentDurationMin = 0
                applyServerQuote(origin, destination, dist.toDouble(), 0, "%.1f km".format(dist))
            }
        }
    }

    @Inject lateinit var voucherRepository: VoucherRepository
    /** Voucher opsional untuk order ini (kosong = tanpa voucher). */
    private var selectedVoucherCode = ""
    private var voucherOffered = false
    /** Quote ULANG setelah customer memilih voucher. */
    private var reQuote: (() -> Unit)? = null

    private fun applyServerQuote(origin: LatLng, destination: LatLng, routeKm: Double, durationMin: Int, distanceLabel: String) {
        _binding?.tvEstimatePrice?.text = "Mengambil harga server..."
        _binding?.tvEstimateDist?.text = distanceLabel
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val quote = orderRepository.quoteOrder(OrderRepository.OrderRequest(
                    serviceType = OrderPolicy.TYPE_RIDE,
                    pickupLat = origin.latitude, pickupLng = origin.longitude,
                    destLat = destination.latitude, destLng = destination.longitude,
                    distanceKm = routeKm, durationMin = durationMin
                ), selectedVoucherCode)
                if (_binding == null || pickerStep != PickerStep.CHECKOUT) return@launch
                currentDistanceKm = quote.distanceKm
                currentPrice = quote.price
                // Potongan dihitung SERVER; app hanya menampilkan: harga asli
                // dicoret, harga setelah diskon di sebelahnya.
                showPriceWithVoucher(quote.price, quote.voucherDiscount)
                updateVoucherButton()
                binding.tvEstimateDist.text = distanceLabel
                setCheckoutVisible(true)
                enableConfirmButton()
                reQuote = { applyServerQuote(origin, destination, routeKm, durationMin, distanceLabel) }
                if (selectedVoucherCode.isNotBlank() && quote.voucherDiscount <= 0L) {
                    // Ditolak server saat quote: jangan dipakai untuk membuat
                    // pesanan, kembalikan harga penuh, dan beri tahu alasannya.
                    selectedVoucherCode = ""
                    if (quote.voucherMessage.isNotBlank()) {
                        Toast.makeText(requireContext(), quote.voucherMessage, Toast.LENGTH_LONG).show()
                    }
                    reQuote?.invoke()
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.tvEstimatePrice.text = "Harga server belum tersedia"
                    setCheckoutVisible(false)
                    Toast.makeText(requireContext(), "Gagal mengambil harga: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Tombol "Pakai voucher": pilih voucher, atau lanjut tanpa voucher. */
    private fun openVoucherPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val usable = try {
                voucherRepository.usableClaims(service = "ride", spend = currentPrice)
            } catch (e: Exception) {
                emptyList()
            }
            if (_binding == null) return@launch
            VoucherPickerDialog.show(
                fragment = this@LocationPickerFragment,
                accentColorRes = R.color.brand_dark,
                claims = usable,
                selectedCode = selectedVoucherCode
            ) { code ->
                selectedVoucherCode = code
                reQuote?.invoke()
            }
        }
    }

    /**
     * Harga asli dicoret + harga setelah diskon di sebelahnya.
     * Angka setelah diskon = price - discount, keduanya dari server (app tidak
     * pernah menghitung potongan sendiri).
     */
    private fun showPriceWithVoucher(price: Long, discount: Long) {
        val b = _binding ?: return
        b.tvEstimatePrice.text = "Rp %,d".format(price)
        // Harga asli dikecilkan saat ada diskon: kartu TOTAL HARGA tingginya
        // tetap 82dp, jadi dua baris harga harus muat tanpa terpotong.
        b.tvEstimatePrice.textSize = if (discount > 0L) 13f else 18f
        if (discount > 0L) {
            b.tvEstimatePrice.paintFlags = b.tvEstimatePrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            b.tvEstimatePrice.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            b.tvEstimatePriceAfter.text = "Rp %,d".format((price - discount).coerceAtLeast(0L))
            b.tvEstimatePriceAfter.visibility = View.VISIBLE
        } else {
            b.tvEstimatePrice.paintFlags = b.tvEstimatePrice.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            b.tvEstimatePrice.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
            b.tvEstimatePriceAfter.visibility = View.GONE
        }
    }

    /** Label tombol voucher mengikuti pilihan sekarang. */
    private fun updateVoucherButton() {
        val b = _binding ?: return
        b.btnVoucher.text =
            if (selectedVoucherCode.isBlank()) "🎟️  Pakai voucher" else "🎟️  Voucher dipakai"
    }

    data class RouteResult(val polyline: List<LatLng>, val distance: Long, val duration: String, val durationSec: Long)

    private fun callDirectionsApi(origin: LatLng, destination: LatLng): RouteResult? {
        if (mapsApiKey.isEmpty()) return null

        val url = "https://maps.googleapis.com/maps/api/directions/json?" +
            "origin=${origin.latitude},${origin.longitude}" +
            "&destination=${destination.latitude},${destination.longitude}" +
            "&mode=driving" +
            "&key=$mapsApiKey"

        try {
            val jsonStr = URL(url).readText()
            val json = JSONObject(jsonStr)
            val status = json.getString("status")
            if (status != "OK") {
                Log.w(TAG, "Directions API status: $status")
                return null
            }

            val route = json.getJSONArray("routes").getJSONObject(0)
            val leg = route.getJSONArray("legs").getJSONObject(0)
            val distance = leg.getJSONObject("distance").getLong("value") // meters
            val duration = leg.getJSONObject("duration").getString("text") // "12 mins"
            val durationSec = leg.getJSONObject("duration").getLong("value") // seconds

            val points = route.getJSONObject("overview_polyline").getString("points")
            val decoded = decodePolyline(points)

            return RouteResult(decoded, distance, duration, durationSec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse directions", e)
            return null
        }
    }

    // ─── Polyline decoder ──────────────────────────────────────────────────

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }

    private fun drawRoutePolyline(points: List<LatLng>) {
        routePolyline?.remove()
        routePolyline = googleMap?.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(8f)
                .color(ContextCompat.getColor(requireContext(), R.color.brand))
                .geodesic(false)
        )
        zoomToFit(points)
    }

    private fun drawStraightLine(from: LatLng, to: LatLng) {
        routePolyline?.remove()
        routePolyline = googleMap?.addPolyline(
            PolylineOptions()
                .add(from, to)
                .width(6f)
                .color(ContextCompat.getColor(requireContext(), R.color.brand))
                .pattern(listOf(Dot(), Gap(10f)))
        )
        zoomToFit(listOf(from, to))
    }

    private fun zoomToFit(points: List<LatLng>) {
        val builder = LatLngBounds.builder()
        points.forEach { builder.include(it) }
        googleMap?.animateCamera(
            CameraUpdateFactory.newLatLngBounds(builder.build(), 150)
        )
    }

    private fun enableConfirmButton() {
        _binding?.btnConfirmRide?.isEnabled = true
        _binding?.btnConfirmRide?.setBackgroundResource(R.drawable.bg_location_primary_teal)
        val btnText = _binding?.btnConfirmRide?.getChildAt(0) as? android.widget.TextView
        btnText?.text = "Cari driver"
        btnText?.setTextColor(requireContext().getColor(R.color.brand_ink))
    }

    private fun disableConfirmButton(text: String = "Pilih tujuan di peta") {
        _binding?.btnConfirmRide?.isEnabled = false
        _binding?.btnConfirmRide?.setBackgroundResource(R.drawable.bg_location_disabled)
        val btnText = _binding?.btnConfirmRide?.getChildAt(0) as? android.widget.TextView
        btnText?.text = text
        btnText?.setTextColor(requireContext().getColor(R.color.text_muted))
    }

    // ─── Utilities ─────────────────────────────────────────────────────────

    private fun estimateDistance(from: LatLng, to: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            from.latitude, from.longitude, to.latitude, to.longitude, results
        )
        return results[0] / 1000f
    }

    private fun getAddress(latLng: LatLng, callback: (String) -> Unit) {
        try {
            val geocoder = Geocoder(requireContext(), Locale("id", "ID"))
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val parts = listOfNotNull(
                    address.thoroughfare, address.subLocality, address.locality
                ).joinToString(", ")
                callback(parts.ifEmpty { address.getAddressLine(0) ?: "Lokasi dipilih" })
            } else callback("Lokasi dipilih")
        } catch (e: IOException) { callback("Lokasi dipilih") }
    }


    /** Search produces selectable, non-committing suggestions while the keyboard is open. */
    private data class SearchSuggestion(val name: String, val address: String, val latLng: LatLng)

    private fun setupLocationSearch() {
        binding.btnSearchLocation.setOnClickListener { requestLocationSuggestions(immediate = true) }
        binding.etLocationSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && pickerStep != PickerStep.CHECKOUT) {
                showSearchAboveKeyboard()
            }
        }
        binding.etLocationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { requestLocationSuggestions() }
        })
        binding.etLocationSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                requestLocationSuggestions(immediate = true)
                true
            } else false
        }
    }

    private fun showSearchAboveKeyboard() {
        if (_binding == null) return
        BottomSheetBehavior.from(binding.bottomSheet).state = BottomSheetBehavior.STATE_EXPANDED
        binding.locationSheetScroll.post {
            if (_binding != null) {
                binding.locationSheetScroll.smoothScrollTo(
                    0,
                    (binding.layoutLocationSearch.top - 8 * resources.displayMetrics.density).toInt().coerceAtLeast(0)
                )
            }
        }
    }

    private fun requestLocationSuggestions(immediate: Boolean = false) {
        val query = binding.etLocationSearch.text?.toString()?.trim().orEmpty()
        locationSearchJob?.cancel()
        if (query.length < 3 || pickerStep == PickerStep.CHECKOUT) {
            clearSearchSuggestions()
            return
        }
        locationSearchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) delay(280)
            binding.btnSearchLocation.isEnabled = false
            binding.btnSearchLocation.text = "…"
            try {
                val suggestions = withContext(Dispatchers.IO) { fetchSearchSuggestions(query) }
                if (_binding != null && pickerStep != PickerStep.CHECKOUT && binding.etLocationSearch.text.toString().trim() == query) {
                    renderSearchSuggestions(suggestions)
                }
            } finally {
                _binding?.btnSearchLocation?.isEnabled = true
                _binding?.btnSearchLocation?.text = "⌕"
            }
        }
    }

    /** Google Places Text Search returns POIs/addresses relevant to the actual words typed. */
    private fun fetchSearchSuggestions(query: String): List<SearchSuggestion> {
        if (mapsApiKey.isNotBlank()) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val json = JSONObject(URL("https://maps.googleapis.com/maps/api/place/textsearch/json?query=$encoded&language=id&key=$mapsApiKey").readText())
                if (json.optString("status") == "OK") {
                    val results = json.getJSONArray("results")
                    return (0 until minOf(results.length(), 4)).map { index ->
                        val place = results.getJSONObject(index)
                        val location = place.getJSONObject("geometry").getJSONObject("location")
                        SearchSuggestion(
                            name = place.optString("name", "Lokasi"),
                            address = place.optString("formatted_address", "Alamat tidak tersedia"),
                            latLng = LatLng(location.getDouble("lat"), location.getDouble("lng"))
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Places text search failed; falling back to geocoder", e)
            }
        }
        return try {
            Geocoder(requireContext(), Locale("id", "ID")).getFromLocationName(query, 4)?.map { address ->
                SearchSuggestion(
                    name = listOfNotNull(address.featureName, address.thoroughfare).firstOrNull() ?: "Lokasi",
                    address = address.getAddressLine(0) ?: "Alamat tidak tersedia",
                    latLng = LatLng(address.latitude, address.longitude)
                )
            }.orEmpty()
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun renderSearchSuggestions(suggestions: List<SearchSuggestion>) {
        binding.layoutSearchSuggestions.removeAllViews()
        binding.layoutSearchSuggestions.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        suggestions.forEach { suggestion ->
            val row = layoutInflater.inflate(R.layout.item_location_search_suggestion, binding.layoutSearchSuggestions, false)
            row.findViewById<android.widget.TextView>(R.id.tv_suggestion_name).text = suggestion.name
            row.findViewById<android.widget.TextView>(R.id.tv_suggestion_address).text = suggestion.address
            row.setOnClickListener { selectSearchSuggestion(suggestion) }
            binding.layoutSearchSuggestions.addView(row)
        }
    }

    private fun clearSearchSuggestions() {
        _binding?.layoutSearchSuggestions?.removeAllViews()
        _binding?.layoutSearchSuggestions?.visibility = View.GONE
    }

    /** Selecting a suggestion changes only the pending camera center; confirm remains explicit. */
    private fun selectSearchSuggestion(suggestion: SearchSuggestion) {
        clearSearchSuggestions()
        binding.etLocationSearch.setText(suggestion.name)
        locationSearchJob?.cancel()
        binding.etLocationSearch.clearFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.etLocationSearch.windowToken, 0)
        BottomSheetBehavior.from(binding.bottomSheet).state = BottomSheetBehavior.STATE_COLLAPSED
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(suggestion.latLng, 17f))
        isUserMovingMap = true
        if (pickerStep == PickerStep.DESTINATION) binding.tvDestAddress.text = suggestion.address
        else if (pickerStep == PickerStep.PICKUP) binding.tvPickupAddress.text = suggestion.address
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (hasLocationPermission()) enableMyLocationSafely()
        } else {
            Toast.makeText(requireContext(), "Izin lokasi diperlukan", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
