package com.suruhaja.ui.food

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.suruhaja.R
import com.suruhaja.data.model.Merchant
import com.suruhaja.data.model.Promotion
import com.suruhaja.databinding.FragmentMerchantListBinding
import com.suruhaja.ui.promo.PromoAdapter
import com.suruhaja.ui.promo.PromoCarousel
import com.suruhaja.util.LocationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MerchantListFragment : Fragment() {

    private var _binding: FragmentMerchantListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MerchantListViewModel by viewModels()
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private lateinit var adapter: MerchantAdapter
    private lateinit var promoAdapter: PromoAdapter
    private var promoCarousel: PromoCarousel? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMerchantListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply status bar padding to header (LinearLayout)
            (binding.root as? ViewGroup)?.getChildAt(0)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top
            }

            // Apply nav bar inset as padding to root
            binding.root.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        // Promo toko jadi ITEM PERTAMA di dalam daftar supaya ikut ter-scroll.
        promoAdapter = PromoAdapter { promo -> openPromoMerchant(promo) }
        adapter = MerchantAdapter(
            onMerchantClick = { merchant -> openMerchantMenu(merchant) },
            onMenuClick = { merchant, menu -> openMerchantMenu(merchant, menu.id) }
        )
        adapter.promoAdapter = promoAdapter
        adapter.onPromoHeaderBound = { rv, indicator ->
            // WAJIB pakai satu instance yang di-rebind: membuat PromoCarousel baru
            // tiap bind akan memasang SnapHelper kedua di RecyclerView yang sama
            // → crash "An instance of OnFlingListener already set".
            val carousel = promoCarousel
            if (carousel == null) {
                promoCarousel = PromoCarousel(rv, indicator)
            } else {
                carousel.rebind(rv, indicator)
            }
            promoCarousel?.submit(
                viewModel.promotions.value?.size ?: 0,
                viewLifecycleOwner.lifecycleScope
            )
        }
        binding.rvMerchants.adapter = adapter

        setupPromoSection()

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }


        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fetchLocation()

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { viewModel.setQuery(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.chipNearest.setOnClickListener { viewModel.setFilter(MerchantListViewModel.FILTER_NEAREST) }
        binding.chipPopular.setOnClickListener { viewModel.setFilter(MerchantListViewModel.FILTER_POPULAR) }
        binding.chipRating.setOnClickListener { viewModel.setFilter(MerchantListViewModel.FILTER_RATING) }

        viewModel.activeFilter.observe(viewLifecycleOwner) { active -> updateFilterChips(active) }

        viewModel.merchants.observe(viewLifecycleOwner) { merchants ->
            binding.layoutEmpty.visibility = if (merchants.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(merchants)
            // Query berubah → header promo bisa ikut muncul/hilang.
            if (::promoAdapter.isInitialized) updatePromoHeaderVisibility()
        }

        // Label menu yang cocok (mis. "Jual «Nasi Goreng»") di kartu toko.
        viewModel.menuMatchHint.observe(viewLifecycleOwner) { hints ->
            adapter.menuHint = hints
            adapter.notifyDataSetChanged()
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }


    }

    private fun updateFilterChips(active: String) {
        val chips = mapOf(
            MerchantListViewModel.FILTER_NEAREST to binding.chipNearest,
            MerchantListViewModel.FILTER_POPULAR to binding.chipPopular,
            MerchantListViewModel.FILTER_RATING to binding.chipRating
        )
        chips.forEach { (key, chip) ->
            val on = key == active
            chip.setBackgroundResource(
                if (on) R.drawable.bg_food_primary_orange else R.drawable.bg_food_filter_chip
            )
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_ink))
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        viewLifecycleOwner.lifecycleScope.launch {
            val loc = LocationHelper.fetch(fusedLocationClient)
            if (loc != null) applyLocation(loc.latitude, loc.longitude)
        }
    }

    private fun applyLocation(lat: Double, lng: Double) {
        adapter.customerLat = lat
        adapter.customerLng = lng
        viewModel.setLocation(lat, lng)
    }

    // ─── Penawaran toko (dari admin web) — item pertama daftar toko ────────
    private fun setupPromoSection() {
        viewModel.promotions.observe(viewLifecycleOwner) { promos ->
            promoAdapter.submit(promos)
            updatePromoHeaderVisibility()
        }
    }

    /**
     * Header penawaran tampil saat ada promo DAN user tidak sedang mencari —
     * supaya hasil pencarian kosong tidak tertimpa banner promo.
     */
    private fun updatePromoHeaderVisibility() {
        val searching = !binding.etSearch.text.isNullOrBlank()
        val hasPromos = !viewModel.promotions.value.isNullOrEmpty()
        val shouldShow = hasPromos && !searching
        if (adapter.promoHeaderVisible == shouldShow) return
        adapter.promoHeaderVisible = shouldShow
        adapter.notifyDataSetChanged()
    }

    /** Buka toko dari kartu penawaran. Toko di luar radius 30 km tidak dibuka. */
    private fun openPromoMerchant(promo: Promotion) {
        val merchant = viewModel.findVisibleMerchant(promo.merchantId)
        if (merchant == null) {
            Toast.makeText(requireContext(), "Toko pada penawaran ini di luar jangkauan", Toast.LENGTH_SHORT).show()
            return
        }
        openMerchantMenu(merchant)
    }

    private fun openMerchantMenu(merchant: Merchant, highlightMenuId: String = "") {
        val bundle = Bundle().apply {
            putString("merchantId", merchant.id)
            putString("merchantName", merchant.storeName)
            putFloat("merchantLat", merchant.latitude.toFloat())
            putFloat("merchantLng", merchant.longitude.toFloat())
            putString("merchantImageUrl", merchant.imageUrl)
            putString("merchantAddress", merchant.address)
            putFloat("merchantRating", merchant.rating.toFloat())
            putLong("merchantRatingCount", merchant.ratingCount)
            putLong("merchantOrderCount", merchant.orderCount)
            putString("highlightMenuId", highlightMenuId)
        }
        findNavController().navigate(R.id.merchantMenuFragment, bundle)
    }

    override fun onResume() {
        super.onResume()
        // Penawaran bergeser otomatis lagi setelah kembali ke layar ini.
        promoCarousel?.resume(viewLifecycleOwner.lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        promoCarousel?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        promoCarousel?.pause()
        promoCarousel = null
        _binding = null
    }
}
