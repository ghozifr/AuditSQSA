package com.suruhaja.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.suruhaja.data.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _user = MutableLiveData<User>()
    val user: LiveData<User> = _user

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _saveResult = MutableLiveData<SaveResult?>()
    val saveResult: LiveData<SaveResult?> = _saveResult

    sealed class SaveResult {
        data object Success : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    init { loadUser() }

    fun loadUser() {
        _loading.value = true
        viewModelScope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val doc = firestore.collection("users").document(uid).get().await()
                if (doc.exists()) {
                    _user.value = User(
                        uid = uid,
                        name = doc.getString("name") ?: "",
                        phone = doc.getString("phone") ?: auth.currentUser?.phoneNumber ?: "",
                        email = doc.getString("email") ?: "",
                        emailVerified = doc.getBoolean("emailVerified") ?: (auth.currentUser?.isEmailVerified == true),
                        photoUrl = doc.getString("photoUrl") ?: "",
                        balance = doc.getLong("balance") ?: 0L,
                        points = doc.getLong("points") ?: 0L,
                        createdAt = doc.getLong("createdAt") ?: 0L
                    )
                } else {
                    _user.value = User(
                        uid = uid, name = "",
                        phone = auth.currentUser?.phoneNumber ?: "",
                        balance = 0L, points = 0L, createdAt = 0L
                    )
                }
            } catch (e: Exception) {
                val uid = auth.currentUser?.uid ?: ""
                _user.value = User(
                    uid = uid, name = "",
                    phone = auth.currentUser?.phoneNumber ?: "",
                    balance = 0L, points = 0L, createdAt = 0L
                )
            } finally { _loading.value = false }
        }
    }

    fun saveProfile(name: String, phone: String) {
        if (name.isBlank()) { _saveResult.value = SaveResult.Error("Nama tidak boleh kosong"); return }
        _loading.value = true
        viewModelScope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                firestore.collection("users").document(uid)
                    .set(mapOf("name" to name, "phone" to phone), com.google.firebase.firestore.SetOptions.merge())
                    .await()
                _user.value = _user.value?.copy(name = name, phone = phone)
                _saveResult.value = SaveResult.Success
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Gagal menyimpan")
            } finally { _loading.value = false }
        }
    }

    fun refresh() { loadUser() }
}
