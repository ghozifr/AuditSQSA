package com.suruhaja.data.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpInputPolicyTest {

    @Test
    fun `kode valid hanya kalau tepat 6 digit`() {
        assertTrue(OtpInputPolicy.isValidCode("123457"))
        assertTrue(OtpInputPolicy.isValidCode(" 123457 "))
        assertFalse(OtpInputPolicy.isValidCode("12345"))
        assertFalse(OtpInputPolicy.isValidCode("1234567"))
        assertFalse(OtpInputPolicy.isValidCode("12345a"))
        assertFalse(OtpInputPolicy.isValidCode(""))
        assertFalse(OtpInputPolicy.isValidCode(null))
    }

    @Test
    fun `normalize membuang karakter bukan angka dan memotong 6 digit`() {
        assertEquals("123457", OtpInputPolicy.normalize(" 12-34 57 "))
        assertEquals("123457", OtpInputPolicy.normalize("1234579999"))
        assertEquals("", OtpInputPolicy.normalize("abc"))
        assertEquals("", OtpInputPolicy.normalize(null))
    }

    @Test
    fun `label kirim ulang menampilkan hitungan mundur`() {
        assertEquals("Kirim ulang kode", OtpInputPolicy.resendLabel(0))
        assertEquals("Kirim ulang kode", OtpInputPolicy.resendLabel(-3))
        assertEquals("Kirim ulang kode (42s)", OtpInputPolicy.resendLabel(42))
    }
}
