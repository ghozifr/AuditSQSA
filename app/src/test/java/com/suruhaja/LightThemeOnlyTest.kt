package com.suruhaja

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Penjaga: aplikasi customer HANYA boleh tampil terang (permintaan 2026-09-24).
 *
 * Kalau salah satu tes di bawah gagal, berarti seseorang membuka jalan kembali
 * ke tema gelap. Kalau itu memang disengaja, urutannya:
 *   1. ThemeManager.DARK_THEME_SUPPORTED = true
 *   2. hapus android:visibility="gone" dari btn_theme_toggle di fragment_profile.xml
 *   3. pasang kembali wiring switch_theme di ProfileFragment
 *   4. kembalikan folder res/values-night + res/drawable-night dari git
 *      (backup: audit-scripts/backups/night-resources-20260924/)
 * lalu perbarui tes ini.
 */
class LightThemeOnlyTest {

    /** Cari app/src/main dengan menelusuri ke atas dari working directory tes. */
    private val mainDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "app/src/main").takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, "src/main").takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("app/src/main tidak ditemukan dari ${File("").absolutePath}")
    }

    @Test
    fun `tidak ada resource khusus mode gelap`() {
        val resDir = File(mainDir, "res")
        assertTrue("res/ tidak ditemukan di $resDir", resDir.isDirectory)

        val nightDirs = resDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.contains("-night") }
            .map { it.name }

        assertTrue(
            "Resource mode gelap masih ada: $nightDirs. Selama tema gelap dimatikan, " +
                "folder *-night harus tidak ada supaya warna gelap tidak bisa bocor " +
                "(termasuk layar splash saat app dibuka di device gelap).",
            nightDirs.isEmpty()
        )
    }

    @Test
    fun `ThemeManager memaksa mode terang`() {
        val source = File(mainDir, "java/com/suruhaja/util/ThemeManager.kt")
        assertTrue("ThemeManager.kt tidak ditemukan di $source", source.isFile)
        val text = source.readText()

        assertTrue(
            "DARK_THEME_SUPPORTED harus false selama tema gelap dimatikan",
            Regex("""DARK_THEME_SUPPORTED\s*=\s*false""").containsMatchIn(text)
        )
        assertTrue(
            "apply() harus memakai MODE_NIGHT_NO saat tema gelap dimatikan",
            text.contains("AppCompatDelegate.MODE_NIGHT_NO")
        )
        assertTrue(
            "apply() harus memaksa MODE_NIGHT_NO di jalur pertama",
            Regex("""if \(!DARK_THEME_SUPPORTED\)[\s\S]{0,120}MODE_NIGHT_NO""").containsMatchIn(text)
        )
    }

    @Test
    fun `saklar tema di profil disembunyikan dan tidak di-wire`() {
        val layout = File(mainDir, "res/layout/fragment_profile.xml").readText()
        val row = Regex("""<LinearLayout android:id="@\+id/btn_theme_toggle"[^>]*""").find(layout)?.value
        assertTrue("baris btn_theme_toggle tidak ditemukan di fragment_profile.xml", row != null)
        assertTrue(
            "btn_theme_toggle harus android:visibility=\"gone\" (disembunyikan sementara)",
            row!!.contains("""android:visibility="gone"""")
        )

        val fragment = File(mainDir, "java/com/suruhaja/ui/profile/ProfileFragment.kt").readText()
        assertFalse(
            "ProfileFragment masih menyetel switch_theme — hapus wiring-nya",
            fragment.contains("binding.switchTheme.isChecked") ||
                fragment.contains("binding.switchTheme.setOnCheckedChangeListener")
        )
    }

    @Test
    fun `paksa terang diterapkan saat aplikasi dibuka`() {
        val app = File(mainDir, "java/com/suruhaja/SuruhajaApp.kt").readText()
        assertTrue(
            "SuruhajaApp.onCreate harus memanggil ThemeManager.apply(this) supaya " +
                "mode terang berlaku sejak aplikasi dibuka",
            Regex("""ThemeManager\.apply\(this\)""").containsMatchIn(app)
        )
    }
}
