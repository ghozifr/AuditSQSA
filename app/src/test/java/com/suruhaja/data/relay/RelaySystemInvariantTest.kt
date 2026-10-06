package com.suruhaja.data.relay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Penjaga invarian SISTEM RELAY — bukan cuma satu fungsi.
 *
 * Memastikan semua callable di app ini tetap lewat SATU pintu (shim `Fungsi` /
 * `CallableFallback`) sehingga jalur cadangan (relay) selalu tersedia, dan tidak ada
 * yang diam-diam memakai SDK langsung (tanpa jalur cadangan) atau memakai callable baru
 * yang belum terdaftar di allowlist relay — kasus terakhir ini pernah membuat driver
 * tidak bisa menerima order di operator pemblokir.
 */
class RelaySystemInvariantTest {

    /** Harus SAMA dengan callAllowlist relay. Penjaga lintas-repo:
     *  audit-scripts/check-relay-allowlist.py (jalankan sebelum rilis). */
    private val ALLOWLIST_RELAY = setOf(
        "requestEmailOtp",
        "verifyEmailOtp",
        "quoteOrder",
        "quoteFoodOrder",
        "getReferralSummary",
        "redeemReferralCode",
        "redeemVoucherCode",
        "createOrder",
        "createFoodOrder",
        "payOrder",
        "payFoodOrder",
        "registerDeviceToken",
        "createBalanceTopup",
        "transitionOrder",
        "createOrReuseTripPayment",
        "requestWithdrawal",
        "acceptOrder",
        "acceptFoodOrder",
        "cancelUnacceptedFoodOrdersForMerchant",
        "deleteMerchantAccount",
        "completeOrder",
        "completeFoodOrder"
    )

    private val akarPaket = "src/main/java/com/suruhaja"
    private val paketRelay = "data/relay"

    private fun akarPulang(): File {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val f = File(d, akarPaket)
            if (f.exists()) return f
            d = d.parentFile
        }
        error("tidak menemukan $akarPaket")
    }

    private fun berkasKotlin(): List<File> =
        akarPulang().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `callable tidak boleh memakai SDK langsung di luar paket relay`() {
        val pelanggar = berkasKotlin()
            .filter { !it.path.replace('\\', '/').contains(paketRelay) }
            .filter { it.readText().contains("FirebaseFunctions.getInstance") }
            .map { it.name }
        assertTrue(
            "Pemakaian SDK langsung (tanpa jalur cadangan) di: $pelanggar — " +
                "pakai Fungsi.getInstance(...) atau CallableFallback.call(...).",
            pelanggar.isEmpty()
        )
    }

    @Test
    fun `semua callable yang dipakai app ada di allowlist relay`() {
        val pola = Regex("""getHttpsCallable\("([A-Za-z0-9_]+)"""")
        val dipakai = berkasKotlin()
            // paket relay hanya infrastruktur (shim/relay client) — contoh di komentarnya
            // memakai nama palsu seperti "x", bukan callable sungguhan.
            .filter { !it.path.replace('\\', '/').contains(paketRelay) }
            .flatMap { pola.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        assertTrue("harus menemukan callable — pola pemindaian rusak?", dipakai.isNotEmpty())
        val terlewat = dipakai - ALLOWLIST_RELAY
        assertTrue("Callable belum terdaftar di relay (jalur cadangan 404): $terlewat", terlewat.isEmpty())
    }

    @Test
    fun `nama callable dinamis (lewat variabel) tidak luput`() {
        val pola = Regex("""acceptViaFunction\("([A-Za-z0-9_]+)"""")
        val dipakai = berkasKotlin()
            // paket relay hanya infrastruktur (shim/relay client) — contoh di komentarnya
            // memakai nama palsu seperti "x", bukan callable sungguhan.
            .filter { !it.path.replace('\\', '/').contains(paketRelay) }
            .flatMap { pola.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        val terlewat = dipakai - ALLOWLIST_RELAY
        assertTrue("Nama callable dinamis belum ada di allowlist relay: $terlewat", terlewat.isEmpty())
    }

    @Test
    fun `relay selalu dijalankan di dispatcher IO`() {
        val sumber = berkasKotlin().first { it.name == "CallableFallback.kt" }.readText()
        assertTrue(
            "CallableFallback.call wajib withContext(Dispatchers.IO) — kalau tidak, " +
                "NetworkOnMainThreadException muncul sebagai 'Jalur cadangan tidak terjangkau'",
            sumber.contains("withContext(Dispatchers.IO)")
        )
    }
}
