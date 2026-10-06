package com.suruhaja.ui.food

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.suruhaja.util.LocationHelper
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import com.suruhaja.ui.component.ZoomableImageView
import com.suruhaja.R
import com.suruhaja.data.model.FoodAddon
import com.suruhaja.data.model.FoodItem
import com.suruhaja.data.model.MenuItem
import com.suruhaja.data.model.MenuAddon
import com.suruhaja.data.policy.FoodAddonPolicy
import com.suruhaja.databinding.FragmentMerchantMenuBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.suruhaja.databinding.DialogAddonPickerBinding
import com.suruhaja.databinding.DialogCartReviewBinding
import com.suruhaja.databinding.DialogMenuPhotoBinding
import com.suruhaja.databinding.ItemCartLineBinding
import com.suruhaja.databinding.ItemFoodAddonBinding
import com.suruhaja.databinding.LayoutMenuHeaderBinding
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONArray
import org.json.JSONObject

/** Satu baris keranjang = satu menu + satu kombinasi addon (qty addon spesifik). */
private data class CartLine(
    val menuItemId: String,
    val name: String,
    val price: Long,
    val addons: List<FoodAddon>,
    var qty: Int
) {
    val unitPrice: Long get() = price + addons.sumOf { it.price.toLong() * it.qty }
    val key: String get() = menuItemId + "|" + addons.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.qty}" }
    fun label(): String = buildString {
        if (addons.isNotEmpty()) {
            append(addons.joinToString(", ") { "${it.name}×${it.qty}" })
        }
    }
}

/** Adapter untuk list addon di picker. */
private class AddonAdapter(
    private val addons: List<MenuAddon>,
    private val steppers: MutableMap<String, Int>,
    private val onUpdate: () -> Unit
) : RecyclerView.Adapter<AddonAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFoodAddonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemFoodAddonBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val addon = addons[position]
        holder.binding.tvAddonName.text = addon.name
        holder.binding.tvAddonPrice.text = if (addon.price == 0L) "Gratis" else "+Rp %,d".format(addon.price)

        val qty = steppers[addon.id] ?: 0
        holder.binding.tvQty.text = qty.toString()

        holder.binding.btnPlus.setOnClickListener {
            val current = steppers[addon.id] ?: 0
            if (current < 99) {
                steppers[addon.id] = current + 1
                holder.binding.tvQty.text = steppers[addon.id].toString()
                onUpdate()
            }
        }

        holder.binding.btnMinus.setOnClickListener {
            val current = steppers[addon.id] ?: 0
            if (current > 0) {
                steppers[addon.id] = current - 1
                holder.binding.tvQty.text = steppers[addon.id].toString()
                onUpdate()
            }
        }
    }

    override fun getItemCount() = addons.size
}

/** Adapter untuk list item di review keranjang. */
private class CartLineAdapter(
    private val lines: List<CartLine>,
    private val onAdjust: (CartLine, Int) -> Unit,
    private val onRemove: (CartLine) -> Unit
) : RecyclerView.Adapter<CartLineAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemCartLineBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemCartLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.binding.tvLineName.text = line.name
        val addonLabel = line.label()
        holder.binding.tvLineAddons.text = if (addonLabel.isEmpty()) "Original" else "+ $addonLabel"
        holder.binding.tvLinePrice.text = "Rp %,d".format(line.qty.toLong() * line.unitPrice)
        holder.binding.tvQty.text = line.qty.toString()

        holder.binding.btnPlus.setOnClickListener { onAdjust(line, 1) }
        holder.binding.btnMinus.setOnClickListener { onAdjust(line, -1) }
        holder.binding.btnRemove.setOnClickListener { onRemove(line) }
    }

    override fun getItemCount() = lines.size
}

@AndroidEntryPoint
class MerchantMenuFragment : Fragment() {

    private var _binding: FragmentMerchantMenuBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MerchantMenuViewModel by viewModels()

    private var menuItems: List<MenuItem> = emptyList()
    private var menuQuery: String = ""
    private var selectedCategory: String? = null
    private var menuSort: MenuSort = MenuSort.DEFAULT
    private var pendingHighlightMenuId: String = ""

    /**
     * Header (hero + banner + search + chip) sekarang item PERTAMA RecyclerView,
     * jadi view-nya datang dari adapter dan bisa di-bind ulang saat didaur ulang.
     */
    private var headerBinding: LayoutMenuHeaderBinding? = null

