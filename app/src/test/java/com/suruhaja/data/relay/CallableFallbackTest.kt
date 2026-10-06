package com.suruhaja.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mengunci kontrak jalur cadangan callable: bentuk body yang dikirim ke relay dan
 * cara membaca balasannya. Bentuk yang sama diuji nyata terhadap fungsi palsu di
 * relay/test/forward-call.test.js — jadi kedua sisi sepakat.
 *
 * Penanda `dariRelay` penting: kalau RELAY yang menolak, pemanggil menampilkan
 * error jaringan asli; kalau FUNGSI yang menolak, pesan server yang ditampilkan.
 */
class CallableFallbackTest {

    @Test
    fun `body callable dibungkus dalam field data`() {
        val b = RelayClient.bodyCallable(mapOf("code" to "123456", "appVersionCode" to 24))
        val data = b.getJSONObject("data")
        assertEquals("123456", data.getString("code"))
        assertEquals(24, data.getInt("appVersionCode"))
    }

    @Test
    fun `body callable meneruskan map bersarang dan daftar apa adanya`() {
        val b = RelayClient.bodyCallable(
            mapOf(
                "items" to listOf(
                    mapOf(
                        "menuItemId" to "m1",
                        "qty" to 2,
                        "addons" to listOf(mapOf("id" to "a1", "qty" to 1))
                    )
                ),
                "voucherCode" to "ABC"
            )
        )
        val data = b.getJSONObject("data")
        val items = data.getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals("m1", items.getJSONObject(0).getString("menuItemId"))
        assertEquals(
            1,
            items.getJSONObject(0).getJSONArray("addons").getJSONObject(0).getInt("qty")
        )
        assertEquals("ABC", data.getString("voucherCode"))
    }

    @Test
    fun `payload kosong tetap jadi objek data kosong`() {
        val b = RelayClient.bodyCallable(null)
        assertEquals(0, b.getJSONObject("data").length())
    }

    @Test
    fun `nilai null tetap terkirim sebagai null json`() {
        val b = RelayClient.bodyCallable(mapOf("voucherCode" to null))
        assertTrue(b.getJSONObject("data").isNull("voucherCode"))
    }

    @Test
    fun `balasan sukses dibaca dari field result`() {
        val hasil = RelayClient.bacaBalasanCallable(
            200,
            """{"result":{"price":15000,"distanceKm":7.5}}"""
        ) as? Map<*, *>
        assertEquals(15000L, (hasil?.get("price") as Number).toLong())
        assertEquals(7.5, (hasil?.get("distanceKm") as Number).toDouble(), 0.0001)
    }

    @Test
    fun `balasan sukses berupa teks diteruskan apa adanya`() {
        // Ada callable yang mengembalikan String (mis. id order), bukan Map.
        assertEquals("order-123", RelayClient.bacaBalasanCallable(200, """{"result":"order-123"}"""))
    }

    @Test
    fun `balasan sukses tanpa result memberi null`() {
        assertNull(RelayClient.bacaBalasanCallable(200, """{"ok":true}"""))
    }

    @Test
    fun `balasan sukses memberi map bersarang yang bisa dibaca seperti map biasa`() {
        // JSONObject BUKAN Map — tanpa konversi, `voucher` selalu null dan quote
        // tampak "tidak valid" padahal server sukses.
        val hasil = RelayClient.bacaBalasanCallable(
            200,
            """{"result":{"price":15000,"voucher":{"code":"ABC","discount":2000}}}"""
        ) as? Map<*, *>
        val voucher = hasil?.get("voucher") as? Map<*, *>
        assertEquals("ABC", voucher?.get("code"))
        assertEquals(2000, (voucher?.get("discount") as Number).toInt())
    }

    @Test
    fun `error dari FUNGSI memakai pesan server dan ditandai bukan dariRelay`() {
        val e = ambil {
            RelayClient.bacaBalasanCallable(
                400,
                """{"error":{"status":"INVALID_ARGUMENT","message":"Kode tidak valid"}}"""
            )
        } as RelayClient.RelayCallException
        assertEquals("Kode tidak valid", e.message)
        assertFalse("pesan dari fungsi ⇒ dariRelay false", e.dariRelay)
    }

