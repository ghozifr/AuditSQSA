package com.suruhaja.ui.food

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.suruhaja.R
import com.suruhaja.data.repository.OrderRepository
import com.suruhaja.databinding.FragmentFoodLocationBinding
import com.suruhaja.service.OrderNotificationService
import androidx.appcompat.app.AlertDialog
import com.suruhaja.ui.voucher.VoucherPickerDialog
import com.suruhaja.data.repository.VoucherRepository
import com.suruhaja.util.LocationHelper
import com.suruhaja.util.MapRouteHelper
import com.suruhaja.util.MapPinIcon
import com.suruhaja.util.NearbyDriverOverlay
import com.suruhaja.util.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

@AndroidEntryPoint
class FoodLocationFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentFoodLocationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FoodLocationViewModel by viewModels()

    @Inject lateinit var orderRepository: OrderRepository
    @Inject lateinit var voucherRepository: VoucherRepository
    /** Kode voucher yang dipakai untuk order ini (kosong = tanpa voucher). */
    private var selectedVoucherCode = ""
    /** Titik antar terakhir yang sudah di-quote, untuk quote ulang setelah pilih voucher. */
    private var lastQuoteLatLng: LatLng? = null
    private var voucherOffered = false

    private var googleMap: GoogleMap? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var merchantMarker: Marker? = null
    private var customerMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var driverOverlay: NearbyDriverOverlay? = null

    private val merchantLatLng: LatLng by lazy {
        LatLng(
            arguments?.getFloat("merchantLat")?.toDouble() ?: 0.0,
            arguments?.getFloat("merchantLng")?.toDouble() ?: 0.0
        )
    }

    /** Lokasi customer yang sudah diketahui (argumen checkout / cache GPS).
     *  Dipakai supaya peta langsung terpusat ke customer tanpa menunggu GPS. */
    private var lastKnownLatLng: LatLng? = null
    private var locationAnnounced = false

    private var deliveryLatLng: LatLng? = null
    /** Customer sudah menggeser peta / memilih titik antar sendiri → jangan
     *  ditimpa lagi oleh lokasi otomatis. */
    private var userTookOver = false
    private var deliveryAddress: String = ""
    private var selectedPaymentMethod = "cash"
    private var quotedDistanceKm: Double = 0.0
    private var quotedDeliveryFee: Long = 0L
    private var quotedAdminFee: Long = 0L
    private var isUserMovingMap = false

    companion object {
        private const val TAG = "FoodLocation"
        private const val LOCATION_PERMISSION_REQUEST = 1101
        private const val GPS_ENABLE_REQUEST = 1102
        /** Jarak minimum (km) sebelum titik antar otomatis diperbarui lagi. */
        private const val AUTO_UPDATE_MIN_KM = 0.05
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFoodLocationBinding.inflate(inflater, container, false)
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

            // Adjust bottom panel to float above the nav bar or adjust its padding
            binding.bottomPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // If we want it to sit AT the bottom, we might not need navBar.bottom margin
                // but since it's a solid card background extending to edges in ActivityMain,
                // we probably want it to extend behind the nav pill but pad the content.
                // However, current ActivityMain nav_host has constraintBottom_toBottomOf="parent".
                // Let's ensure the button is above nav pill.
                bottomMargin = navBars.bottom
            }
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        val mapFragment = childFragmentManager.findFragmentById(R.id.map_view) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.btnPayCash.setOnClickListener { setPaymentMethod("cash") }
        binding.btnPaySaldo.setOnClickListener { setPaymentMethod("saldo") }
        // Tombol "Pakai voucher": pilih voucher atau lanjut tanpa voucher.
        binding.btnVoucher.setOnClickListener { openVoucherPicker() }
        setPaymentMethod("cash")
        setCheckoutVisible(false)

        // Tombol "lokasi saya" = minta eksplisit pakai lokasi customer, jadi
        // izinkan lagi titik antar diisi otomatis dari hasil deteksi ini.
        binding.btnMyLocation.setOnClickListener {
            userTookOver = false
            fetchCurrentLocation(forceFresh = true)
        }

        // Konfirmasi titik antar custom (pin peta) — bukan hanya GPS customer.
        binding.btnSetLocation.setOnClickListener {
            val center = googleMap?.cameraPosition?.target ?: return@setOnClickListener
            // Pilihan eksplisit customer → hentikan pemakaian lokasi otomatis.
            userTookOver = true
            setDeliveryLocation(center)
            binding.btnSetLocation.visibility = View.GONE
            binding.ivCenterPin.visibility = View.GONE
        }

        binding.btnConfirm.setOnClickListener { placeOrder() }

        // Lokasi customer dari argumen checkout (kalau sudah diketahui) → dipakai
        // langsung, jadi layar ini tidak mulai dari "kosong".
        val argLat = arguments?.getFloat("customerLat") ?: 0f
        val argLng = arguments?.getFloat("customerLng") ?: 0f
        if (argLat != 0f || argLng != 0f) {
            val argLatLng = LatLng(argLat.toDouble(), argLng.toDouble())
            lastKnownLatLng = argLatLng
            // Lokasi sudah ada saat checkout → langsung dipakai sebagai titik
            // antar, jadi harga diambil dari server tanpa menunggu GPS/peta.
            maybeAutoUseDeliveryPoint(argLatLng)
        }

        // Mulai deteksi lokasi SEKARANG — jangan tunggu onMapReady, karena
        // peta bisa telat beberapa detik (Play Services + tile).
        startLocationWarmUp()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        driverOverlay = NearbyDriverOverlay(viewLifecycleOwner.lifecycleScope, map, orderRepository.observeNearbyDrivers())
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        ThemeManager.applyMapStyle(requireContext(), map)

        // Pin toko (destination) — marker merah fixed
        merchantMarker?.remove()
        merchantMarker = map.addMarker(
            MarkerOptions()
                .position(merchantLatLng)
                .title("Toko")
                .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_coral))
                .anchor(0.5f, 1f)
        )

        // Kalau lokasi customer sudah diketahui (argumen checkout / cache GPS),
        // peta langsung dipusatkan ke customer; kalau belum → pin toko dulu.
        val known = lastKnownLatLng
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(known ?: merchantLatLng, if (known != null) 16f else 15f)
        )
        known?.let { driverOverlay?.setCenter(it) }

        // Geser peta → tampilkan pin + tombol "Pilih Lokasi Ini" (titik antar custom).
        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isUserMovingMap = true
                // Customer mulai mengatur titik antar sendiri → lokasi otomatis
                // tidak boleh menimpanya lagi.
                userTookOver = true
                binding.btnSetLocation.visibility = View.GONE
                binding.ivCenterPin.visibility = View.VISIBLE
            }
        }
        map.setOnCameraIdleListener {
            if (isUserMovingMap) {
                isUserMovingMap = false
                binding.btnSetLocation.visibility = View.VISIBLE
            }
        }
        map.setOnMapClickListener { latLng ->
            map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            isUserMovingMap = true
        }

        // Titik antar sudah ditentukan sebelum peta siap (dari argumen checkout,
        // cache lokasi, atau auto-pakai lokasi)? Gambar marker + rutenya sekarang.
        deliveryLatLng?.let { point ->
            showCustomerMarker(point)
            fitCameraToRoute()
        }
    }

    /**
     * Food: titik antar diisi otomatis dari lokasi customer (kalau sudah ada),
     * lalu harga diambil dari server. Customer tetap bisa menggeser peta dan
     * menekan "Pilih Lokasi Ini" untuk memakai titik lain.
     */
    private fun setDeliveryLocation(latLng: LatLng) {
        deliveryLatLng = latLng
        showCustomerMarker(latLng)
        fitCameraToRoute()
        requestServerQuote(latLng)
    }

    private fun showCustomerMarker(latLng: LatLng) {
        val map = googleMap ?: return
        customerMarker?.remove()
        customerMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Titik antar")
                .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_brand))
                .anchor(0.5f, 1f)
        )
        // Rute jalan asli toko → titik antar (seperti SuruhRide).
        viewLifecycleOwner.lifecycleScope.launch {
            val points = MapRouteHelper.fetchRoutePoints(requireContext(), merchantLatLng, latLng)
            if (points != null && isAdded) {
                routePolyline?.remove()
                routePolyline = MapRouteHelper.drawRoute(map, points, "#EA4335")
            }
        }
    }

    private fun fitCameraToRoute() {
        val map = googleMap ?: return
        val customer = deliveryLatLng ?: return
        val bounds = LatLngBounds.Builder()
            .include(merchantLatLng)
            .include(customer)
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
    }

    private fun requestServerQuote(latLng: LatLng) {
        setCheckoutVisible(false)
        binding.tvDeliveryFee.text = "Mengambil harga dari server..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val address = withContext(Dispatchers.IO) { reverseGeocode(requireContext().applicationContext, latLng) }
                val localDistanceKm = distanceKm(latLng.latitude, latLng.longitude, merchantLatLng.latitude, merchantLatLng.longitude)
                val quote = viewModel.quote(latLng.latitude, latLng.longitude, localDistanceKm, selectedVoucherCode)
                if (_binding == null || deliveryLatLng != latLng) return@launch
                deliveryAddress = address
                quotedDistanceKm = quote.distanceKm
                quotedDeliveryFee = quote.deliveryFee
                quotedAdminFee = quote.adminFee
                val foodTotal = arguments?.getLong("total") ?: 0L
                binding.tvAddress.text = address
                // Potongan voucher dihitung SERVER; app hanya menampilkan.
                binding.tvDeliveryFee.text =
                    "Ongkir Rp %,d · %.1f km".format(quote.deliveryFee, quote.distanceKm)
                binding.tvAdminFee.text = "Biaya layanan Rp %,d".format(quote.adminFee)
                binding.tvFoodTotal.text = "Total makanan Rp %,d".format(foodTotal)
                lastQuoteLatLng = latLng
                // Potongan dihitung SERVER; app hanya menampilkan: total asli
                // dicoret, total setelah diskon di sebelahnya.
                showTotalWithVoucher(
                    foodTotal + quote.deliveryFee + quote.adminFee,
                    quote.voucherDiscount
                )
                updateVoucherButton()
                setCheckoutVisible(true)

                if (selectedVoucherCode.isNotBlank() && quote.voucherDiscount <= 0L) {
                    // Voucher ditolak server saat quote -> jangan dipakai untuk
                    // membuat pesanan, dan beri tahu alasannya.
                    selectedVoucherCode = ""
                    if (quote.voucherMessage.isNotBlank()) {
                        Toast.makeText(requireContext(), quote.voucherMessage, Toast.LENGTH_LONG).show()
                    }
                    requestServerQuote(latLng)
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.tvDeliveryFee.text = "Harga server belum tersedia"
                    Toast.makeText(requireContext(), "Gagal mengambil harga: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setCheckoutVisible(visible: Boolean) {
        binding.btnPayGroup.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnVoucher.visibility = if (visible) View.VISIBLE else View.GONE
        binding.etNotes.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnConfirm.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun reverseGeocode(ctx: android.content.Context, latLng: LatLng): String {
        return try {
            val geocoder = Geocoder(ctx, Locale("id", "ID"))
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val parts = listOfNotNull(
                    address.thoroughfare, address.subLocality, address.locality
                ).joinToString(", ")
                parts.ifEmpty { address.getAddressLine(0) ?: "Lokasi kamu" }
            } else {
                "Lokasi kamu"
            }
        } catch (e: IOException) {
            "Lokasi kamu"
        }
    }

    private fun placeOrder() {
        val latLng = deliveryLatLng
        if (latLng == null) {
            Toast.makeText(requireContext(), "Menunggu lokasi GPS...", Toast.LENGTH_SHORT).show()
            return
        }
        if (quotedDistanceKm <= 0.0 || quotedDeliveryFee <= 0L) {
            Toast.makeText(requireContext(), "Menunggu harga dari server", Toast.LENGTH_SHORT).show()
            return
        }
        val distanceKm = quotedDistanceKm
        val deliveryFee = quotedDeliveryFee
        val notes = binding.etNotes.text.toString().trim()
        val address = deliveryAddress.ifEmpty { binding.tvAddress.text.toString() }

        viewLifecycleOwner.lifecycleScope.launch {
            _binding?.btnConfirm?.isEnabled = false
            _binding?.btnConfirm?.text = "MEMBUAT PESANAN..."
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val orderId = viewModel.createOrder(
                    customerAddress = address,
                    customerLat = latLng.latitude,
                    customerLng = latLng.longitude,
                    distanceKm = distanceKm,
                    deliveryFee = deliveryFee,
                    paymentMethod = selectedPaymentMethod,
                    notes = notes,
                    voucherCode = selectedVoucherCode
                )
                Log.d(TAG, "order created ${SystemClock.elapsedRealtime() - startedAt}ms id=$orderId")
                if (orderId.isBlank()) throw IllegalStateException("ID pesanan tidak diterima")
                OrderNotificationService.start(requireContext())
                Toast.makeText(requireContext(), "Pesanan dibuat! Mencari driver...", Toast.LENGTH_SHORT).show()
                val bundle = Bundle().apply { putString("orderId", orderId) }
                findNavController().navigate(R.id.foodTrackingFragment, bundle)
            } catch (e: Exception) {
                _binding?.btnConfirm?.isEnabled = true
                _binding?.btnConfirm?.text = confirmButtonText()
                Toast.makeText(requireContext(), "Gagal buat pesanan: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setPaymentMethod(method: String) {
        selectedPaymentMethod = method
        val cash = method == "cash"
        binding.btnPayCash.text = if (cash) "💵\nTunai ✓" else "💵\nTunai"
        binding.btnPaySaldo.text = if (cash) "📱\nSaldo" else "📱\nSaldo ✓"
        binding.btnPayCash.isSelected = cash
        binding.btnPaySaldo.isSelected = !cash
        binding.btnPayCash.contentDescription = if (cash) "Tunai, dipilih" else "Pilih pembayaran tunai"
        binding.btnPaySaldo.contentDescription = if (cash) "Pilih pembayaran saldo" else "Saldo, dipilih"
        binding.btnPayCash.setBackgroundResource(
            if (cash) R.drawable.bg_food_primary_orange else R.drawable.bg_food_pay_idle
        )
        binding.btnPayCash.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.brand_ink)
        )
        binding.btnPaySaldo.setBackgroundResource(
            if (!cash) R.drawable.bg_food_primary_orange else R.drawable.bg_food_pay_idle
        )
        binding.btnPaySaldo.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.brand_ink)
        )
        binding.btnConfirm.text = confirmButtonText()
    }

    private fun confirmButtonText(): String =
        if (selectedPaymentMethod == "saldo") "PESAN • SALDO" else "PESAN • TUNAI"

    /**
     * Mulai deteksi lokasi secepat mungkin, TANPA menunggu peta siap:
     *  1) pakai lokasi terakhir yang diketahui → peta langsung terpusat ke
     *     customer begitu layar terbuka (instan, tanpa nunggu GPS),
     *  2) sambil itu, ambil fix baru (cache segar < 3 menit / GPS) untuk
     *     memperbarui posisi.
     */
    private fun startLocationWarmUp() {
        viewLifecycleOwner.lifecycleScope.launch {
            val peek = LocationHelper.peekLast(fusedLocationClient)
            if (peek != null && isAdded) {
                // animate = false → peta langsung lompat ke posisi customer.
                applyKnownLocation(LatLng(peek.latitude, peek.longitude), animate = false)
            }
        }
        fetchCurrentLocation()
    }

    /**
     * Pusatkan peta ke lokasi customer yang sudah diketahui.
     * Kalau peta belum siap, lokasi disimpan dan dipakai saat onMapReady.
     *
     * [animate] = false dipakai untuk lokasi instan supaya begitu layar terbuka
     * posisinya sudah terlihat, bukan bergerak menyusul.
     */
    private fun applyKnownLocation(latLng: LatLng, animate: Boolean) {
        lastKnownLatLng = latLng
        googleMap?.let { map ->
            if (animate) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            } else {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            }
            driverOverlay?.setCenter(latLng)
        }
        // Titik antar otomatis — dijalankan juga saat peta belum siap supaya
        // harga bisa langsung diambil dari server (marker + rute menyusul di
        // onMapReady).
        maybeAutoUseDeliveryPoint(latLng)
    }

    /**
     * Pakai lokasi customer yang sudah terdeteksi sebagai TITIK ANTAR secara
     * otomatis, supaya harga langsung muncul begitu layar ini dibuka.
     *
     * Sebelumnya customer harus menggeser peta lalu menekan "Pilih Lokasi Ini"
     * dulu walaupun lokasinya sudah terbaca — UX-nya terasa seperti lagging.
     *
     * Tidak menimpa pilihan customer:
     *  - berhenti kalau customer sudah menggeser peta / memilih titik sendiri
     *  - pembaruan berikutnya hanya kalau titik bergeser berarti (>= 50 m),
     *    supaya harga tidak berkedip hanya karena beda beberapa meter
     */
    private fun maybeAutoUseDeliveryPoint(latLng: LatLng) {
        if (userTookOver) return
        val current = deliveryLatLng
        if (current != null &&
            distanceKm(current.latitude, current.longitude, latLng.latitude, latLng.longitude) < AUTO_UPDATE_MIN_KM
        ) return

        // Titik antar sudah terisi otomatis → pin tengah & tombol pilih tidak
        // perlu tampil (keduanya muncul lagi begitu customer menggeser peta).
        binding.ivCenterPin.visibility = View.GONE
        binding.btnSetLocation.visibility = View.GONE
        setDeliveryLocation(latLng)
    }

    /**
     * Tawarkan voucher SETELAH harga server tampil (sekali per layar).
     * Memilih voucher = quote ULANG memakai kodenya; server yang menghitung
     * potongan — app tidak pernah menghitung diskon sendiri.
     */
    /** Tombol "Pakai voucher": pilih voucher, atau lanjut tanpa voucher. */
    private fun openVoucherPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val usable = try {
                voucherRepository.usableClaims(
                    service = "food",
                    spend = arguments?.getLong("total") ?: 0L,
                    merchantId = arguments?.getString("merchantId").orEmpty()
                )
            } catch (e: Exception) {
                emptyList()
            }
            if (_binding == null) return@launch
            VoucherPickerDialog.show(
                fragment = this@FoodLocationFragment,
                accentColorRes = R.color.food_orange_text,
                claims = usable,
                selectedCode = selectedVoucherCode
            ) { code ->
                selectedVoucherCode = code
                lastQuoteLatLng?.let { requestServerQuote(it) }
            }
        }
    }

    /** Total asli dicoret + total setelah diskon di sebelahnya (angka dari server). */
    private fun showTotalWithVoucher(total: Long, discount: Long) {
        val b = _binding ?: return
        b.tvGrandTotal.text = "Total Rp %,d".format(total)
        // Dua baris total: yang asli (dicoret) lebih kecil, yang baru lebih besar.
        b.tvGrandTotal.textSize = if (discount > 0L) 13f else 18f
        if (discount > 0L) {
            b.tvGrandTotal.paintFlags = b.tvGrandTotal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            b.tvGrandTotal.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            b.tvGrandTotalAfter.text = "Rp %,d".format((total - discount).coerceAtLeast(0L))
            b.tvGrandTotalAfter.visibility = View.VISIBLE
        } else {
            b.tvGrandTotal.paintFlags = b.tvGrandTotal.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            b.tvGrandTotal.setTextColor(ContextCompat.getColor(requireContext(), R.color.food_orange_text))
            b.tvGrandTotalAfter.visibility = View.GONE
        }
    }

    /** Label tombol voucher mengikuti pilihan sekarang. */
    private fun updateVoucherButton() {
        val b = _binding ?: return
        b.btnVoucher.text =
            if (selectedVoucherCode.isBlank()) "\uD83C\uDF9F\uFE0F  Pakai voucher" else "\uD83C\uDF9F\uFE0F  Voucher dipakai"
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(forceFresh: Boolean = false) {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        if (!isGpsEnabled()) {
            promptEnableGps()
            return
        }
        val client = fusedLocationClient ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val location = LocationHelper.fetch(client, forceFresh)
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                // GPS hanya memusatkan peta Food; titik antar tetap harus
                // dikonfirmasi eksplisit oleh customer.
                applyKnownLocation(latLng, animate = true)
                if (_binding != null && !locationAnnounced) {
                    locationAnnounced = true
                    Toast.makeText(requireContext(), "Lokasi terdeteksi", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (_binding != null) {
                    Toast.makeText(requireContext(), "Lokasi belum tersedia, coba lagi", Toast.LENGTH_SHORT).show()
                }
            }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GPS_ENABLE_REQUEST && isGpsEnabled()) {
            fetchCurrentLocation()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            fetchCurrentLocation()
        } else {
            Toast.makeText(requireContext(), "Izin lokasi diperlukan", Toast.LENGTH_LONG).show()
        }
    }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        merchantMarker?.remove()
        merchantMarker = null
        customerMarker?.remove()
        customerMarker = null
        routePolyline?.remove()
        routePolyline = null
        googleMap = null
        _binding = null
    }
}