    /**
     * Instance EditText pencarian yang SUDAH dipasangi listener. Dipakai sebagai
     * penanda, bukan flag boolean: kalau view holder header dibuang dari pool,
     * binding baru dibuat dan listener lama ikut hilang — dengan penanda ini
     * listener selalu dipasang ulang pada instance yang benar-benar aktif.
     */
    private var headerControlsTarget: EditText? = null
    private val cartLines = mutableListOf<CartLine>()
    private var merchantOnline = true
    private var closingSoonMinutes: Int? = null

    private lateinit var menuAdapter: MenuAdapter

    /** Tampilan menu: grid 2 kolom (default) atau daftar — pilihan disimpan. */
    private var gridMode = true

    /** Klien GPS: dipakai untuk mengirim lokasi awal saat checkout. */
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireActivity())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMerchantMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.header.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top
            }
            binding.cartBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBars.bottom + (20 * resources.displayMetrics.density).toInt()
            }
            insets
        }

        val merchantName = arguments?.getString("merchantName") ?: ""
        binding.tvTitle.text = merchantName.ifEmpty { "Menu" }
        pendingHighlightMenuId = arguments?.getString("highlightMenuId").orEmpty()

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        setupMenuList()

        viewModel.menu.observe(viewLifecycleOwner) { items ->
            // Menu yang dinonaktifkan toko TETAP ditampilkan (ditandai "Habis"
            // dan tidak bisa dipesan), jadi daftar di sini tidak disaring.
            if (items.isEmpty()) {
                binding.tvCartSummary.text = "Menu kosong"
                binding.btnCheckout.isEnabled = false
                renderMenu(emptyList())
            } else {
                menuItems = items
                rebuildCategoryChips()
                renderMenu()
                updateCartSummary()
            }
        }

        viewModel.isOnline.observe(viewLifecycleOwner) { online ->
            merchantOnline = online
            updateBanners()
            updateCartSummary()
        }

        viewModel.closingSoonMinutes.observe(viewLifecycleOwner) { mins ->
            closingSoonMinutes = mins
            updateBanners()
        }

        viewModel.unavailableItem.observe(viewLifecycleOwner) { item ->
            if (item == null) return@observe
            val removed = cartLines.removeAll { it.menuItemId == item.id }
            if (removed) updateCartSummary()
            Toast.makeText(requireContext(), "Maaf, \"${item.name}\" telah habis", Toast.LENGTH_SHORT).show()
        }

        binding.cartBar.setOnClickListener { showCartReview() }
        binding.btnCheckout.setOnClickListener { proceedToLocation() }
        updateCartSummary()
    }

    /**
     * Banner: tutup vs segera tutup (jam otomatis) vs normal.
     *
     * Status toko disimpan di field ([merchantOnline], [closingSoonMinutes]),
     * bukan dibaca dari view — header bisa belum ter-bind atau sedang didaur
     * ulang saat observer memanggil ini.
     */
    private fun updateBanners() {
        val header = headerBinding ?: return
        header.tvClosedBanner.visibility = if (merchantOnline) View.GONE else View.VISIBLE
        header.tvMerchantStatus.text = if (merchantOnline) "BUKA · SIAP MASAK" else "TOKO TUTUP"
        header.tvMerchantStatus.setBackgroundResource(
            if (merchantOnline) R.drawable.bg_send_primary_green else R.drawable.bg_activity_cancelled_chip
        )
        val closingSoon = merchantOnline && closingSoonMinutes != null
        header.tvClosingSoonBanner.visibility = if (closingSoon) View.VISIBLE else View.GONE
        if (closingSoon) {
            header.tvClosingSoonBanner.text = "⚠️ Toko akan tutup dalam ±$closingSoonMinutes menit"
        }
    }

    // ─── Daftar menu: grid 2 kolom <-> daftar ───────────────────────────────

    private fun setupMenuList() {
        val prefs = requireContext().getSharedPreferences(PREFS_MENU_VIEW, android.content.Context.MODE_PRIVATE)
        gridMode = prefs.getBoolean(KEY_GRID, true)

        menuAdapter = MenuAdapter(
            isGrid = { gridMode },
            qtyFor = { id -> qtyForItem(id) },
            onOpenPhoto = { item -> showMenuPhoto(item) },
            onPlus = { item -> onMenuPlus(item) },
            onMinus = { item -> onMenuMinus(item) }
        )
        // Header halaman datang dari adapter (item pertama) supaya ikut ter-scroll.
        menuAdapter.onHeaderBound = { header -> bindMenuHeader(header) }
        binding.rvMenu.adapter = menuAdapter
        applyMenuLayoutManager()

        binding.btnViewToggle.setOnClickListener {
            gridMode = !gridMode
            prefs.edit().putBoolean(KEY_GRID, gridMode).apply()
            applyMenuLayoutManager()
            menuAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Dipanggil setiap kali header di-bind: pulihkan seluruh isinya dari state,
     * bukan dari view, supaya aman ketika view holder didaur ulang.
     */
    private fun bindMenuHeader(header: LayoutMenuHeaderBinding) {
        headerBinding = header
        if (headerControlsTarget !== header.etSearchMenu) {
            headerControlsTarget = header.etSearchMenu
            setupMenuDiscoveryControls()
        }
        // Teks pencarian bisa hilang saat header di-recycle; kembalikan tanpa
        // memicu watcher kalau nilainya memang sudah sama.
        if (header.etSearchMenu.text?.toString() != menuQuery) {
            header.etSearchMenu.setText(menuQuery)
            header.etSearchMenu.setSelection(header.etSearchMenu.text?.length ?: 0)
        }
        bindMerchantHero()
        updateSortChips()
        rebuildCategoryChips()
        updateBanners()
    }

    private fun bindMerchantHero() {
        val header = headerBinding ?: return
        val imageUrl = arguments?.getString("merchantImageUrl").orEmpty()
        val address = arguments?.getString("merchantAddress").orEmpty()
        val rating = arguments?.getFloat("merchantRating")?.toDouble() ?: 0.0
        val ratingCount = arguments?.getLong("merchantRatingCount") ?: 0L
        val orderCount = arguments?.getLong("merchantOrderCount") ?: 0L
        header.tvMerchantAddress.text = address.ifBlank { "Menu pilihan yang siap menggugah selera" }
        header.tvMerchantMeta.text = buildList {
            if (rating > 0) add("★ %.1f (%,d)".format(rating, ratingCount))
            if (orderCount > 0) add("%,d pesanan".format(orderCount))
        }.joinToString(" · ").ifBlank { "Toko baru di SuruhFood" }
        if (imageUrl.isNotBlank()) {
            header.ivMerchantCover.visibility = View.VISIBLE
            Glide.with(this).load(imageUrl).into(header.ivMerchantCover)
        } else {
            header.ivMerchantCover.visibility = View.GONE
        }

        // Ringkasan ulasan + tombol menuju daftar review pelanggan lain.
        header.tvReviewsSummary.text = ReviewDisplayPolicy.summaryLabel(rating, ratingCount)
        header.btnReviews.setOnClickListener { openReviews() }
    }

    /** Buka layar review toko ini. Parameter agregat dikirim supaya ringkasan
     *  di layar review memakai angka yang sama dengan kartu toko. */
    private fun openReviews() {
        val merchantId = arguments?.getString("merchantId").orEmpty()
        if (merchantId.isEmpty()) return
        val bundle = Bundle().apply {
            putString("merchantId", merchantId)
            putString("merchantName", arguments?.getString("merchantName").orEmpty())
            putFloat("merchantRating", arguments?.getFloat("merchantRating") ?: 0f)
            putLong("merchantRatingCount", arguments?.getLong("merchantRatingCount") ?: 0L)
        }
        findNavController().navigate(R.id.merchantReviewsFragment, bundle)
    }

    private fun setupMenuDiscoveryControls() {
        val header = headerBinding ?: return
        header.etSearchMenu.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                menuQuery = s?.toString().orEmpty()
                renderMenu()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        header.chipSortAll.setOnClickListener { selectSort(MenuSort.DEFAULT) }
        header.chipSortPopular.setOnClickListener { selectSort(MenuSort.POPULAR) }
        header.chipSortRating.setOnClickListener { selectSort(MenuSort.BEST_RATED) }
        header.chipSortCheapest.setOnClickListener { selectSort(MenuSort.CHEAPEST) }
    }

    private fun selectSort(sort: MenuSort) {
        menuSort = sort
        updateSortChips()
        renderMenu()
    }

    private fun updateSortChips() {
        val header = headerBinding ?: return
        mapOf(
            MenuSort.DEFAULT to header.chipSortAll,
            MenuSort.POPULAR to header.chipSortPopular,
            MenuSort.BEST_RATED to header.chipSortRating,
            MenuSort.CHEAPEST to header.chipSortCheapest
        ).forEach { (sort, chip) ->
            chip.setBackgroundResource(
                if (menuSort == sort) R.drawable.bg_food_primary_orange else R.drawable.bg_food_filter_chip
            )
        }
    }

    private fun rebuildCategoryChips() {
        val container = headerBinding?.layoutCategoryChips ?: return
        val categories = menuItems.map { it.category.trim().ifEmpty { "Menu" } }.distinct()
        if (selectedCategory != null && categories.none { it.equals(selectedCategory, true) }) {
            selectedCategory = null
        }
        container.removeAllViews()
        listOf<String?>(null).plus(categories).forEach { category ->
            val chip = TextView(requireContext()).apply {
                text = category ?: "Semua kategori"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
                typeface = ResourcesCompat.getFont(requireContext(), R.font.poppins_bold)
                textSize = 10f
                val horizontal = (13 * resources.displayMetrics.density).toInt()
                val vertical = (7 * resources.displayMetrics.density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                setBackgroundResource(
                    if (category == selectedCategory) R.drawable.bg_food_primary_orange
                    else R.drawable.bg_food_filter_chip
                )
                setOnClickListener {
                    selectedCategory = category
                    rebuildCategoryChips()
                    renderMenu()
                }
            }
            container.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            )
        }
    }

    private fun applyMenuLayoutManager() {
        binding.rvMenu.layoutManager = if (gridMode) {
            GridLayoutManager(requireContext(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        menuAdapter.spanSizeFor(position, spanCount)
                }
            }
        } else {
            LinearLayoutManager(requireContext())
        }
        binding.btnViewToggle.setImageResource(if (gridMode) R.drawable.ic_list else R.drawable.ic_grid)
    }

    /** Tombol + : menu beraddon → picker, tanpa addon → langsung tambah. */
    private fun onMenuPlus(item: MenuItem) {
        if (!MenuDiscoveryPolicy.isOrderable(item)) {
            Toast.makeText(requireContext(), "Maaf, \"${item.name}\" sedang habis", Toast.LENGTH_SHORT).show()
            return
        }
        if (item.addons.any { it.available }) showAddonPicker(item) else adjustNoAddon(item, +1)
    }

    /** Tombol − : menu beraddon → buka keranjang (atur di sana), tanpa addon → kurangi. */
    private fun onMenuMinus(item: MenuItem) {
        if (!MenuDiscoveryPolicy.isOrderable(item)) return
        if (item.addons.any { it.available }) showCartReview() else adjustNoAddon(item, -1)
    }

    /**
     * Popup foto menu: foto tampil UTUH (tidak dipotong) mengikuti rasio asli
     * foto, tetapi tingginya dibatasi [PHOTO_MAX_SCREEN_FRACTION] tinggi layar
     * supaya tidak menutupi seluruh layar.
     */
    private fun showMenuPhoto(item: MenuItem) {
        if (item.imageUrl.isBlank()) return
        val dialog = BottomSheetDialog(requireContext())
        val photoBinding = DialogMenuPhotoBinding.inflate(layoutInflater)
        dialog.setContentView(photoBinding.root)

        photoBinding.tvName.text = item.name
        photoBinding.tvPrice.text = "Rp %,d".format(item.price)
        photoBinding.ivPhoto.maxHeight =
            (resources.displayMetrics.heightPixels * PHOTO_MAX_SCREEN_FRACTION).toInt()
        photoBinding.ivPhoto.resetZoom()
        // Dimuat dengan resolusi lebih tinggi dari ukuran tampilan supaya gambar
        // tetap tajam ketika di-zoom (bukan hasil downscale ukuran view).
        val loadWidth = maxOf(resources.displayMetrics.widthPixels, ZOOM_DETAIL_MIN_WIDTH)
        Glide.with(this).load(item.imageUrl)
            .override(loadWidth, Target.SIZE_ORIGINAL)
            .into(photoBinding.ivPhoto)

        photoBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun renderMenu(items: List<MenuItem>) {
        menuItems = items
        rebuildCategoryChips()
        renderMenu()
    }

    private fun renderMenu() {
        val filtered = MenuDiscoveryPolicy.filter(menuItems, menuQuery, selectedCategory)
        val visibleItems = MenuDiscoveryPolicy.sort(filtered, menuSort)
        val rows = mutableListOf<MenuRow>()
        val grouped = menuQuery.isBlank() && menuSort == MenuSort.DEFAULT
        if (visibleItems.isEmpty()) {
            rows.add(MenuRow.Category("Menu tidak ditemukan"))
        } else if (grouped) {
            visibleItems.groupBy { it.category.trim().ifEmpty { "Menu" } }.forEach { (category, categoryItems) ->
                rows.add(MenuRow.Category(category))
                categoryItems.forEach { item -> rows.add(menuRow(item)) }
            }
        } else {
            val title = when (menuSort) {
                MenuSort.POPULAR -> "🔥 Menu terlaris"
                MenuSort.BEST_RATED -> "★ Rating terbaik"
                MenuSort.CHEAPEST -> "Harga termurah"
                MenuSort.MOST_EXPENSIVE -> "Harga tertinggi"
                MenuSort.DEFAULT -> "Hasil pencarian"
            }
            rows.add(MenuRow.Category(title))
            visibleItems.forEach { item -> rows.add(menuRow(item)) }
        }
        menuAdapter.submit(rows)
        if (pendingHighlightMenuId.isNotBlank()) {
            // Posisi adapter sudah termasuk item header, jadi scroll tidak
            // pernah mendarat satu baris terlalu awal.
            val position = menuAdapter.adapterPositionOfMenu(pendingHighlightMenuId)
            if (position >= 0) {
                binding.rvMenu.post { binding.rvMenu.smoothScrollToPosition(position) }
                pendingHighlightMenuId = ""
            }
        }
    }

    private fun menuRow(item: MenuItem): MenuRow.Item {
        val addonHint = if (item.addons.isNotEmpty()) {
            val hint = item.addons.filter { it.available }.joinToString(", ") { it.name }
            if (item.description.isBlank()) "+ $hint" else "${item.description}\n+ $hint"
        } else {
            item.description
        }
        return MenuRow.Item(item, addonHint)
    }

    private fun qtyForItem(itemId: String): Int =
        cartLines.filter { it.menuItemId == itemId }.sumOf { it.qty }

    private fun adjustNoAddon(item: MenuItem, delta: Int) {
        val key = item.id + "|"
        val line = cartLines.firstOrNull { it.key == key }
        if (line == null) {
            if (delta > 0) cartLines.add(CartLine(item.id, item.name, item.price, emptyList(), 1))
        } else {
            line.qty += delta
            if (line.qty <= 0) cartLines.remove(line)
        }
        menuAdapter.refreshQuantities()
        updateCartSummary()
    }

    /** Picker addon beautified: BottomSheet dengan RecyclerView. */
    private fun showAddonPicker(item: MenuItem) {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogAddonPickerBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Addon bersifat OPSIONAL — dibuat eksplisit di judul supaya customer
        // tahu ia boleh langsung menambah tanpa memilih addon apa pun.
        dialogBinding.tvTitle.text = "Addon (opsional) — ${item.name}"

        // Batasi tinggi daftar addon (maks 45% tinggi layar) supaya tombol
        // "TAMBAH KE KERANJANG" tetap terlihat walau addon sangat banyak.
        val maxListHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        dialogBinding.rvAddons.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintMaxHeight = maxListHeight
        }

        val steppers = mutableMapOf<String, Int>()
        val availableAddons = item.addons.filter { it.available }

        // Tombol menampilkan harga per porsi (menu + addon terpilih) supaya
        // jelas: tanpa addon pun harganya sudah final dan bisa langsung ditambah.
        fun refreshAddButton() {
            val selected = FoodAddonPolicy.selectedAddons(availableAddons, steppers)
            dialogBinding.btnAdd.text = "TAMBAH • Rp %,d".format(FoodAddonPolicy.unitPrice(item.price, selected))
        }

        val adapter = AddonAdapter(availableAddons, steppers) { refreshAddButton() }
        dialogBinding.rvAddons.adapter = adapter
        refreshAddButton()

        dialogBinding.btnAdd.setOnClickListener {
            // Tanpa addon TETAP boleh ditambahkan: addon opsional, bukan wajib.
            addLine(item, FoodAddonPolicy.selectedAddons(availableAddons, steppers))
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun addLine(item: MenuItem, addons: List<FoodAddon>) {
        val line = CartLine(item.id, item.name, item.price, addons, 1)
        val existing = cartLines.firstOrNull { it.key == line.key }
        if (existing != null) existing.qty += 1 else cartLines.add(line)
        menuAdapter.refreshQuantities()
        updateCartSummary()
    }

    private fun showCartReview() {
        if (cartLines.isEmpty()) {
            Toast.makeText(requireContext(), "Keranjang kosong", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogCartReviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val adapter = CartLineAdapter(
            lines = cartLines,
            onAdjust = { line, delta ->
                line.qty += delta
                if (line.qty <= 0) cartLines.remove(line)
                updateCartSummary()
                if (cartLines.isEmpty()) dialog.dismiss() else dialogBinding.rvCartItems.adapter?.notifyDataSetChanged()
                menuAdapter.refreshQuantities()
            },
            onRemove = { line ->
                cartLines.remove(line)
                updateCartSummary()
                if (cartLines.isEmpty()) dialog.dismiss() else dialogBinding.rvCartItems.adapter?.notifyDataSetChanged()
                menuAdapter.refreshQuantities()
            }
        )
        dialogBinding.rvCartItems.adapter = adapter

        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateCartSummary() {
        val count = cartLines.sumOf { it.qty }
        val total = cartLines.sumOf { it.qty.toLong() * it.unitPrice }
        binding.tvCartSummary.text = "%d item · Rp %,d".format(count, total)
        val canCheckout = count > 0 && merchantOnline
        binding.btnCheckout.isEnabled = canCheckout
        binding.btnCheckout.alpha = if (canCheckout) 1f else 0.5f
        binding.btnCheckout.text = if (!merchantOnline) "TUTUP" else "CHECKOUT"
    }

    private fun proceedToLocation() {
        val items = cartLines.map { line ->
            FoodItem(menuItemId = line.menuItemId, name = line.name, qty = line.qty, price = line.price, addons = line.addons)
        }
        if (items.isEmpty()) return

        val total = items.sumOf { it.qty.toLong() * (it.price + it.addons.sumOf { a -> a.price.toLong() * a.qty }) }
        val itemsJson = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("menuItemId", item.menuItemId)
                        put("name", item.name)
                        put("qty", item.qty)
                        put("price", item.price)
                        put(
                            "addons",
                            JSONArray().apply {
                                item.addons.forEach { a ->
                                    put(JSONObject().apply { put("id", a.id); put("qty", a.qty) })
                                }
                            }
                        )
                    }
                )
            }
        }.toString()

        val bundle = Bundle().apply {
            putString("merchantId", arguments?.getString("merchantId") ?: "")
            putString("merchantName", arguments?.getString("merchantName") ?: "")
            putFloat("merchantLat", arguments?.getFloat("merchantLat") ?: 0f)
            putFloat("merchantLng", arguments?.getFloat("merchantLng") ?: 0f)
            putString("itemsJson", itemsJson)
            putLong("total", total)
        }
        // Lokasi customer ikut dikirim kalau sudah diketahui, supaya layar
        // konfirmasi antar LANGSUNG menampilkan posisinya tanpa menunggu GPS.
        // peekLast() instan (baca cache provider), jadi checkout tidak tertahan.
        viewLifecycleOwner.lifecycleScope.launch {
            val peek = LocationHelper.peekLast(fusedLocationClient)
            if (peek != null) {
                bundle.putFloat("customerLat", peek.latitude.toFloat())
                bundle.putFloat("customerLng", peek.longitude.toFloat())
            }
            if (isAdded && view != null) {
                findNavController().navigate(R.id.foodLocationFragment, bundle)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        headerBinding = null
        headerControlsTarget = null
        _binding = null
    }

    companion object {
        private const val PREFS_MENU_VIEW = "suruhaja_menu_view"
        private const val KEY_GRID = "grid"
        /** Foto popup maksimum 70% tinggi layar supaya tidak menutupi seluruh layar. */
        private const val PHOTO_MAX_SCREEN_FRACTION = 0.7f
        /** Lebar minimum saat memuat foto, supaya tetap tajam waktu di-zoom. */
        private const val ZOOM_DETAIL_MIN_WIDTH = 1080
    }
}
