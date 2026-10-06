package com.suruhaja.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mengunci aturan perpindahan jalur: 3 gagal ⇒ cadangan, 3 sukses ⇒ normal. */
class RelayModePolicyTest {

    @Test
    fun `dua kegagalan belum memindahkan jalur`() {
        val p = RelayModePolicy()
        p.catatGagalJaringan()
        p.catatGagalJaringan()
        assertFalse("2 gagal seharusnya belum beralih", p.modeCadangan)
    }

    @Test
    fun `tiga kegagalan mengaktifkan mode cadangan`() {
        val p = RelayModePolicy()
        repeat(3) { p.catatGagalJaringan() }
        assertTrue("3 gagal harus beralih ke relay", p.modeCadangan)
    }

    @Test
    fun `sukses langsung memutus rantai kegagalan`() {
        val p = RelayModePolicy()
        p.catatGagalJaringan()
        p.catatGagalJaringan()
        p.catatSuksesLangsung()
        p.catatGagalJaringan()
        p.catatGagalJaringan()
        assertFalse("gagal harus berturut-turut, bukan akumulasi", p.modeCadangan)
    }

    @Test
    fun `tiga sukses berturut mengembalikan ke jalur normal`() {
        val p = RelayModePolicy()
        repeat(3) { p.catatGagalJaringan() }
        assertTrue(p.modeCadangan)
        repeat(3) { p.catatSuksesLangsung() }
        assertFalse("3 sukses berturut harus kembali normal", p.modeCadangan)
    }

    @Test
    fun `reset mengembalikan keadaan awal`() {
        val p = RelayModePolicy()
        repeat(5) { p.catatGagalJaringan() }
        p.reset()
        assertFalse(p.modeCadangan)
    }
}
