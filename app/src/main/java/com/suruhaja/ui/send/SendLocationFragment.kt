package com.suruhaja.ui.send

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.suruhaja.R
import com.suruhaja.data.policy.OrderPolicy
import androidx.appcompat.app.AlertDialog
import com.suruhaja.ui.voucher.VoucherPickerDialog
import com.suruhaja.data.repository.OrderRepository
import com.suruhaja.data.repository.VoucherRepository
import com.suruhaja.databinding.FragmentSendLocationBinding
import com.suruhaja.service.OrderNotificationService
import com.suruhaja.util.LocationHelper
import com.suruhaja.util.MapRouteHelper
import com.suruhaja.util.MapPinIcon
import com.suruhaja.util.NearbyDriverOverlay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SendLocationFragment : Fragment(), OnMapReadyCallback {

    @Inject lateinit var orderRepository: OrderRepository

    private var _binding: FragmentSendLocationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SendViewModel by activityViewModels()

    private var googleMap: GoogleMap? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var pickupMarker: Marker? = null
    private var destMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var driverOverlay: NearbyDriverOverlay? = null

    private var pickupLatLng: LatLng? = null
    private var destLatLng: LatLng? = null
    private var currentDistanceKm: Double = 0.0
    private var currentServerPrice: Long = 0L
    private var selectedPayment = "cash"
    private enum class PickerStep { PICKUP, DESTINATION, CHECKOUT }
    private var pickerStep = PickerStep.PICKUP
    private var isUserMovingMap = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSendLocationBinding.inflate(inflater, container, false)
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
            binding.btnConfirm.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBars.bottom
            }
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_view) as com.google.android.gms.maps.SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        // Weight / vehicle info
        binding.tvWeightInfo.text = "${viewModel.weightKg} kg · ${if (viewModel.vehicle == "car") "Mobil" else "Motor"}"

        // Payment selector
        binding.btnPayCash.setOnClickListener { setPayment("cash") }
        binding.btnPaySaldo.setOnClickListener { setPayment("saldo") }
        // Tombol "Pakai voucher": pilih voucher atau lanjut tanpa voucher.
        binding.btnVoucher.setOnClickListener { openVoucherPicker() }
        setPayment("cash")
        setCheckoutVisible(false)

        binding.tvPickupAddress.setOnClickListener {
            if (pickerStep == PickerStep.CHECKOUT) {
                pickerStep = PickerStep.PICKUP
                setCheckoutVisible(false)
                binding.ivCenterPin.visibility = View.VISIBLE
                binding.btnSetLocation.visibility = View.VISIBLE
                updatePickingModeUI()
            }
            pickupLatLng?.let { googleMap?.animateCamera(CameraUpdateFactory.newLatLng(it)) }
        }

        binding.tvDestAddress.setOnClickListener {
            if (pickupLatLng == null) {
                Toast.makeText(requireContext(), "Konfirmasi penjemputan dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickerStep == PickerStep.CHECKOUT) {
                pickerStep = PickerStep.DESTINATION
                setCheckoutVisible(false)
                binding.ivCenterPin.visibility = View.VISIBLE
                binding.btnSetLocation.visibility = View.VISIBLE
                updatePickingModeUI()
            }
            destLatLng?.let { googleMap?.animateCamera(CameraUpdateFactory.newLatLng(it)) }
        }

        binding.btnMyLocation.setOnClickListener {
            fetchCurrentLocation()
        }

        binding.btnSetLocation.setOnClickListener {
            val center = googleMap?.cameraPosition?.target ?: return@setOnClickListener
            when (pickerStep) {
                PickerStep.PICKUP -> { setPickup(center); pickerStep = PickerStep.DESTINATION }
                PickerStep.DESTINATION -> { setDestination(center); pickerStep = PickerStep.CHECKOUT }
                PickerStep.CHECKOUT -> return@setOnClickListener
            }
            if (pickerStep != PickerStep.CHECKOUT) showPickerPin()
            else binding.ivCenterPin.visibility = View.INVISIBLE
            binding.btnSetLocation.visibility = if (pickerStep == PickerStep.CHECKOUT) View.GONE else View.VISIBLE
            updatePickingModeUI()
        }

        binding.btnConfirm.setOnClickListener {
            if (pickupLatLng == null || destLatLng == null) {
                Toast.makeText(requireContext(), "Tentukan lokasi penjemputan & tujuan dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (currentDistanceKm <= 0.0) {
                Toast.makeText(requireContext(), "Jarak terlalu dekat / belum valid", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Anti-double-tap: tombol jadi abu-abu + teks proses, biar user tahu app merespons (bukan hang).
            setConfirmLoading(true)
            viewLifecycleOwner.lifecycleScope.launch {
                createSendOrder()
            }
        }

        updatePickingModeUI()
    }

    private fun updatePickingModeUI() {
        val pickup = pickerStep == PickerStep.PICKUP
        binding.ivCenterPin.setImageResource(
            if (pickup) R.drawable.ic_map_pin_web_green else R.drawable.ic_map_pin_web_coral
        )
        binding.btnSetLocation.text = when (pickerStep) {
            PickerStep.PICKUP -> "Konfirmasi Penjemputan"
            PickerStep.DESTINATION -> "Konfirmasi Tujuan"
            PickerStep.CHECKOUT -> "Harga dari server"
        }
        binding.btnSetLocation.setBackgroundResource(
            if (pickup) R.drawable.bg_send_primary_green else R.drawable.bg_location_primary_coral
        )
        binding.tvPickupAddress.alpha = if (pickerStep == PickerStep.PICKUP) 1.0f else 0.6f
        binding.tvDestAddress.alpha = if (pickerStep == PickerStep.DESTINATION) 1.0f else 0.6f
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        driverOverlay = NearbyDriverOverlay(viewLifecycleOwner.lifecycleScope, map, orderRepository.observeNearbyDrivers())
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        com.suruhaja.util.ThemeManager.applyMapStyle(requireContext(), map)

        fetchInitialLocation()

        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isUserMovingMap = true
                if (pickerStep != PickerStep.CHECKOUT) {
                    showPickerPin(lifted = true)
                    binding.btnSetLocation.visibility = View.GONE
                }
            }
        }

        map.setOnCameraIdleListener {
            if (isUserMovingMap) {
                if (pickerStep != PickerStep.CHECKOUT) {
                    showPickerPin(lifted = false)
                    binding.btnSetLocation.visibility = View.VISIBLE
                }
                isUserMovingMap = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchFreshLocation(forceFresh = true) ?: return@launch
            val latLng = LatLng(location.latitude, location.longitude)
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
        }
    }

    /** Ambil lokasi: cache segar → one-shot → requestLocationUpdates. Lihat LocationHelper. */
    @SuppressLint("MissingPermission")
    private suspend fun fetchFreshLocation(forceFresh: Boolean = false): Location? {
        return LocationHelper.fetch(fusedLocationClient, forceFresh)
    }

    @SuppressLint("MissingPermission")
    private fun fetchInitialLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2001)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchFreshLocation()
            val latLng = if (location != null) {
                LatLng(location.latitude, location.longitude)
            } else {
                LatLng(-6.2088, 106.8456) // Jakarta fallback
            }
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            // GPS only centers the map and scopes nearby Drivers; pickup needs confirmation.
            driverOverlay?.setCenter(latLng)
        }
    }

    private fun setPickup(latLng: LatLng) {
        pickupLatLng = latLng
        pickupMarker?.remove()
        pickupMarker = googleMap?.addMarker(
            MarkerOptions().position(latLng).title("Penjemputan")
                .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_brand))
                .anchor(0.5f, 1f)
        )
        getAddress(latLng) { _binding?.tvPickupAddress?.text = it }
        updateRouteAndPrice()
    }

    private fun setDestination(latLng: LatLng) {
        destLatLng = latLng
        destMarker?.remove()
        destMarker = googleMap?.addMarker(
            MarkerOptions().position(latLng).title("Tujuan")
                .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_coral))
                .anchor(0.5f, 1f)
        )
        getAddress(latLng) { _binding?.tvDestAddress?.text = it }
        updateRouteAndPrice()
    }

    @Inject lateinit var voucherRepository: VoucherRepository
    /** Voucher opsional untuk order ini (kosong = tanpa voucher). */
    private var selectedVoucherCode = ""
    private var voucherOffered = false
    /** Quote ULANG setelah customer memilih voucher. */
    private var reQuote: (() -> Unit)? = null

    private fun updateRouteAndPrice() {
        val map = googleMap ?: return
        val from = pickupLatLng ?: return
        val to = destLatLng ?: return

        // Rute jalan asli (Directions API), seperti SuruhRide — bukan garis lurus.
        viewLifecycleOwner.lifecycleScope.launch {
            val points = MapRouteHelper.fetchRoutePoints(requireContext(), from, to)
            if (points != null && isAdded) {
                routeLine?.remove()
                routeLine = MapRouteHelper.drawRoute(map, points, "#EA4335")
            }
        }

        currentDistanceKm = estimateDistance(from, to).toDouble()
        binding.tvEstimatePrice.text = "Mengambil harga server..."
        binding.tvEstimateDist.text = "%.1f km".format(currentDistanceKm)
        setCheckoutVisible(false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val quote = orderRepository.quoteOrder(OrderRepository.OrderRequest(
                    serviceType = OrderPolicy.TYPE_SEND,
                    pickupLat = from.latitude, pickupLng = from.longitude,
                    destLat = to.latitude, destLng = to.longitude,
                    distanceKm = currentDistanceKm, weightKg = viewModel.weightKg
                ), selectedVoucherCode)
                if (_binding == null || pickerStep != PickerStep.CHECKOUT) return@launch
                currentDistanceKm = quote.distanceKm
                currentServerPrice = quote.price
                // Potongan dihitung SERVER; app hanya menampilkan: harga asli
                // dicoret, harga setelah diskon di sebelahnya.
                showPriceWithVoucher(quote.price, quote.voucherDiscount)
                updateVoucherButton()
                binding.tvEstimateDist.text = "%.1f km".format(quote.distanceKm)
                setCheckoutVisible(true)
                reQuote = { updateRouteAndPrice() }
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
                    Toast.makeText(requireContext(), "Gagal mengambil harga: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Tawarkan voucher SETELAH harga server tampil (sekali per layar).
     * Memilih voucher = quote ulang memakai kodenya; potongan tetap dihitung
     * server (app tidak pernah menghitung diskon sendiri).
     */
    /** Tombol "Pakai voucher": pilih voucher, atau lanjut tanpa voucher. */
    private fun openVoucherPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val usable = try {
                voucherRepository.usableClaims(service = "send", spend = currentServerPrice)
            } catch (e: Exception) {
                emptyList()
            }
            if (_binding == null) return@launch
            VoucherPickerDialog.show(
                fragment = this@SendLocationFragment,
                accentColorRes = R.color.send_green_text,
                claims = usable,
                selectedCode = selectedVoucherCode
            ) { code ->
                selectedVoucherCode = code
                reQuote?.invoke()
            }
        }
    }

    /** Harga asli dicoret + harga setelah diskon di sebelahnya (angka dari server). */
    private fun showPriceWithVoucher(price: Long, discount: Long) {
        val b = _binding ?: return
        b.tvEstimatePrice.text = "Rp %,d".format(price)
        // Dua baris harga: yang asli (dicoret) lebih kecil, yang baru lebih besar.
        b.tvEstimatePrice.textSize = if (discount > 0L) 14f else 22f
        if (discount > 0L) {
            b.tvEstimatePrice.paintFlags = b.tvEstimatePrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            b.tvEstimatePrice.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            b.tvEstimatePriceAfter.text = "Rp %,d".format((price - discount).coerceAtLeast(0L))
            b.tvEstimatePriceAfter.visibility = View.VISIBLE
        } else {
            b.tvEstimatePrice.paintFlags = b.tvEstimatePrice.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            b.tvEstimatePrice.setTextColor(ContextCompat.getColor(requireContext(), R.color.send_green_text))
            b.tvEstimatePriceAfter.visibility = View.GONE
        }
    }

    /** Label tombol voucher mengikuti pilihan sekarang. */
    private fun updateVoucherButton() {
        val b = _binding ?: return
        b.btnVoucher.text =
            if (selectedVoucherCode.isBlank()) "\uD83C\uDF9F\uFE0F  Pakai voucher" else "\uD83C\uDF9F\uFE0F  Voucher dipakai"
    }

    private fun setPayment(method: String) {
        selectedPayment = method
        val cash = method == "cash"
        binding.btnPayCash.setBackgroundResource(if (cash) R.drawable.bg_send_primary_green else R.drawable.bg_send_pay_idle)
        binding.btnPayCash.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
        binding.btnPaySaldo.setBackgroundResource(if (!cash) R.drawable.bg_send_primary_green else R.drawable.bg_send_pay_idle)
        binding.btnPaySaldo.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
    }

    private fun showPickerPin(lifted: Boolean = false) {
        binding.ivCenterPin.animate().cancel()
        binding.ivCenterPin.visibility = View.VISIBLE
        binding.ivCenterPin.alpha = 1f
        binding.ivCenterPin.animate()
            .translationY(if (lifted) -100f else -48f)
            .setDuration(160)
            .start()
    }

    private fun setCheckoutVisible(visible: Boolean) {
        binding.layoutEstimate.visibility = if (visible) View.VISIBLE else View.GONE
        binding.layoutPayment.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnVoucher.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnConfirm.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setConfirmLoading(loading: Boolean) {
        val b = _binding ?: return
        b.btnConfirm.isEnabled = !loading
        b.btnConfirm.alpha = if (loading) 0.5f else 1f
        (b.btnConfirm.getChildAt(0) as? android.widget.TextView)?.text =
            if (loading) "MEMBUAT KIRIMAN..." else "Pesan Kurir"
    }

    private suspend fun createSendOrder() {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
            if (!OrderPolicy.canCreateOrder(OrderPolicy.TYPE_SEND, orderRepository.countActiveOrders(uid, OrderPolicy.TYPE_SEND))) {
                setConfirmLoading(false)
                Toast.makeText(requireContext(), "Terlalu banyak kiriman aktif. Selesaikan dulu.", Toast.LENGTH_LONG).show()
                return
            }

            var customerName = "Pelanggan"
            var customerPhone = ""
            try {
                val userDoc = FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
                customerPhone = userDoc.getString("phone") ?: ""
                customerName = userDoc.getString("name")?.ifBlank {
                    customerPhone.ifBlank { "Pelanggan" }
                } ?: "Pelanggan"
            } catch (_: Exception) {}

            if (currentServerPrice <= 0L) {
                setConfirmLoading(false)
                Toast.makeText(requireContext(), "Menunggu harga dari server", Toast.LENGTH_SHORT).show()
                return
            }
            val price = currentServerPrice
            val order = OrderRepository.OrderRequest(
                userId = uid,
                customerName = customerName,
                customerPhone = customerPhone,
                serviceType = OrderPolicy.TYPE_SEND,
                paymentMethod = selectedPayment,
                pickup = _binding?.tvPickupAddress?.text?.toString() ?: "Penjemputan",
                pickupLat = pickupLatLng?.latitude ?: 0.0,
                pickupLng = pickupLatLng?.longitude ?: 0.0,
                destination = _binding?.tvDestAddress?.text?.toString() ?: "Tujuan",
                destLat = destLatLng?.latitude ?: 0.0,
                destLng = destLatLng?.longitude ?: 0.0,
                distanceKm = currentDistanceKm,
                durationMin = 0,
                price = price,
                itemName = viewModel.itemName,
                itemDesc = viewModel.itemDesc,
                weightKg = viewModel.weightKg,
                receiverName = viewModel.receiverName,
                receiverPhone = viewModel.receiverPhone,
                senderName = viewModel.senderName,
                senderPhone = viewModel.senderPhone,
                vehicle = viewModel.vehicle
            )
            val orderId = orderRepository.createOrder(order, selectedVoucherCode)
            OrderNotificationService.start(requireContext())
            viewModel.reset()
            Toast.makeText(requireContext(), "Kiriman dibuat! Mencari driver...", Toast.LENGTH_SHORT).show()
            val bundle = Bundle().apply { putString("orderId", orderId) }
            findNavController().navigate(R.id.orderTrackingFragment, bundle)
        } catch (e: Exception) {
            setConfirmLoading(false)
            Toast.makeText(requireContext(), "Gagal buat kiriman: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun estimateDistance(from: LatLng, to: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            from.latitude, from.longitude, to.latitude, to.longitude, results
        )
        return results[0] / 1000f
    }

    private fun getAddress(latLng: LatLng, callback: (String) -> Unit) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(ctx, Locale("id", "ID"))
                    val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val parts = listOfNotNull(address.thoroughfare, address.subLocality, address.locality)
                            .joinToString(", ")
                        parts.ifEmpty { address.getAddressLine(0) ?: "Lokasi dipilih" }
                    } else "Lokasi dipilih"
                } catch (e: Exception) { "Lokasi dipilih" }
            }
            callback(result)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pickupMarker = null
        destMarker = null
        routeLine = null
        googleMap = null
        _binding = null
    }
}
