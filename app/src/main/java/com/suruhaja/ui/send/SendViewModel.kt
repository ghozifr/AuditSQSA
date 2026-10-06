package com.suruhaja.ui.send

import androidx.lifecycle.ViewModel

/**
 * Draft order SuruhSend, dibagikan antar 3 fragment lewat activity-scoped ViewModel
 * (vehicle → form → location) tanpa harus bawa Bundle panjang.
 */
class SendViewModel : ViewModel() {
    var vehicle: String = "motor"   // "motor" | "car" (car = template, hidden utk sekarang)
    var itemName: String = ""
    var itemDesc: String = ""
    var weightKg: Int = 1
    var receiverName: String = ""
    var receiverPhone: String = ""
    var senderName: String = ""
    var senderPhone: String = ""

    /** Reset draft untuk order baru. */
    fun reset() {
        vehicle = "motor"
        itemName = ""
        itemDesc = ""
        weightKg = 1
        receiverName = ""
        receiverPhone = ""
        senderName = ""
        senderPhone = ""
    }
}
