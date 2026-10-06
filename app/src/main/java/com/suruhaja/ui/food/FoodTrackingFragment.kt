package com.suruhaja.ui.food

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.suruhaja.R
import com.suruhaja.data.model.FoodItem
import com.suruhaja.data.model.FoodOrder
import com.suruhaja.data.model.ReviewedMenuItem
import com.suruhaja.data.policy.OrderMoneyPolicy
import com.suruhaja.realtime.RealtimeStatePolicy
import com.suruhaja.databinding.FragmentFoodTrackingBinding
import com.bumptech.glide.Glide
import com.suruhaja.ui.chat.ChatSheetFragment
import com.suruhaja.util.MapRouteHelper
import com.suruhaja.util.MapPinIcon
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FoodTrackingFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentFoodTrackingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FoodTrackingViewModel by viewModels()
    private var paymentPromptShown = false
    private var navigatedAfterRating = false
    private var chatBadge: BadgeDrawable? = null
    private var reconnectSnackbar: Snackbar? = null

    private var googleMap: GoogleMap? = null
    private var merchantMarker: Marker? = null
    private var customerMarker: Marker? = null
    private var driverMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var routeOrigin: LatLng? = null
    private var routePhase: String? = null
    private var boundsFitted = false
    private var lastDriverLat = 0.0
    private var lastDriverLng = 0.0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFoodTrackingBinding.inflate(inflater, container, false)
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
            binding.statusCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBars.bottom
            }
            insets
        }

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        // Pembayaran lewat dialog 2-jalur (Saldo / QRIS). Tombol ini adalah
        // jalan masuk MANUAL ke dialog itu: ia tampil selama driver sudah minta
        // bayar dan order belum lunas, jadi customer tidak stuck ketika dialog
        // pembayaran tertutup (tombol back / swipe).
        binding.btnPaySaldo.setOnClickListener { showPaymentPrompt() }
        binding.btnFinish.setOnClickListener { showRatingDialog() }

        binding.btnCancelOrder.setOnClickListener {
            val o = viewModel.order.value ?: return@setOnClickListener
            if (o.status == FoodOrder.STATUS_PENDING || o.status == FoodOrder.STATUS_SEEKING_DRIVER) {
                confirmCancel { viewModel.cancelDirectly() }
            } else {
                confirmCancel { viewModel.requestCancel() }
            }
        }

        binding.btnChatDriver.setOnClickListener {
            val order = viewModel.order.value ?: return@setOnClickListener
            if (order.driverId.isEmpty()) {
                Toast.makeText(requireContext(), "Driver belum tersedia", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ChatSheetFragment.newInstance("merchant_orders", order.id, "customer", order.driverName.ifEmpty { "Driver" })
                .show(childFragmentManager, "chat")
        }
        binding.btnCallDriver.setOnClickListener {
            val order = viewModel.order.value ?: return@setOnClickListener
            val phone = order.driverPhone
            if (phone.isEmpty()) {
                Toast.makeText(requireContext(), "Nomor driver belum tersedia", Toast.LENGTH_SHORT).show()
            } else {
                openWhatsApp(phone)
            }
        }

        viewModel.driverInfo.observe(viewLifecycleOwner) { info ->
            if (info.photoUrl.isEmpty()) {
                binding.ivDriverPhoto.visibility = View.GONE
            } else {
                binding.ivDriverPhoto.visibility = View.VISIBLE
                Glide.with(binding.root).load(info.photoUrl).circleCrop().into(binding.ivDriverPhoto)
            }
            binding.tvDriverVehicle.text = listOf(info.vehicle, info.plate)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            binding.tvDriverVehicle.visibility =
                if (binding.tvDriverVehicle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val mapFragment = childFragmentManager.findFragmentById(R.id.map_view) as SupportMapFragment
        mapFragment.getMapAsync(this)

        viewModel.paymentMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
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

        viewModel.cancelMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.cancelRequestedBy.observe(viewLifecycleOwner) { by ->
            binding.btnCancelOrder.isEnabled = by != "customer"
            binding.btnCancelOrder.text = if (by == "customer") "Menunggu persetujuan driver..." else "Batalkan Pesanan"
        }

        viewModel.unreadCount.observe(viewLifecycleOwner) { count -> updateChatBadge(count) }

        viewModel.paymentPrompt.observe(viewLifecycleOwner) { show ->
            // Dialog otomatis hanya sekali per permintaan driver; tombol tetap ada.
            val decision = RealtimeStatePolicy.paymentPromptDecision(show, paymentPromptShown)
            paymentPromptShown = decision.markPrompted
            binding.btnPaySaldo.visibility = if (decision.buttonVisible) View.VISIBLE else View.GONE
            if (decision.autoShow) showPaymentPrompt()
        }

        viewModel.ratingError.observe(viewLifecycleOwner) { err ->
            if (!err.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Gagal menyimpan rating: $err", Toast.LENGTH_LONG).show()
            }
        }

        viewModel.ratingSubmitted.observe(viewLifecycleOwner) { success ->
            if (success == true && !navigatedAfterRating) {
                navigatedAfterRating = true
                Toast.makeText(requireContext(), "Terima kasih atas ratingnya!", Toast.LENGTH_SHORT).show()
                // Kembali ke dashboard (home), buang tumpukan flow food.
                findNavController().navigate(
                    R.id.homeFragment,
                    null,
                    androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.homeFragment, true)
                        .build()
                )
            }
        }

        viewModel.order.observe(viewLifecycleOwner) { order ->
            if (order == null) {
                binding.tvStatus.text = "Memuat..."
                return@observe
            }
            binding.tvStatus.text = if (order.status == FoodOrder.STATUS_CANCELLED && order.cancelledBy == "merchant")
                "Dibatalkan · ${order.cancelReason.ifEmpty { "Toko tutup" }}"
            else FoodOrder.statusText(order.status)
            updateStepper(order.status)
            binding.tvMerchant.text = order.merchantName.ifEmpty { "Toko" }
            binding.tvDriver.text = order.driverName.ifEmpty { "Mencari driver..." }

            // Cancel hanya sebelum driver ditugaskan: pending/seeking_driver langsung; accepted mutual.
            val cancellable = order.status == FoodOrder.STATUS_PENDING ||
                order.status == FoodOrder.STATUS_SEEKING_DRIVER ||
                order.status == FoodOrder.STATUS_ACCEPTED
            binding.btnCancelOrder.visibility = if (cancellable) View.VISIBLE else View.GONE

            // Total yang ditampilkan = yang ditagih server (setelah potongan
            // voucher). Order lama tanpa field voucher tetap memakai total lama.
            val money = OrderMoneyPolicy.food(
                total = order.total,
                deliveryFee = order.deliveryFee,
                adminFee = order.adminFee,
                voucherDiscount = order.voucherDiscount
            )
            binding.tvTotal.text = OrderMoneyPolicy.rupiah(money.payable)
            binding.tvDeliveryFee.text = buildString {
                append("Ongkir: ${OrderMoneyPolicy.rupiah(order.deliveryFee)}")
                append(" · Biaya layanan: ${OrderMoneyPolicy.rupiah(order.adminFee)}")
                append(" · %.1f km".format(order.distanceKm))
                if (money.hasDiscount) append(" · voucher −${OrderMoneyPolicy.rupiah(money.discount)}")
            }

            renderItems(order.items)
            updateMap(order)

            // Status pembayaran
            val isPaid = order.paymentStatus == "paid"
            val qrisWaiting = order.paymentRequest == "qris" && !isPaid
            binding.tvPaymentStatus.visibility = if (isPaid || qrisWaiting) View.VISIBLE else View.GONE
            binding.tvPaymentStatus.text = when {
                isPaid -> "✅ Lunas"
                qrisWaiting -> "⏳ Menunggu pembayaran QRIS..."
                else -> ""
            }

            // Tombol selesai + rating saat order sudah delivered
            val delivered = order.status == FoodOrder.STATUS_DELIVERED
            binding.btnFinish.visibility = if (delivered && !order.rated) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshFromServer()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        viewModel.order.value?.let { updateMap(it) }
    }

    /** Render 3 titik: toko (merah), alamat antar (biru), driver live (hijau) + rute. */
    private fun updateMap(o: FoodOrder) {
        val map = googleMap ?: return
        val merchant = if (o.merchantLat != 0.0 || o.merchantLng != 0.0)
            LatLng(o.merchantLat, o.merchantLng) else null
        val customer = if (o.customerLat != 0.0 || o.customerLng != 0.0)
            LatLng(o.customerLat, o.customerLng) else null
        val driver = if (o.driverLat != 0.0 || o.driverLng != 0.0)
            LatLng(o.driverLat, o.driverLng) else null

        if (merchantMarker == null && merchant != null) {
            merchantMarker = map.addMarker(
                MarkerOptions().position(merchant).title("Toko: ${o.merchantName}")
                    .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_coral))
            )
        }
        if (customerMarker == null && customer != null) {
            customerMarker = map.addMarker(
                MarkerOptions().position(customer).title("Alamat antar")
                    .icon(MapPinIcon.fromDrawable(requireContext(), R.drawable.ic_map_pin_web_brand))
            )
        }
        val driverWasShown = driverMarker != null
        if (driver != null) {
            val bearing = if (lastDriverLat != 0.0 && lastDriverLng != 0.0) {
                val from = Location("").apply { latitude = lastDriverLat; longitude = lastDriverLng }
                val to = Location("").apply { latitude = driver.latitude; longitude = driver.longitude }
                from.bearingTo(to)
            } else 0f
            lastDriverLat = driver.latitude
            lastDriverLng = driver.longitude
            if (driverMarker == null) {
                driverMarker = map.addMarker(
                    MarkerOptions().position(driver).title("Driver: ${o.driverName}")
                        .anchor(0.5f, 1.0f)
                        .rotation(bearing)
                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_driver_marker))
                )
            } else {
                driverMarker!!.position = driver
                driverMarker!!.rotation = bearing
            }
        }

        // Rute jalan asli (Directions API), fase-aware:
        //  - delivering: driver → alamat antar (merah)
        //  - menuju toko: driver → toko (biru)
        //  - belum ada posisi driver: preview toko → alamat antar (oranye)
        val delivering = o.status == FoodOrder.STATUS_DELIVERING
        var origin: LatLng? = null
        var target: LatLng? = null
        var colorHex = "#F5A623"
        var phaseKey = "preview"
        when {
            delivering && driver != null && customer != null -> {
                origin = driver; target = customer; colorHex = "#E86E20"; phaseKey = "deliver"
            }
            driver != null && merchant != null -> {
                origin = driver; target = merchant; colorHex = "#E86E20"; phaseKey = "pickup"
            }
            merchant != null && customer != null -> {
                origin = merchant; target = customer; colorHex = "#FB923C"; phaseKey = "preview"
            }
        }
        if (origin != null && target != null) {
            val moved = routeOrigin == null || distanceMeters(
                routeOrigin!!.latitude, routeOrigin!!.longitude, origin.latitude, origin.longitude
            ) >= 25.0
            if (moved || routePhase != phaseKey) {
                routeOrigin = origin
                routePhase = phaseKey
                fetchAndDrawRoute(origin, target, colorHex)
            }
        }

        // Fit kamera sekali di awal, dan sekali lagi saat driver pertama kali muncul
        if (!boundsFitted || (driver != null && !driverWasShown)) {
            val builder = LatLngBounds.Builder()
            merchant?.let { builder.include(it) }
            customer?.let { builder.include(it) }
            driver?.let { builder.include(it) }
            if (merchant != null || customer != null || driver != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 150))
                boundsFitted = true
            }
        }
    }

    /** Ambil rute jalan asli dari Directions API lalu gambar di peta. */
    private fun fetchAndDrawRoute(origin: LatLng, dest: LatLng, colorHex: String) {
        val map = googleMap ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val points = MapRouteHelper.fetchRoutePoints(requireContext(), origin, dest)
            if (points != null && isAdded && googleMap == map) {
                routePolyline?.remove()
                routePolyline = MapRouteHelper.drawRoute(map, points, colorHex)
            }
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat1 == 0.0 && lng1 == 0.0) return Double.MAX_VALUE
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
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

    private fun confirmCancel(action: () -> Unit) {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.dialog_food_confirm, null)
        dialog.setContentView(dialogView)
        dialogView.findViewById<TextView>(R.id.btn_confirm_yes).setOnClickListener {
            dialog.dismiss()
            action()
        }
        dialogView.findViewById<TextView>(R.id.btn_confirm_no).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRatingDialog() {
        val order = viewModel.order.value ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_rating_food, null)
        val dialog = AlertDialog.Builder(requireContext(), R.style.CustomDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val rbMerchant = dialogView.findViewById<RatingBar>(R.id.rb_merchant)
        val rbDriver = dialogView.findViewById<RatingBar>(R.id.rb_driver)
        val etMerchant = dialogView.findViewById<EditText>(R.id.et_merchant_comment)
        val etDriver = dialogView.findViewById<EditText>(R.id.et_driver_comment)
        val menuContainer = dialogView.findViewById<LinearLayout>(R.id.menu_rating_container)
        val btnSubmit = dialogView.findViewById<TextView>(R.id.btn_submit)
        val btnSkip = dialogView.findViewById<TextView>(R.id.btn_skip)

        dialogView.findViewById<TextView>(R.id.tv_rating_merchant_name).text =
            order.merchantName.ifEmpty { "Toko" }
        dialogView.findViewById<TextView>(R.id.tv_rating_driver_name).text =
            order.driverName.ifEmpty { "Driver" }

        // Baris rating per menu item (default 5)
        val itemBars = mutableListOf<Pair<String, RatingBar>>()
        for (item in order.items) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 0)
            }
            val name = TextView(requireContext()).apply {
                text = "${item.qty}× ${item.name}"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
                typeface = resources.getFont(R.font.poppins_bold)
            }
            val rb = RatingBar(requireContext(), null, android.R.attr.ratingBarStyle).apply {
                numStars = 5
                rating = 5f
                stepSize = 1f
                progressTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.amber))
                progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_muted))
            }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(rb)
            menuContainer.addView(row)
            itemBars.add(item.menuItemId to rb)
        }

        val submit: () -> Unit = {
            // Nama menu ikut disimpan sebagai snapshot supaya daftar pesanan di
            // layar ulasan tetap tampil walau menu itu kemudian dihapus toko.
            val menuNames = order.items.associate { it.menuItemId to it.name }
            val reviewedItems = itemBars.map { (menuId, rb) ->
                ReviewedMenuItem(
                    menuItemId = menuId,
                    name = menuNames[menuId].orEmpty(),
                    rating = rb.rating.toInt().coerceIn(1, 5)
                )
            }
            viewModel.submitReview(
                merchantRating = rbMerchant.rating.toInt().coerceIn(1, 5),
                driverRating = rbDriver.rating.toInt().coerceIn(1, 5),
                merchantComment = etMerchant.text.toString().trim(),
                driverComment = etDriver.text.toString().trim(),
                reviewedItems = reviewedItems
            )
            dialog.dismiss()
        }
        btnSubmit.setOnClickListener { submit() }
        btnSkip.setOnClickListener {
            rbMerchant.rating = 5f
            rbDriver.rating = 5f
            itemBars.forEach { it.second.rating = 5f }
            submit()
        }

        dialog.show()
    }

    private fun updateStepper(status: String) {
        val activeColor = Color.parseColor("#E86E20")
        val inactiveColor = Color.parseColor("#D8DEF5")
        val activeText = ContextCompat.getColor(requireContext(), R.color.brand_ink)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.text_muted)

        val step1Dot = binding.root.findViewById<View>(R.id.step_1_dot)
        val step1Label = binding.root.findViewById<TextView>(R.id.step_1_label)
        val line1 = binding.root.findViewById<View>(R.id.line_1)
        val step2Dot = binding.root.findViewById<View>(R.id.step_2_dot)
        val step2Label = binding.root.findViewById<TextView>(R.id.step_2_label)
        val line2 = binding.root.findViewById<View>(R.id.line_2)
        val step3Dot = binding.root.findViewById<View>(R.id.step_3_dot)
        val step3Label = binding.root.findViewById<TextView>(R.id.step_3_label)

        step1Label.text = "Mencari"
        step2Label.text = "Menyiapkan"
        step3Label.text = "Mengantar"

        when (status) {
            FoodOrder.STATUS_PENDING, FoodOrder.STATUS_SEEKING_DRIVER, FoodOrder.STATUS_ACCEPTED -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step1Label.setTextColor(activeText)
                line1.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step2Label.setTextColor(inactiveText)
                line2.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Label.setTextColor(inactiveText)
            }
            FoodOrder.STATUS_PREPARING, FoodOrder.STATUS_READY -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                line1.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Label.setTextColor(activeText)
                line2.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(inactiveColor)
                step3Label.setTextColor(inactiveText)
            }
            FoodOrder.STATUS_DELIVERING -> {
                step1Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                line1.backgroundTintList = ColorStateList.valueOf(activeColor)
                step2Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                line2.backgroundTintList = ColorStateList.valueOf(activeColor)
                step3Dot.backgroundTintList = ColorStateList.valueOf(activeColor)
                step3Label.setTextColor(activeText)
            }
            FoodOrder.STATUS_DELIVERED -> {
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
        val order = viewModel.order.value ?: return
        val money = OrderMoneyPolicy.food(
            total = order.total,
            deliveryFee = order.deliveryFee,
            adminFee = order.adminFee,
            voucherDiscount = order.voucherDiscount
        )
        val fare = money.payable
        val balance = viewModel.balance.value ?: 0L
        
        val dialog = BottomSheetDialog(requireContext(), R.style.CustomDialog)
        val dialogView = layoutInflater.inflate(R.layout.dialog_payment, null)
        dialog.setContentView(dialogView)

        val tvTotal = dialogView.findViewById<TextView>(R.id.tv_payment_total)
        val tvBalance = dialogView.findViewById<TextView>(R.id.tv_payment_balance)
        val tvHint = dialogView.findViewById<TextView>(R.id.tv_payment_hint)
        val tvVoucher = dialogView.findViewById<TextView>(R.id.tv_payment_voucher)
        val btnSaldo = dialogView.findViewById<TextView>(R.id.btn_pay_saldo)
        val btnQris = dialogView.findViewById<TextView>(R.id.btn_pay_qris)

        tvTotal.text = "Total: ${OrderMoneyPolicy.rupiah(fare)}"
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

    private fun renderItems(items: List<FoodItem>) {
        binding.itemsList.removeAllViews()
        for (item in items) {
            val row = TextView(requireContext()).apply {
                text = "${item.qty}× ${item.name}"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
                typeface = resources.getFont(R.font.poppins_bold)
                textSize = 14f
            }
            binding.itemsList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
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
        merchantMarker = null
        customerMarker = null
        driverMarker = null
        routePolyline = null
        routeOrigin = null
        routePhase = null
        googleMap = null
        boundsFitted = false
        _binding = null
    }
}
