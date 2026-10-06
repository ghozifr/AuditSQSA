package com.suruhaja.ui.food

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.suruhaja.R
import com.suruhaja.data.model.MenuItem
import com.suruhaja.data.model.Merchant
import com.suruhaja.databinding.ItemMerchantHeaderBinding
import com.suruhaja.databinding.ItemMerchantListBinding
import com.suruhaja.databinding.ItemMerchantMenuPreviewBinding
import com.suruhaja.ui.promo.PromoAdapter

class MerchantAdapter(
    private val onMerchantClick: (Merchant) -> Unit,
    private val onMenuClick: (Merchant, MenuItem) -> Unit
) : ListAdapter<Merchant, RecyclerView.ViewHolder>(DiffCallback) {

    var customerLat: Double = 0.0
    var customerLng: Double = 0.0
    var menuHint: Map<String, String> = emptyMap()
    var promoHeaderVisible: Boolean = false
    var promoAdapter: PromoAdapter? = null
    var onPromoHeaderBound: ((RecyclerView, LinearLayout) -> Unit)? = null

    private val sharedMenuPool = RecyclerView.RecycledViewPool()
    private val headerCount: Int get() = if (promoHeaderVisible) 1 else 0

    override fun getItemCount(): Int = super.getItemCount() + headerCount

    override fun getItemViewType(position: Int): Int =
        if (promoHeaderVisible && position == 0) VIEW_TYPE_PROMO_HEADER else VIEW_TYPE_MERCHANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_PROMO_HEADER) {
            HeaderHolder(ItemMerchantHeaderBinding.inflate(inflater, parent, false))
        } else {
            MerchantHolder(ItemMerchantListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind()
            is MerchantHolder -> holder.bind(getItem(position - headerCount))
        }
    }

    private inner class HeaderHolder(
        private val binding: ItemMerchantHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.rvPromos.adapter = promoAdapter
            onPromoHeaderBound?.invoke(binding.rvPromos, binding.layoutPromoIndicator)
        }
    }

    private inner class MerchantHolder(
        private val binding: ItemMerchantListBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val previewAdapter = MerchantMenuPreviewAdapter { menu ->
            boundMerchant?.let { merchant ->
                if (!merchant.isOnline) {
                    Toast.makeText(binding.root.context, "Toko sudah tutup", Toast.LENGTH_SHORT).show()
                } else {
                    onMenuClick(merchant, menu)
                }
            }
        }
        private var boundMerchant: Merchant? = null

        init {
            binding.rvTopMenu.layoutManager = LinearLayoutManager(
                binding.root.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            binding.rvTopMenu.adapter = previewAdapter
            binding.rvTopMenu.setRecycledViewPool(sharedMenuPool)
            binding.rvTopMenu.isNestedScrollingEnabled = false
        }

        fun bind(merchant: Merchant) {
            boundMerchant = merchant
            val context = binding.root.context
            val closed = !merchant.isOnline
            binding.root.alpha = if (closed) 0.68f else 1f
            binding.tvMerchantName.text = merchant.storeName
            binding.tvMerchantAddress.text = merchant.address.ifBlank { "Pesan makanan favoritmu di sini" }
            binding.tvMerchantClosed.visibility = if (closed) View.VISIBLE else View.GONE
            binding.tvStatus.visibility = if (closed) View.GONE else View.VISIBLE

            val distance = if (customerLat != 0.0 && merchant.latitude != 0.0) {
                "%.1f km".format(distanceKm(customerLat, customerLng, merchant.latitude, merchant.longitude))
            } else null
            val orders = merchant.orderCount.takeIf { it > 0 }?.let { "%,d pesanan".format(it) }
            val matchedMenu = menuHint[merchant.id]
            binding.tvMerchantInfo.text = matchedMenu?.let { "Jual «$it»" }
                ?: listOfNotNull(distance, orders).joinToString(" · ").ifBlank { "SuruhFood" }
            binding.tvMerchantInfo.setTextColor(
                ContextCompat.getColor(context, if (matchedMenu != null) R.color.food_orange_text else R.color.text_muted)
            )
            binding.tvMerchantRating.text = if (merchant.rating > 0) {
                "★ %.1f (%d)".format(merchant.rating, merchant.ratingCount)
            } else {
                "BARU"
            }

            if (merchant.imageUrl.isNotBlank()) {
                binding.ivMerchantPhoto.visibility = View.VISIBLE
                Glide.with(binding.root).load(merchant.imageUrl).into(binding.ivMerchantPhoto)
            } else {
                binding.ivMerchantPhoto.visibility = View.GONE
            }

            val previews = MenuDiscoveryPolicy.topFive(merchant.topMenuPreview)
            binding.layoutTopMenu.visibility = if (previews.isEmpty()) View.GONE else View.VISIBLE
            previewAdapter.submitList(previews)

            val openMerchant = View.OnClickListener {
                if (closed) Toast.makeText(context, "Toko sudah tutup", Toast.LENGTH_SHORT).show()
                else onMerchantClick(merchant)
            }
            binding.root.setOnClickListener(openMerchant)
            binding.btnAllMenu.setOnClickListener(openMerchant)
        }
    }

    private class MerchantMenuPreviewAdapter(
        private val onClick: (MenuItem) -> Unit
    ) : ListAdapter<MenuItem, MerchantMenuPreviewAdapter.Holder>(MenuDiff) {

        class Holder(val binding: ItemMerchantMenuPreviewBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemMerchantMenuPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            b.tvMenuName.text = item.name
            b.tvMenuPrice.text = "Rp %,d".format(item.price)

            val meta = buildList {
                if (item.rating > 0) add("★ %.1f".format(item.rating))
                if (item.soldCount > 0) add("%,d terjual".format(item.soldCount))
            }.joinToString(" · ")
            b.tvMenuMeta.text = meta
            b.tvMenuMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
            b.tvBadge.text = if (item.featured) "PILIHAN TOKO" else "🔥 TERLARIS"
            b.tvBadge.visibility = if (item.featured || (position == 0 && item.soldCount > 0)) View.VISIBLE else View.GONE

            if (item.imageUrl.isNotBlank()) {
                b.ivMenuPhoto.visibility = View.VISIBLE
                Glide.with(b.root).load(item.imageUrl).into(b.ivMenuPhoto)
            } else {
                b.ivMenuPhoto.visibility = View.GONE
            }
            b.root.setOnClickListener { onClick(item) }
        }

        private object MenuDiff : DiffUtil.ItemCallback<MenuItem>() {
            override fun areItemsTheSame(oldItem: MenuItem, newItem: MenuItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MenuItem, newItem: MenuItem) = oldItem == newItem
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

    object DiffCallback : DiffUtil.ItemCallback<Merchant>() {
        override fun areItemsTheSame(oldItem: Merchant, newItem: Merchant) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Merchant, newItem: Merchant) = oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_MERCHANT = 0
        private const val VIEW_TYPE_PROMO_HEADER = 1
    }
}
