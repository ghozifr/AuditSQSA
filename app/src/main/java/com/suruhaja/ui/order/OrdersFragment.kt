package com.suruhaja.ui.order

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.suruhaja.R
import com.suruhaja.databinding.FragmentOrdersBinding
import com.suruhaja.data.policy.OrderMoneyPolicy
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.Locale

@AndroidEntryPoint
class OrdersFragment : Fragment() {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OrdersViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Title section already clears the status bar via its own paddingTop (56dp).
            // NOTE: do not re-add status bar margin here — it previously targeted
            // getChildAt(0) of the stats row, which is the "Perjalanan" card, pushing
            // it down ~24dp and breaking the 3-card alignment.

            binding.root.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        binding.btnRefreshHistory.setOnClickListener { viewModel.refresh() }
        binding.btnLoadMoreHistory.setOnClickListener { viewModel.loadMore() }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.btnRefreshHistory.isEnabled = !loading
            binding.btnRefreshHistory.alpha = if (loading) 0.55f else 1f
            if (loading && binding.tvHistoryLoading.visibility != View.VISIBLE) {
                binding.tvHistoryLoading.text = "Memuat riwayat terbaru..."
                binding.tvHistoryLoading.visibility = View.VISIBLE
            }
        }

        viewModel.loadingMore.observe(viewLifecycleOwner) { loading ->
            binding.btnLoadMoreHistory.isEnabled = !loading
            binding.btnLoadMoreHistory.text = if (loading) "Memuat..." else "Muat riwayat sebelumnya"
        }

        viewModel.hasMoreHistory.observe(viewLifecycleOwner) { hasMore ->
            binding.btnLoadMoreHistory.visibility = if (hasMore) View.VISIBLE else View.GONE
        }

        viewModel.historyMessage.observe(viewLifecycleOwner) { message ->
            binding.tvHistoryLoading.apply {
                text = message.orEmpty()
                visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }

        viewModel.stats.observe(viewLifecycleOwner) { (trips, orders, points) ->
            binding.tvStatTrips.text = trips.toString()
            binding.tvStatOrders.text = orders.toString()
            binding.tvStatPoints.text = "%,d".format(points)
        }

        viewModel.activeOrders.observe(viewLifecycleOwner) { active ->
            renderActiveOrders(active)
        }

        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            if (orders.isEmpty()) {
                binding.layoutOrderList.visibility = View.GONE
                binding.layoutEmpty.visibility = View.VISIBLE
            } else {
                binding.layoutOrderList.visibility = View.VISIBLE
                binding.layoutEmpty.visibility = View.GONE
                renderOrders(orders)
            }
        }

        viewModel.cancelMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderActiveOrders(orders: List<com.suruhaja.data.model.Order>) {
        if (orders.isEmpty()) {
            binding.layoutActiveSection.visibility = View.GONE
            return
        }
        binding.layoutActiveSection.visibility = View.VISIBLE
        binding.layoutActiveList.removeAllViews()
        val inflater = layoutInflater
        for (order in orders) {
            val item = inflater.inflate(R.layout.item_active_order, binding.layoutActiveList, false)
            val isFood = order.serviceType.equals("food", ignoreCase = true)
            val emoji = if (isFood) "🍔" else if (order.serviceType.equals("send", ignoreCase = true)) "📦" else "🚗"
            val dest = order.destination.ifEmpty { order.pickup.ifEmpty { "Perjalanan" } }
            item.findViewById<TextView>(R.id.tv_active_destination).text = "$emoji $dest"
            item.findViewById<TextView>(R.id.tv_active_status).text = activeStatusText(order.status, isFood)

            // Cancel hanya sebelum driver ditugaskan: pending/seeking_driver langsung; setelah itu mutual.
            val canCancel = isFood && (order.status == "pending" || order.status == "seeking_driver" || order.status == "accepted")
            val btnCancel = item.findViewById<TextView>(R.id.btn_cancel_active)
            btnCancel.visibility = if (canCancel) View.VISIBLE else View.GONE
            if (canCancel) {
                btnCancel.setOnClickListener { confirmCancelFood(order) }
            }

            item.setOnClickListener {
                val bundle = Bundle().apply { putString("orderId", order.id) }
                if (isFood) findNavController().navigate(R.id.foodTrackingFragment, bundle)
                else findNavController().navigate(R.id.orderTrackingFragment, bundle)
            }
            binding.layoutActiveList.addView(item)
        }
    }

    private fun confirmCancelFood(order: com.suruhaja.data.model.Order) {
        val isDirect = order.status == "pending" || order.status == "seeking_driver"
        val msg = if (isDirect) "Batalkan pesanan ${order.destination.ifEmpty { "ini" }}?"
        else "Batalkan pesanan ${order.destination.ifEmpty { "ini" }}? Permintaan akan dikirim ke driver."
        AlertDialog.Builder(requireContext())
            .setTitle("Batalkan Pesanan")
            .setMessage(msg)
            .setPositiveButton("Ya, Batalkan") { _, _ ->
                if (isDirect) viewModel.cancelActiveFoodOrder(order.id)
                else viewModel.requestCancelActiveFoodOrder(order.id)
            }
            .setNegativeButton("Tidak", null)
            .show()
    }

    private fun activeStatusText(status: String, isFood: Boolean): String = when {
        isFood -> when (status) {
            "pending" -> "Mencari driver..." // kompatibilitas order lama
            "seeking_driver" -> "Mencari driver..."
            "accepted" -> "Driver ditemukan, menunggu konfirmasi toko"
            "preparing" -> "Toko menyiapkan pesanan"
            "ready" -> "Pesanan siap diambil"
            "delivering" -> "Driver mengantar pesanan"
            else -> status
        }
        else -> when (status) {
            "pending" -> "Mencari driver..."
            "accepted" -> "Driver menuju penjemputan"
            "pickup" -> "Menuju tujuan"
            "delivering" -> "Driver mengantar"
            else -> status
        }
    }

    private fun renderOrders(orders: List<com.suruhaja.data.model.Order>) {
        binding.layoutOrderList.removeAllViews()
        val inflater = layoutInflater
        for (order in orders) {
            val item = inflater.inflate(R.layout.item_order_history, binding.layoutOrderList, false)
            val isFood = order.serviceType.equals("food", ignoreCase = true)
            val isSend = order.serviceType.equals("send", ignoreCase = true)
            val dest = order.destination.ifEmpty { order.pickup.ifEmpty { "Perjalanan" } }

            item.findViewById<TextView>(R.id.tv_item_destination).text = dest
            item.findViewById<ImageView>(R.id.iv_item_service).setImageResource(
                when {
                    isFood -> R.drawable.ic_asset_food_2
                    isSend -> R.drawable.ic_asset_send
                    else -> R.drawable.ic_asset_drive_2
                }
            )
            // Harga yang tampil = yang benar-benar dibayar (setelah potongan
            // voucher), bukan harga kotor.
            item.findViewById<TextView>(R.id.tv_item_price).text =
                NumberFormat.getCurrencyInstance(Locale("id", "ID"))
                    .format(OrderMoneyPolicy.trip(order.price, order.voucherDiscount).payable)

            val cancelled = order.status.equals("cancelled", ignoreCase = true)
            item.findViewById<TextView>(R.id.tv_item_status).apply {
                text = if (cancelled) "Dibatalkan" else "Selesai"
                setBackgroundResource(
                    if (cancelled) R.drawable.bg_activity_cancelled_chip
                    else R.drawable.bg_activity_completed_chip
                )
            }
            binding.layoutOrderList.addView(item)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
