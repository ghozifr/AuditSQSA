package com.suruhaja.ui.tracking

import android.content.Intent
import android.content.res.ColorStateList
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.snackbar.Snackbar
import com.suruhaja.R
import com.suruhaja.databinding.FragmentOrderTrackingBinding
import com.suruhaja.data.policy.OrderMoneyPolicy
import com.suruhaja.realtime.RealtimeStatePolicy
import com.bumptech.glide.Glide
import com.suruhaja.ui.chat.ChatSheetFragment
import com.suruhaja.util.MapRouteHelper
import com.suruhaja.util.MapPinIcon
import com.suruhaja.util.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OrderTrackingFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentOrderTrackingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OrderTrackingViewModel by viewModels()
    private var googleMap: GoogleMap? = null
    private var routePolyline: com.google.android.gms.maps.model.Polyline? = null
    private var cancellationDialogShowing = false
    private var ratingShown = false
    private var paymentPromptShown = false
    private var lastDriverLat = 0.0
    private var lastDriverLng = 0.0
    private var chatBadge: BadgeDrawable? = null
    private var reconnectSnackbar: Snackbar? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.btnBackTracking.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (20 * resources.displayMetrics.density).toInt()
            }
            binding.statusCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBars.bottom
            }
            insets
        }

        val orderId = arguments?.getString("orderId") ?: ""
        viewModel.loadOrder(orderId)

        viewModel.serviceType.observe(viewLifecycleOwner) {
            applyServiceAccent()
            viewModel.status.value?.let { status -> updateButtonStyle(status) }
        }

        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.tvOrderStatus.text = statusText(status)
            updateStepper(status)
            // Re-draw route when phase changes (accepted → pickup)
            drawDestinationAndRoute()
            updateButtonStyle(status)
            // Trip selesai → langsung tampilkan rating (tanpa prompt pembayaran).
            if (status == "completed" && !ratingShown) {
                ratingShown = true
                showRatingDialog()
            }
        }

        viewModel.paymentMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        viewModel.paymentPrompt.observe(viewLifecycleOwner) { show ->
            // Dialog otomatis hanya sekali per permintaan driver, TAPI tombol
            // "Bayar sekarang" tetap tampil selama pembayaran belum selesai —
            // kalau customer menutup dialog (tombol back/swipe) dia tidak stuck.
            val decision = RealtimeStatePolicy.paymentPromptDecision(show, paymentPromptShown)
            paymentPromptShown = decision.markPrompted
            binding.btnPayNow.visibility = if (decision.buttonVisible) View.VISIBLE else View.GONE
            if (decision.autoShow) showPaymentPrompt()
        }
        viewModel.realtimeState.observe(viewLifecycleOwner) { state ->
            if (state.error != null) {
                reconnectSnackbar?.dismiss()
                reconnectSnackbar = Snackbar.make(binding.root, "Koneksi status pesanan terputus", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Coba lagi") { viewModel.refreshFromServer() }
                    .also { it.show() }
            } else if (!state.reconnecting) {
                reconnectSnackbar?.dismiss()
                reconnectSnackbar = null
            }
        }
        viewModel.price.observe(viewLifecycleOwner) { binding.tvPrice.text = it }
        viewModel.distance.observe(viewLifecycleOwner) { binding.tvDistance.text = it }
        viewModel.detail.observe(viewLifecycleOwner) { binding.tvOrderDetail.text = it }
        viewModel.driverName.observe(viewLifecycleOwner) { binding.tvDriverName.text = it }
        viewModel.driverVehicle.observe(viewLifecycleOwner) { binding.tvDriverVehicle.text = it }
        viewModel.driverVisible.observe(viewLifecycleOwner) { visible ->
            binding.layoutDriverInfo.visibility = if (visible) View.VISIBLE else View.GONE
            binding.layoutDriverActions.visibility = if (visible) View.VISIBLE else View.GONE
        }

        viewModel.driverPhoto.observe(viewLifecycleOwner) { photo ->
            if (photo.isNullOrEmpty()) {
                binding.ivDriverPhoto.visibility = View.GONE
            } else {
                binding.ivDriverPhoto.visibility = View.VISIBLE
                Glide.with(binding.root).load(photo).circleCrop().into(binding.ivDriverPhoto)
            }
        }
        viewModel.driverLatLng.observe(viewLifecycleOwner) { (lat, lng) ->
            if (lat != 0.0 && lng != 0.0) updateDriverMarker(lat, lng)
        }

        viewModel.unreadCount.observe(viewLifecycleOwner) { count -> updateChatBadge(count) }

        viewModel.cancelledBy.observe(viewLifecycleOwner) { by ->
            val status = viewModel.status.value
            if (status == "cancelled") {
                binding.tvOrderStatus.text = if (by == "driver") "Pesanan Ditolak Driver" else "Pesanan Dibatalkan"
            }
        }

        viewModel.canCancel.observe(viewLifecycleOwner) { canCancel ->
            binding.btnCancelOrder.visibility = if (canCancel) View.VISIBLE else View.GONE
        }
        viewModel.isCompleted.observe(viewLifecycleOwner) { completed ->
            if (completed) {
                binding.btnCancelOrder.visibility = View.VISIBLE
                // Style handled in updateButtonStyle(status)
            }
        }

        val mapFragment = SupportMapFragment.newInstance()
        childFragmentManager.beginTransaction()
            .replace(R.id.map_container, mapFragment).commit()
        childFragmentManager.executePendingTransactions()
        mapFragment.getMapAsync(this)

        viewModel.cancelRequestedBy.observe(viewLifecycleOwner) { by ->
            binding.btnCancelOrder.isEnabled = by != "customer"
            viewModel.status.value?.let { updateButtonStyle(it) }
        }

        // Driver requested cancellation → show approve/reject dialog
        viewModel.pendingCancellation.observe(viewLifecycleOwner) { pending ->
            if (pending && !cancellationDialogShowing) showCancellationDialog()
        }

        binding.btnChatDriver.setOnClickListener {
            val id = arguments?.getString("orderId") ?: ""
            val name = viewModel.driverName.value ?: "Driver"
            ChatSheetFragment.newInstance("orders", id, "customer", name, viewModel.serviceType.value ?: "")
                .show(childFragmentManager, "chat")
        }

        binding.btnCallDriver.setOnClickListener {
            val phone = viewModel.driverPhone.value.orEmpty()
            if (phone.isEmpty()) {
                Toast.makeText(requireContext(), "Nomor driver belum tersedia", Toast.LENGTH_SHORT).show()
            } else {
                openWhatsApp(phone)
            }
        }

        binding.btnPayNow.setOnClickListener { showPaymentPrompt() }

        binding.btnCancelOrder.setOnClickListener {
            val status = viewModel.status.value
            if (status == "completed" || status == "cancelled") {
                // Go back to home
                findNavController().navigate(R.id.homeFragment, null,
                    androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.nav_graph, false).build())
                return@setOnClickListener
            }
            when (status) {
                "pending" -> {
                    viewModel.cancelOrder()
                    Toast.makeText(requireContext(), "Pesanan dibatalkan", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
                "accepted", "pickup", "delivering" -> {
                    viewModel.requestCancel()
                    Toast.makeText(requireContext(), "Permintaan pembatalan dikirim ke driver", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateChatBadge(count: Long) {
        if (count > 0) {
            if (chatBadge == null) {
                chatBadge = BadgeDrawable.create(requireContext()).apply {
                    backgroundColor = 0xFFE53935.toInt()
                    badgeTextColor = android.graphics.Color.WHITE
                }
                BadgeUtils.attachBadgeDrawable(chatBadge!!, binding.btnChatDriver, null)
            }
            chatBadge?.number = count.coerceAtMost(99).toInt()
            chatBadge?.setVisible(true)
        } else {
            chatBadge?.setVisible(false)
        }
    }

    private fun showCancellationDialog() {
        cancellationDialogShowing = true
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Driver ingin membatalkan")
            .setMessage("Driver meminta pembatalan pesanan. Setujui pembatalan?")
            .setPositiveButton("Setuju") { _, _ ->
                cancellationDialogShowing = false
                viewModel.approveCancel()
            }
            .setNegativeButton("Tolak") { _, _ ->
                cancellationDialogShowing = false
                viewModel.rejectCancel()
            }
            .setOnCancelListener {
                cancellationDialogShowing = false
                viewModel.rejectCancel()
            }
            .show()
    }

    /** true kalau order ini SuruhSend → aksen hijau; SuruhRide → teal. */
    private fun isSend(): Boolean = viewModel.serviceType.value == "send"

    private fun applyServiceAccent() {
        val send = isSend()
        binding.btnCallDriver.setBackgroundResource(
            if (send) R.drawable.bg_send_primary_green else R.drawable.bg_location_primary_teal
        )
        binding.tvPrice.setBackgroundResource(
            if (send) R.drawable.bg_send_stat_green else R.drawable.bg_activity_stat_teal
        )
        // Tombol bayar mengikuti aksen layanan (Ride teal, Send hijau).
        binding.btnPayNow.setBackgroundResource(
            if (send) R.drawable.bg_send_primary_green else R.drawable.bg_location_primary_teal
        )
    }

    private fun updateStepper(status: String) {
        val activeColor = android.graphics.Color.parseColor(if (isSend()) "#3EC76E" else "#2AB2A6")
        val inactiveColor = android.graphics.Color.parseColor("#D8DEF5")
        val activeText = ContextCompat.getColor(requireContext(), R.color.brand_ink)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.text_muted)

        // Using findViewByID if binding.stepper is causing issues with include tags
        val step1Dot = binding.root.findViewById<View>(R.id.step_1_dot)
        val step1Label = binding.root.findViewById<TextView>(R.id.step_1_label)
        val line1 = binding.root.findViewById<View>(R.id.line_1)
        val step2Dot = binding.root.findViewById<View>(R.id.step_2_dot)
        val step2Label = binding.root.findViewById<TextView>(R.id.step_2_label)
        val line2 = binding.root.findViewById<View>(R.id.line_2)
        val step3Dot = binding.root.findViewById<View>(R.id.step_3_dot)
        val step3Label = binding.root.findViewById<TextView>(R.id.step_3_label)

        when (status) {
            "pending" -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step1Label.setTextColor(activeText)
                line1.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step2Label.setTextColor(inactiveText)
                line2.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Label.setTextColor(inactiveText)
            }
            "accepted" -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step1Label.setTextColor(inactiveText)
                line1.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Label.setTextColor(activeText)
                line2.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Label.setTextColor(inactiveText)
            }
            "pickup", "delivering" -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                line1.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Label.setTextColor(inactiveText)
                line2.backgroundTintList = ColorStateList.valueOf(activeColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step3Label.setTextColor(activeText)
            }
            "completed" -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                line1.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                line2.backgroundTintList = ColorStateList.valueOf(activeColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step3Label.setTextColor(activeText)
            }
        }
    }

    private fun showPaymentPrompt() {
        val fare = viewModel.fareAmount.value ?: 0L
        val balance = viewModel.balance.value ?: 0L
        
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.dialog_payment, null)
        dialog.setContentView(dialogView)

        val tvTotal = dialogView.findViewById<android.widget.TextView>(R.id.tv_payment_total)
        val tvBalance = dialogView.findViewById<android.widget.TextView>(R.id.tv_payment_balance)
        val tvHint = dialogView.findViewById<android.widget.TextView>(R.id.tv_payment_hint)
        val tvVoucher = dialogView.findViewById<android.widget.TextView>(R.id.tv_payment_voucher)
        val btnSaldo = dialogView.findViewById<android.widget.TextView>(R.id.btn_pay_saldo)
        val btnQris = dialogView.findViewById<android.widget.TextView>(R.id.btn_pay_qris)

        if (isSend()) btnSaldo.setBackgroundResource(R.drawable.bg_send_primary_green)

        // Harga yang ditagih = setelah potongan voucher (sama dengan server).
        tvTotal.text = "Total: ${OrderMoneyPolicy.rupiah(fare)}"
        val money = viewModel.tripMoney
        tvVoucher.text = OrderMoneyPolicy.discountNote(money.original, money.discount)
        tvVoucher.visibility = if (money.hasDiscount) View.VISIBLE else View.GONE
        if (balance >= fare) {
            tvBalance.text = "Saldo kamu: Rp %,d (cukup)".format(balance)
            tvHint.text = "Pilih metode pembayaran:"
            btnSaldo.isEnabled = true
            btnSaldo.alpha = 1f
            btnSaldo.text = "Bayar pakai Saldo"
        } else {
            tvBalance.text = "Saldo kamu: Rp %,d (kurang)".format(balance)
            tvHint.text = "Saldo tidak cukup — bayar via QRIS."
            btnSaldo.isEnabled = false
            btnSaldo.alpha = 0.4f
            btnSaldo.text = "Saldo kurang (Top up dulu)"
        }

        btnSaldo.setOnClickListener {
            dialog.dismiss()
            viewModel.payFromBalance()
        }
        btnQris.setOnClickListener {
            dialog.dismiss()
            viewModel.requestQrisPayment()
        }

        dialog.show()
    }

    private fun showRatingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rating, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.CustomDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val tvDriverName = dialogView.findViewById<android.widget.TextView>(R.id.tv_rating_driver_name)
        val ratingBar = dialogView.findViewById<android.widget.RatingBar>(R.id.rating_bar_dialog)
        val btnSubmit = dialogView.findViewById<android.widget.TextView>(R.id.btn_submit_rating)
        val btnSkip = dialogView.findViewById<android.widget.TextView>(R.id.btn_skip_rating)

        if (isSend()) btnSubmit.setBackgroundResource(R.drawable.bg_send_primary_green)

        tvDriverName.text = viewModel.driverName.value ?: "Driver Suruhaja"

        // Ensure minimum rating is 1
        ratingBar.setOnRatingBarChangeListener { rb, rating, fromUser ->
            if (fromUser && rating < 1f) {
                rb.rating = 1f
            }
        }

        btnSubmit.setOnClickListener {
            viewModel.submitRating(ratingBar.rating.toInt())
            dialog.dismiss()
            Toast.makeText(requireContext(), "Terima kasih atas ratingnya!", Toast.LENGTH_SHORT).show()
        }

        btnSkip.setOnClickListener {
            // Default to 5 stars as requested
            viewModel.submitRating(5)
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshFromServer()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Apply theme-based map style
        ThemeManager.applyMapStyle(requireContext(), map)

        val showPickup = viewModel.status.value != "delivering"
        viewModel.pickupLatLng.value?.let { (lat, lng) ->
            if (lat != 0.0 && showPickup) {
                map.addMarker(MarkerOptions().position(LatLng(lat, lng)).title("Penjemputan")
                    .icon(MapPinIcon.fromDrawable(
                        requireContext(),
                        if (isSend()) R.drawable.ic_map_pin_web_green else R.drawable.ic_map_pin_web_brand
                    )))
            }
        }
        if (!showPickup) {
            // Driver picked up the customer → center on destination instead of pickup
            viewModel.destLatLng.value?.let { (lat, lng) ->
                if (lat != 0.0) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15f))
            }
        }

        // Destination marker + phase-aware route
        drawDestinationAndRoute()

        // Show driver marker if position already loaded before map was ready
        viewModel.driverLatLng.value?.let { (lat, lng) ->
            if (lat != 0.0 && lng != 0.0) updateDriverMarker(lat, lng)
        }
    }

    /**
     * Draw destination marker + route, phase-aware:
     * - "accepted" (driver heading to customer) → route driver → pickup
     * - "pickup"  (heading to destination)       → route driver → destination
     */
    private fun drawDestinationAndRoute() {
        val map = googleMap ?: return
        val status = viewModel.status.value ?: return

        // Destination marker (red) — always show if available
        viewModel.destLatLng.value?.let { (destLat, destLng) ->
            if (destLat != 0.0) {
                map.addMarker(MarkerOptions().position(LatLng(destLat, destLng)).title("Tujuan")
                    .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_coral)))
            }
        }

        val pickup = viewModel.pickupLatLng.value
        val dest = viewModel.destLatLng.value
        val driver = viewModel.driverLatLng.value

        val origin: LatLng
        val target: LatLng
        val routeColor: String
        when (status) {
            "pending" -> {
                // Customer waiting for driver → preview route pickup → destination (red)
                if (pickup == null || pickup.first == 0.0 || dest == null || dest.first == 0.0) return
                origin = LatLng(pickup.first, pickup.second)
                target = LatLng(dest.first, dest.second)
                routeColor = "#EA4335"
            }
            "accepted" -> {
                // Driver heading to customer → route driver → pickup (blue)
                if (driver == null || driver.first == 0.0 || pickup == null || pickup.first == 0.0) return
                origin = LatLng(driver.first, driver.second)
                target = LatLng(pickup.first, pickup.second)
                routeColor = "#4285F4"
            }
            "pickup" -> {
                // Driver sudah di titik penjemputan → preview rute pickup → destination (red)
                if (pickup == null || pickup.first == 0.0 || dest == null || dest.first == 0.0) return
                origin = LatLng(pickup.first, pickup.second)
                target = LatLng(dest.first, dest.second)
                routeColor = "#EA4335"
            }
            "delivering" -> {
                // Heading to destination → route driver → destination (red; fallback pickup → destination)
                if (dest == null || dest.first == 0.0) return
                if (driver != null && driver.first != 0.0) {
                    origin = LatLng(driver.first, driver.second)
                } else if (pickup != null && pickup.first != 0.0) {
                    origin = LatLng(pickup.first, pickup.second)
                } else return
                target = LatLng(dest.first, dest.second)
                routeColor = "#EA4335"
            }
            else -> return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val points = MapRouteHelper.fetchRoutePoints(requireContext(), origin, target)
            if (points != null && isAdded) {
                routePolyline?.remove()
                routePolyline = MapRouteHelper.drawRoute(map, points, routeColor)
            }
        }
    }

    private fun updateDriverMarker(lat: Double, lng: Double) {
        val map = googleMap ?: return
        map.clear()
        routePolyline = null

        val showPickup = viewModel.status.value != "delivering"
        if (showPickup) {
            viewModel.pickupLatLng.value?.let { (pLat, pLng) ->
                if (pLat != 0.0) {
                    map.addMarker(MarkerOptions().position(LatLng(pLat, pLng)).title("Penjemputan")
                        .icon(MapPinIcon.fromDrawable(
                        requireContext(),
                        if (isSend()) R.drawable.ic_map_pin_web_green else R.drawable.ic_map_pin_web_brand
                    )))
                }
            }
        }
        // Redraw destination + phase-aware route after clear
        drawDestinationAndRoute()

        val driverPos = LatLng(lat, lng)
        val bearing = if (lastDriverLat != 0.0 && lastDriverLng != 0.0) {
            val from = Location("").apply { latitude = lastDriverLat; longitude = lastDriverLng }
            val to = Location("").apply { latitude = lat; longitude = lng }
            from.bearingTo(to)
        } else 0f
        lastDriverLat = lat
        lastDriverLng = lng
        map.addMarker(MarkerOptions().position(driverPos).title("Driver")
            .anchor(0.5f, 1.0f)
            .rotation(bearing)
            .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_driver_marker)))

        // Fit bounds to driver (+ pickup if shown, + destination if available)
        val bounds = com.google.android.gms.maps.model.LatLngBounds.builder().include(driverPos)
        if (showPickup) {
            viewModel.pickupLatLng.value?.let { (pLat, pLng) ->
                if (pLat != 0.0) bounds.include(LatLng(pLat, pLng))
            }
        }
        viewModel.destLatLng.value?.let { (dLat, dLng) ->
            if (dLat != 0.0) bounds.include(LatLng(dLat, dLng))
        }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
    }

    private fun updateButtonStyle(status: String) {
        when (status) {
            "completed" -> {
                binding.btnCancelOrder.text = "Selesaikan Pesanan"
                binding.btnCancelOrder.setBackgroundResource(
                    if (isSend()) R.drawable.bg_send_primary_green else R.drawable.bg_location_primary_teal
                )
                binding.btnCancelOrder.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
            }
            "cancelled" -> {
                binding.btnCancelOrder.text = "Kembali ke Beranda"
                binding.btnCancelOrder.setBackgroundResource(R.drawable.bg_location_primary_coral)
                binding.btnCancelOrder.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
            }
            else -> {
                // pending, accepted, pickup
                val cancelReq = viewModel.cancelRequestedBy.value
                if (cancelReq == "customer") {
                    binding.btnCancelOrder.text = "Menunggu persetujuan driver..."
                } else {
                    binding.btnCancelOrder.text = "Batalkan Pesanan"
                }
                binding.btnCancelOrder.setBackgroundResource(R.drawable.bg_location_primary_coral)
                binding.btnCancelOrder.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
            }
        }
    }

    private fun statusText(s: String): String = when (s) {
        "pending" -> "Mencari Driver..."
        "accepted" -> "Driver Menuju Penjemputan"
        "pickup" -> "Driver Sudah di Titik Penjemputan"
        "delivering" -> "Menuju Tujuan"
        "completed" -> "✅ Pesanan Selesai"
        else -> s  // cancelled handled by cancelledBy observer
    }

    private fun openWhatsApp(phone: String) {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        val wa = when {
            clean.startsWith("0") -> "62" + clean.substring(1)
            clean.startsWith("62") -> clean
            clean.startsWith("+62") -> clean.substring(1)
            else -> clean
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$wa")))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "WhatsApp tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        reconnectSnackbar?.dismiss()
        reconnectSnackbar = null
        super.onDestroyView()
        _binding = null
    }
}
