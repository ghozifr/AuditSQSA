package com.suruhaja.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FoodDriverFirstLifecycleTest {
    @Test
    fun `new food order starts by seeking driver`() {
        assertEquals(FoodOrder.STATUS_SEEKING_DRIVER, FoodOrder().status)
        assertEquals("Mencari Driver...", FoodOrder.statusText(FoodOrder.STATUS_SEEKING_DRIVER))
    }

    @Test
    fun `driver acceptance waits for merchant before preparation`() {
        assertEquals(
            "Driver ditemukan, menunggu konfirmasi toko",
            FoodOrder.statusText(FoodOrder.STATUS_ACCEPTED)
        )
        assertEquals("Pesanan dimasak", FoodOrder.statusText(FoodOrder.STATUS_PREPARING))
    }
}