    @Test
    fun `alasan bisnis dari fungsi diteruskan lewat detail`() {
        // Penting: app driver memetakan penolakan (ORDER_ALREADY_TAKEN,
        // INSUFFICIENT_BALANCE, OUT_OF_RADIUS, DRIVER_BUSY) dari `details`.
        // Tanpa diteruskan, lewat jalur relay semua penolakan jadi "ERROR" biasa.
        val e = ambil {
            RelayClient.bacaBalasanCallable(
                400,
                """{"error":{"status":"FAILED_PRECONDITION","message":"Order sudah diambil",""" +
                    """"details":{"reason":"ORDER_ALREADY_TAKEN"}}}"""
            )
        } as RelayClient.RelayCallException
        assertFalse(e.dariRelay)
        assertTrue("detail harus memuat alasan bisnis", e.detail.contains("ORDER_ALREADY_TAKEN"))
    }

    @Test
    fun `penolakan relay sendiri tidak punya detail`() {
        val e = ambil {
            RelayClient.bacaBalasanCallable(404, """{"ok":false,"error":"fungsi tidak diizinkan"}""")
        } as RelayClient.RelayCallException
        assertEquals("", e.detail)
    }

    @Test
    fun `penolakan relay ditandai dariRelay`() {
        val e = ambil {
            RelayClient.bacaBalasanCallable(404, """{"ok":false,"error":"fungsi tidak diizinkan"}""")
        } as RelayClient.RelayCallException
        assertEquals(404, e.httpCode)
        assertTrue("penolakan relay ⇒ dariRelay true", e.dariRelay)
        assertEquals("fungsi tidak diizinkan", e.message)
    }

    @Test
    fun `balasan rusak ditandai dariRelay`() {
        val e = ambil {
            RelayClient.bacaBalasanCallable(200, "<html>bukan json</html>")
        } as RelayClient.RelayCallException
        assertTrue(e.dariRelay)
        assertEquals("Balasan server tidak terbaca", e.message)
    }

    // ── Penjaga anti-penggandaan tulis ──────────────────────────────────────
    // Hanya kegagalan yang PASTI belum sampai server yang boleh diulang untuk
    // callable yang menulis. Timeout statusnya tidak diketahui ⇒ jangan diulang.

    @Test
    fun `DNS gagal dianggap pasti belum sampai server`() {
        assertTrue(RelayClient.pastiBelumSampai(java.net.UnknownHostException("tidak bisa resolve")))
    }

    @Test
    fun `koneksi ditolak dianggap pasti belum sampai server`() {
        assertTrue(RelayClient.pastiBelumSampai(java.net.ConnectException("connection refused")))
    }

