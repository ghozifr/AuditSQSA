package com.suruhaja.ui.food

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.suruhaja.data.model.MenuItem
import com.suruhaja.data.repository.FoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class MerchantMenuViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val merchantId: String = savedStateHandle["merchantId"] ?: ""

    private val _menu = MutableLiveData<List<MenuItem>>(emptyList())
    val menu: LiveData<List<MenuItem>> = _menu

    private val _isOnline = MutableLiveData(true)
    val isOnline: LiveData<Boolean> = _isOnline

    private val _closingSoonMinutes = MutableLiveData<Int?>(null)
    val closingSoonMinutes: LiveData<Int?> = _closingSoonMinutes

    /** Item yang BARU saja ditandai habis oleh toko (untuk hapus dari keranjang). */
    private val _unavailableItem = MutableLiveData<MenuItem?>(null)
    val unavailableItem: LiveData<MenuItem?> = _unavailableItem

    private val _userName = MutableLiveData("")
    val userName: LiveData<String> = _userName

    private val _userPhone = MutableLiveData("")
    val userPhone: LiveData<String> = _userPhone

    private var merchantListener: ListenerRegistration? = null

    init {
        observeMenu()
        loadUserProfile()
        observeMerchantOnline()
    }

    /** Menu real-time: deteksi item yang habis + simpan daftar penuh (termasuk habis). */
    private fun observeMenu() {
        if (merchantId.isEmpty()) return
        viewModelScope.launch {
            repository.observeMenu(merchantId).collect { items ->
                val prev = _menu.value ?: emptyList()
                val prevMap = prev.associateBy { it.id }
                for (item in items) {
                    val was = prevMap[item.id]
                    if (was != null && was.available && !item.available) {
                        _unavailableItem.value = item
                    }
                }
                _menu.value = items
            }
        }
    }

    /** Pantau status online toko real-time (server-only) — blokir order kalau tutup. */
    private fun observeMerchantOnline() {
        if (merchantId.isEmpty()) return
        merchantListener?.remove()
        merchantListener = firestore.collection("merchants").document(merchantId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener
                _isOnline.value = snap.getBoolean("isOnline") ?: false
                val closeTime = snap.getString("autoOfflineTime") ?: ""
                _closingSoonMinutes.value = minutesUntilClose(closeTime)
            }
    }

    /** Menit tersisa sampai jam tutup, kalau 1–20 menit lagi (untuk notice "segera tutup"). */
    private fun minutesUntilClose(closeTime: String): Int? {
        if (closeTime.isBlank()) return null
        val parts = closeTime.split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val close = h * 60 + m
        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val diff = close - current
        return if (diff in 1..20) diff else null
    }

    override fun onCleared() {
        merchantListener?.remove()
        super.onCleared()
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
}
