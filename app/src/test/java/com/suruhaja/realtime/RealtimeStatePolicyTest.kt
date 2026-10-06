package com.suruhaja.realtime

import org.junit.Assert.*
import org.junit.Test

class RealtimeStatePolicyTest {
    @Test fun `cache snapshot keeps only previous server data and marks reconnecting`() {
        val state = RealtimeStatePolicy.accept(previous = "order", value = "new", fromCache = true)
        assertEquals("order", state.data)
        assertTrue(state.reconnecting)
        assertFalse(state.loading)
    }

    @Test fun `cache cannot initialize correctness critical order`() {
        val state = RealtimeStatePolicy.accept(previous = null, value = "cached", fromCache = true)
        assertNull(state.data)
        assertTrue(state.loading)
        assertTrue(state.canRetry)
    }

    @Test fun `listener error keeps last known data and exposes retry`() {
        val state = RealtimeStatePolicy.failure(previous = "order", message = "offline")
        assertEquals("order", state.data)
        assertEquals("offline", state.error)
        assertTrue(state.canRetry)
        assertFalse(state.loading)
    }

    @Test fun `paid or terminal order never asks for payment`() {
        assertFalse(RealtimeStatePolicy.showPaymentPrompt("saldo", awaiting = true, paymentStatus = "paid", terminal = false))
        assertFalse(RealtimeStatePolicy.showPaymentPrompt("saldo", awaiting = true, paymentStatus = "unpaid", terminal = true))
    }

    @Test fun `payment is pending only for a requested saldo order that is still unpaid`() {
        assertTrue(RealtimeStatePolicy.paymentPending("saldo", awaiting = true, paymentStatus = "unpaid", terminal = false))
        assertFalse(RealtimeStatePolicy.paymentPending("saldo", awaiting = false, paymentStatus = "unpaid", terminal = false))
        assertFalse(RealtimeStatePolicy.paymentPending("cash", awaiting = true, paymentStatus = "unpaid", terminal = false))
        assertFalse(RealtimeStatePolicy.paymentPending("saldo", awaiting = true, paymentStatus = "paid", terminal = false))
        assertFalse(RealtimeStatePolicy.paymentPending("saldo", awaiting = true, paymentStatus = "unpaid", terminal = true))
    }

    /**
     * Regresi: customer menutup dialog pembayaran (tombol back / swipe) saat
     * driver sudah minta bayar. Dialog tidak boleh muncul otomatis lagi, TAPI
     * tombol "Bayar sekarang" harus tetap ada supaya customer tidak stuck.
     */
    @Test fun `dismissed payment dialog keeps a manual entry point`() {
        val decision = RealtimeStatePolicy.paymentPromptDecision(pending = true, alreadyPrompted = true)
        assertFalse("dialog tidak boleh muncul otomatis dua kali", decision.autoShow)
        assertTrue("tombol bayar harus tetap tampil", decision.buttonVisible)
        assertTrue("permintaan masih dianggap sudah ditampilkan", decision.markPrompted)
    }

    @Test fun `fresh payment request prompts once and shows the button`() {
        val decision = RealtimeStatePolicy.paymentPromptDecision(pending = true, alreadyPrompted = false)
        assertTrue(decision.autoShow)
        assertTrue(decision.buttonVisible)
    }

    @Test fun `a new request episode after payment is settled prompts again`() {
        val cleared = RealtimeStatePolicy.paymentPromptDecision(pending = false, alreadyPrompted = true)
        assertFalse(cleared.autoShow)
        assertFalse(cleared.buttonVisible)
        assertFalse("flag harus direset supaya permintaan berikutnya muncul lagi", cleared.markPrompted)

        val next = RealtimeStatePolicy.paymentPromptDecision(pending = true, alreadyPrompted = cleared.markPrompted)
        assertTrue(next.autoShow)
    }
}
