package com.suruhaja.data.relay

/**
 * Penentu kapan klien beralih ke jalur cadangan (relay) dan kapan kembali ke jalur
 * langsung ke Firebase.
 *
 * Sengaja MURNI LOGIKA: tanpa Android, tanpa jaringan — supaya bisa diuji unit.
 *
 * Aturan: 3 kegagalan jaringan berturut-turut ⇒ modeCadangan aktif.
 *         3 keberhasilan langsung berturut-turut ⇒ kembali normal.
 * Hanya kegagalan JARINGAN yang dihitung (lihat RelayClient.isNetworkError).
 */
class RelayModePolicy(
    private val gagalUntukAktif: Int = 3,
    private val suksesUntukKembali: Int = 3,
) {

    @Volatile
    var modeCadangan: Boolean = false
        private set

    private var gagal = 0
    private var sukses = 0

    @Synchronized
    fun catatGagalJaringan() {
        sukses = 0
        gagal++
        if (gagal >= gagalUntukAktif) modeCadangan = true
    }

    @Synchronized
    fun catatSuksesLangsung() {
        gagal = 0
        sukses++
        if (sukses >= suksesUntukKembali) modeCadangan = false
    }

    @Synchronized
    fun reset() {
        gagal = 0
        sukses = 0
        modeCadangan = false
    }
}
