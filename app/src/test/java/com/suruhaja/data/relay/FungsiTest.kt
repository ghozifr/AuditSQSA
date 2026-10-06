package com.suruhaja.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Penjaga bentuk "pintu tunggal" callable.
 *
 * [Fungsi] menggantikan `FirebaseFunctions.getInstance(region)` di seluruh app,
 * jadi rantai panggilan lama (`getHttpsCallable(...).call(...).await()` dan
 * `.getData()`) tetap sah. Tes ini memastikan shim-nya terbentuk tanpa meledak
 * dan mengembalikan Task sungguhan (bentuk yang sama dengan SDK).
 *
 * Catatan: sengaja TIDAK menunggu (`await`) Task-nya di sini, karena di lingkungan
 * tes JVM FirebaseApp belum diinisialisasi — yang penting bentuknya benar dan
 * tidak menggantung.
 */
class FungsiTest {

    @Test
    fun `shim mengembalikan Task seperti SDK`() {
        val task = Fungsi.getInstance("asia-southeast2")
            .getHttpsCallable("quoteOrder")
            .call(mapOf("distanceKm" to 7.5))
        assertNotNull("shim harus mengembalikan Task", task)
    }

    @Test
    fun `shim juga bisa dipanggil tanpa region dan tanpa data`() {
        val task = Fungsi.getInstance().getHttpsCallable("getReferralSummary").call()
        assertNotNull(task)
    }

    @Test
    fun `hasil membungkus data apa adanya`() {
        assertEquals("order-123", Hasil("order-123").getData())
        assertEquals(null, Hasil(null).getData())
    }
}
