package com.suruhaja.ui.food

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.suruhaja.data.model.FoodAddon
import com.suruhaja.data.model.FoodItem
import com.suruhaja.data.repository.FoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import javax.inject.Inject

@HiltViewModel
class FoodLocationViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val merchantId: String = savedStateHandle["merchantId"] ?: ""
    private val merchantName: String = savedStateHandle["merchantName"] ?: ""
    private val merchantLat: Double = navDouble("merchantLat")
    private val merchantLng: Double = navDouble("merchantLng")
    private val total: Long = savedStateHandle["total"] ?: 0L
    private val items: List<FoodItem> = parseItems(savedStateHandle["itemsJson"] ?: "")

    /** Baca arg navigasi numerik dengan toleran terhadap Float/Double/Int/Long. */
    private fun navDouble(key: String): Double = when (val v = savedStateHandle.get<Any>(key)) {
        is Number -> v.toDouble()
        else -> 0.0
    }

    private val _userName = MutableLiveData("")
    val userName: LiveData<String> = _userName

    private val _userPhone = MutableLiveData("")
    val userPhone: LiveData<String> = _userPhone

    init {
        loadUserProfile()
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val doc = firestore.collection("users").document(uid).get().await()
                _userName.value = doc.getString("name") ?: ""
                _userPhone.value = doc.getString("phone") ?: ""
            } catch (e: Exception) {
                // biarkan kosong jika profil gagal dimuat
            }
        }
    }

    suspend fun quote(
        customerLat: Double,
        customerLng: Double,
        distanceKm: Double,
        voucherCode: String = ""
    ): FoodRepository.FoodQuote =
        repository.quoteFoodOrder(merchantId, customerLat, customerLng, distanceKm, items, voucherCode)

    suspend fun createOrder(
        customerAddress: String,
        customerLat: Double,
        customerLng: Double,
        distanceKm: Double,
        deliveryFee: Long,
        paymentMethod: String,
        notes: String,
        voucherCode: String = ""
    ): String = repository.createFoodOrder(
        merchantId = merchantId,
        merchantName = merchantName,
        merchantLat = merchantLat,
        merchantLng = merchantLng,
        customerId = auth.currentUser?.uid ?: "",
        customerName = _userName.value?.ifBlank { _userPhone.value ?: "" } ?: "",
        customerPhone = _userPhone.value ?: "",
        customerAddress = customerAddress,
        customerLat = customerLat,
        customerLng = customerLng,
        items = items,
        total = total,
        distanceKm = distanceKm,
        deliveryFee = deliveryFee,
        paymentMethod = paymentMethod,
        notes = notes,
        voucherCode = voucherCode
    )

    private fun parseItems(json: String): List<FoodItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FoodItem(
                    menuItemId = o.optString("menuItemId", ""),
                    name = o.getString("name"),
                    qty = o.getInt("qty"),
                    price = o.getLong("price"),
                    addons = parseAddons(o.optJSONArray("addons"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseAddons(arr: JSONArray?): List<FoodAddon> {
        if (arr == null) return emptyList()
        return try {
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                FoodAddon(
                    id = a.optString("id", ""),
                    name = a.optString("name", ""),
                    price = a.optLong("price", 0L),
                    qty = a.optInt("qty", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