    @Test
    fun `timeout TIDAK dianggap aman diulang`() {
        assertFalse(RelayClient.pastiBelumSampai(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun `penyebab di balik pembungkus tetap terbaca`() {
        assertTrue(
            RelayClient.pastiBelumSampai(
                RuntimeException("gagal memanggil", java.net.ConnectException("refused"))
            )
        )
        assertFalse(
            RelayClient.pastiBelumSampai(
                RuntimeException("gagal memanggil", java.net.SocketTimeoutException("timeout"))
            )
        )
    }

    @Test
    fun `kegagalan yang tidak jelas TIDAK dianggap aman diulang`() {
        assertFalse(RelayClient.pastiBelumSampai(RuntimeException("ada yang aneh")))
    }

    // ── Callable yang aman diulang (penjaga regresi) ────────────────────────
    // OTP WAJIB boleh diulang lewat relay. Tanpa ini, operator yang memblokir
    // cloudfunctions.net dan gagal dengan TIMEOUT akan mengunci user di layar
    // verifikasi dengan pesan "Gagal mengirim kode verifikasi".

    @Test
    fun `OTP termasuk callable yang aman diulang lewat relay`() {
        assertTrue(CallableFallback.AMAN_DIULANG.contains("requestEmailOtp"))
        assertTrue(CallableFallback.AMAN_DIULANG.contains("verifyEmailOtp"))
    }

    @Test
    fun `callable baca termasuk daftar aman diulang`() {
        assertTrue(CallableFallback.AMAN_DIULANG.contains("quoteOrder"))
        assertTrue(CallableFallback.AMAN_DIULANG.contains("quoteFoodOrder"))
        assertTrue(CallableFallback.AMAN_DIULANG.contains("getReferralSummary"))
    }

    @Test
    fun `callable uang dan order TIDAK boleh diulang otomatis`() {
        val dilarang = listOf(
            "createOrder", "createFoodOrder", "payOrder", "payFoodOrder",
            "createBalanceTopup", "createOrReuseTripPayment", "requestWithdrawal",
            "transitionOrder", "completeOrder", "acceptOrder", "acceptFoodOrder",
            "redeemReferralCode", "redeemVoucherCode", "deleteMerchantAccount",
            "registerDeviceToken"
        )
        dilarang.forEach { nama ->
            assertFalse("$nama tidak boleh diulang otomatis", CallableFallback.AMAN_DIULANG.contains(nama))
        }
    }

    // ── Pemblokiran operator yang TIDAK berbentuk DNS-gagal ─────────────────
    // Kasus lapangan: phone bisa buka /health relay, tapi panggilan ke
    // cloudfunctions.net diputus di tengah jalan (connection reset/abort).
    // Dulu jenis ini lolos → relay tidak dicoba → "Gagal mengirim kode verifikasi".

    @Test
    fun `koneksi direset dianggap masalah jaringan`() {
        assertTrue(RelayClient.isNetworkError(java.net.SocketException("Connection reset by peer")))
    }

    @Test
    fun `pesan transport khas pemblokiran dianggap masalah jaringan`() {
        assertTrue(RelayClient.isNetworkError(RuntimeException("Software caused connection abort")))
        assertTrue(RelayClient.isNetworkError(RuntimeException("broken pipe")))
        assertTrue(RelayClient.isNetworkError(RuntimeException("connection closed by peer")))
    }

    @Test
    fun `tidak punya rute dianggap pasti belum sampai server`() {
        assertTrue(RelayClient.pastiBelumSampai(RuntimeException("Network is unreachable")))
        assertTrue(RelayClient.pastiBelumSampai(RuntimeException("No route to host")))
    }

    @Test
    fun `koneksi direset TIDAK dianggap pasti belum sampai server`() {
        // Reset bisa terjadi SETELAH permintaan terkirim ⇒ callable uang tetap dilarang diulang.
        assertFalse(RelayClient.pastiBelumSampai(java.net.SocketException("Connection reset")))
    }

    @Test
    fun `kode transport SDK bukan pesan bisnis server`() {
        listOf("INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED").forEach {
            assertFalse("$it = transport", RelayClient.kodePesanBisnis(it))
        }
        listOf(
            "FAILED_PRECONDITION", "INVALID_ARGUMENT", "RESOURCE_EXHAUSTED",
            "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND", "ALREADY_EXISTS"
        ).forEach { assertTrue("$it = server menjawab", RelayClient.kodePesanBisnis(it)) }
        assertFalse("null bukan pesan bisnis", RelayClient.kodePesanBisnis(null))
    }

    // ── Penjaga: relay tidak boleh dipanggil dari thread utama ────────────────
    // Bug nyata (3 Okt 2026): OTP gagal dengan "Jalur cadangan tidak terjangkau".
    // Sebabnya RelayClient.callable() — HttpURLConnection, BLOKIRAN — dipanggil dari
    // viewModelScope (Dispatchers.Main) ⇒ Android melempar NetworkOnMainThreadException
    // SEBELUM permintaan keluar. Callable lewat shim Fungsi aman karena shim sudah
    // memakai Dispatchers.IO; yang bocor hanya jalur panggilan langsung (OTP).

    @Test
    fun `relay selalu dipanggil dari dispatcher IO`() {
        val sumber = bacaSumber("src/main/java/com/suruhaja/data/relay/CallableFallback.kt")
        assertTrue(
            "CallableFallback.call WAJIB dibungkus withContext(Dispatchers.IO) supaya " +
                "tidak kena NetworkOnMainThreadException",
            sumber.contains("withContext(Dispatchers.IO)")
        )
    }

    private fun bacaSumber(rel: String): String {
        var d: java.io.File? = java.io.File(".").absoluteFile
        while (d != null) {
            val f = java.io.File(d, rel)
            if (f.exists()) return f.readText()
            d = d.parentFile
        }
        error("tidak menemukan $rel")
    }

    private fun ambil(blok: () -> Any?): Exception = try {
        blok()
        error("seharusnya melempar")
    } catch (e: Exception) {
        e
    }
}
