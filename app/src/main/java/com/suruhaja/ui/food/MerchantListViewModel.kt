package com.suruhaja.ui.food

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suruhaja.data.model.Merchant
import com.suruhaja.data.model.Promotion
import com.suruhaja.data.policy.PromoVisibilityPolicy
import com.suruhaja.data.repository.FoodRepository
import com.suruhaja.data.repository.PromotionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MerchantListViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val promotionRepository: PromotionRepository
) : ViewModel() {

    private val _merchants = MutableLiveData<List<Merchant>>(emptyList())
    val merchants: LiveData<List<Merchant>> = _merchants

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _isGridView = MutableLiveData(true)
    val isGridView: LiveData<Boolean> = _isGridView

    private val _activeFilter = MutableLiveData(FILTER_NEAREST)
    val activeFilter: LiveData<String> = _activeFilter

    /** Penawaran Terbaik yang mengarah ke toko — tampil di atas daftar toko. */
    private val _promotions = MutableLiveData<List<Promotion>>(emptyList())
    val promotions: LiveData<List<Promotion>> = _promotions

    /** merchantId → nama menu yang cocok dengan query (untuk label di kartu toko). */
    private val _menuMatchHint = MutableLiveData<Map<String, String>>(emptyMap())
    val menuMatchHint: LiveData<Map<String, String>> = _menuMatchHint

    private var allMerchants: List<Merchant> = emptyList()
    private var allPromos: List<Promotion> = emptyList()
    private var menuIndex: Map<String, List<String>> = emptyMap()
    private var previewFallback: Map<String, List<com.suruhaja.data.model.MenuItem>> = emptyMap()
    private var previewSignature = ""
    private var previewLoading = false
    private var indexSignature = ""
    private var indexLoadedAt = 0L
    private var indexLoading = false
    private var customerLat = 0.0
    private var customerLng = 0.0
    private var filter = FILTER_NEAREST
    private var query = ""

    companion object {
        const val FILTER_NEAREST = "nearest"
        const val FILTER_POPULAR = "popular"   // menu terbaik / paling banyak dipesan
        const val FILTER_RATING = "rating"     // rating tertinggi
        const val MAX_RADIUS_KM = 30.0         // batas jarak toko dari customer
        const val MENU_QUERY_TOKEN_LIMIT = 2   // token yang di-query ke index menu
        const val MENU_INDEX_TTL_MS = 5 * 60 * 1000L  // hasil pencarian dianggap segar 5 menit
        const val MAX_PREVIEW_MERCHANTS = 40
    }

    init {
        viewModelScope.launch {
            _loading.value = true
            repository.observeMerchants().collect { list ->
                allMerchants = list
                apply()
                _loading.value = false
            }
        }
        // Index menu dimuat lazy: hanya saat user benar-benar mencari, dan hanya
        // untuk toko dalam radius (lihat ensureMenuIndex) — hemat baca/biaya.
        viewModelScope.launch {
            // Layar SuruhFood hanya menampilkan promo yang berkaitan dengan toko/menu.
            promotionRepository.observeActivePromotions(foodOnly = true).collect { list ->
                allPromos = list
                publishPromos()
            }
        }
    }

    /**
     * Cari toko dari daftar yang sedang tampil (dipakai saat kartu penawaran
     * ditekan). Null = toko di luar radius / belum termuat.
     */
    fun findVisibleMerchant(merchantId: String): Merchant? =
        allMerchants.firstOrNull { it.id == merchantId }

    /**
     * Ambil index menu HANYA kalau:
     *  - query sudah cukup panjang (min [MENU_QUERY_MIN_CHARS] huruf),
     *  - set toko dalam radius berubah ATAU cache sudah lewat [MENU_INDEX_TTL_MS],
     *  - dan tidak ada pemuatan yang sedang jalan.
     *
     * Hasilnya: user yang cuma buka SuruhFood tanpa mencari = 0 dokumen menu
     * dibaca; user yang mencari = dibaca sekali, lalu dipakai ulang sampai
     * 5 menit / pindah lokasi. Toko di kota lain tidak pernah ikut dibaca.
     */
    private fun ensureMenuIndex(inRadius: List<Merchant>) {
        val tokens = MerchantSearchPolicy.queryTokens(query, MENU_QUERY_TOKEN_LIMIT)
        if (tokens.isEmpty()) return
        val ids = inRadius.map { it.id }.sorted()
        val signature = tokens.joinToString(",") + "|" + ids.joinToString(",")
        val fresh = System.currentTimeMillis() - indexLoadedAt < MENU_INDEX_TTL_MS
        if (signature == indexSignature && fresh) return
        if (indexLoading) return
        indexLoading = true
        viewModelScope.launch {
            val hits = repository.searchMenuKeywords(tokens)
            indexLoading = false
            indexSignature = signature
            indexLoadedAt = System.currentTimeMillis()
            // Toko di luar radius tidak akan tampil → hasilnya dibuang.
            val allowed = ids.toHashSet()
            val index = hits.filter { it.merchantId in allowed }
                .groupBy({ it.merchantId }, { it.name })
            if (index != menuIndex) {
                menuIndex = index
                apply()
            }
        }
    }

    /** Paksa ambil ulang index pada pencarian berikutnya (mis. setelah pull-to-refresh). */
    fun invalidateMenuIndexCache() {
        indexSignature = ""
        indexLoadedAt = 0L
    }

    fun setLocation(lat: Double, lng: Double) {
        customerLat = lat; customerLng = lng
        publishPromos()   // promo toko ikut disaring ulang setelah lokasi diketahui
        apply()
    }

    /**
     * Promo toko disaring per jarak: customer di kota lain tidak melihat promo
     * toko kota lain. Promo voucher/info tetap tampil (tidak terikat lokasi).
     */
    private fun publishPromos() {
        _promotions.value = PromoVisibilityPolicy.visible(allPromos, customerLat, customerLng)
    }
    fun setFilter(f: String) { filter = f; _activeFilter.value = f; apply() }

    fun setQuery(q: String) { query = q.trim(); apply() }

    fun toggleView() {
        _isGridView.value = !(_isGridView.value ?: true)
    }

    private fun apply() {
        var list = allMerchants
        // Fail closed sampai lokasi customer tersedia; setelah itu hanya toko ≤30 km.
        if (customerLat == 0.0 || customerLng == 0.0) {
            _merchants.value = emptyList()
            return
        }
        list = list.filter {
            distanceKm(customerLat, customerLng, it.latitude, it.longitude) <= MAX_RADIUS_KM
        }
        ensureTopMenuPreviews(list)
        list = list.map { merchant ->
            if (merchant.topMenuPreview.isNotEmpty()) merchant
            else merchant.copy(topMenuPreview = previewFallback[merchant.id].orEmpty())
        }
        // Toko di luar radius tidak akan pernah tampil → jangan baca menunya.
        ensureMenuIndex(list)
        val searchResult = MerchantSearchPolicy.search(list, query, menuIndex)
        list = searchResult.merchants
        // LiveData selalu notify walau nilainya sama → guard supaya tidak
        // memicu rebind berulang di adapter.
        if (_menuMatchHint.value != searchResult.menuHints) {
            _menuMatchHint.value = searchResult.menuHints
        }
        list = when (filter) {
            FILTER_POPULAR -> list.sortedByDescending { it.orderCount }
            FILTER_RATING -> list.sortedByDescending { it.rating }
            else -> list.sortedBy { distanceKm(customerLat, customerLng, it.latitude, it.longitude) }
        }
        // Online dulu, offline (tutup) di bawah — stable sort menjaga urutan filter.
        list = list.sortedByDescending { it.isOnline }
        _merchants.value = list
    }

    /**
     * Rollout fallback: satu query whereIn per 10 merchant, bukan listener menu
     * pada setiap kartu. Setelah server menulis topMenuPreview, jalur ini otomatis
     * tidak dipakai untuk merchant tersebut.
     */
    private fun ensureTopMenuPreviews(inRadius: List<Merchant>) {
        val missingIds = inRadius
            .asSequence()
            .filter { it.topMenuPreview.isEmpty() }
            .sortedBy { distanceKm(customerLat, customerLng, it.latitude, it.longitude) }
            .map { it.id }
            .filter { it.isNotBlank() }
            .take(MAX_PREVIEW_MERCHANTS)
            .sorted()
            .toList()
        if (missingIds.isEmpty()) return
        val signature = missingIds.joinToString(",")
        if (previewLoading || signature == previewSignature) return
        previewLoading = true
        previewSignature = signature
        viewModelScope.launch {
            val loaded = repository.getMenusForMerchants(missingIds)
                .mapValues { (_, items) -> MenuDiscoveryPolicy.topFive(items) }
            previewLoading = false
            previewFallback = previewFallback + loaded
            apply()
        }
    }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat1 == 0.0 || lng1 == 0.0 || lat2 == 0.0 || lng2 == 0.0) return Double.MAX_VALUE
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
