package com.suruhaja.data.relay

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Membuktikan PERILAKU jalur cadangan (bukan cuma isi daftar).
 *
 * Kasus nyata yang pernah menggigit: operator memblokir cloudfunctions.net dan
 * kegagalannya berupa TIMEOUT ⇒ dulu relay TIDAK dicoba untuk OTP sehingga user
 * terkunci di layar verifikasi dengan "Gagal mengirim kode verifikasi".
 * Sekarang: OTP tetap dicoba lewat relay; callable uang/order tetap TIDAK diulang.
 *
 * Relay dipalsukan dengan server HTTP lokal, dan jalur langsung disuntik supaya
 * gagal dengan jenis kegagalan yang ditentukan tes.
 */
class CallableFallbackRelayTest {

    private lateinit var server: HttpServer
    private val hit = AtomicInteger(0)
    private var baseLama: String = ""
    private var jalurLama: (suspend (String, Any?) -> Any?)? = null

    @Before
    fun siapkan() {
        hit.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/call/") { ex ->
            hit.incrementAndGet()
            val body = """{"result":{"ok":true}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        baseLama = RelayClient.baseUrl
        RelayClient.baseUrl = "http://127.0.0.1:" + server.address.port
        jalurLama = CallableFallback.panggilLangsung
        // Jenis kegagalan yang statusnya TIDAK diketahui (bisa sudah sampai server).
        CallableFallback.panggilLangsung = { _, _ -> throw SocketTimeoutException("timeout") }
    }

    @After
    fun bersihkan() {
        CallableFallback.panggilLangsung = jalurLama!!
        RelayClient.baseUrl = baseLama
        server.stop(0)
    }

    @Test
    fun `OTP tetap dicoba lewat relay walau jalur langsung timeout`() = runBlocking {
        CallableFallback.call("requestEmailOtp", mapOf("paksa" to true))
        assertEquals("relay harus dicoba untuk OTP", 1, hit.get())
    }

    @Test
    fun `verifikasi OTP juga dicoba lewat relay saat timeout`() = runBlocking {
        CallableFallback.call("verifyEmailOtp", mapOf("code" to "123456"))
        assertEquals(1, hit.get())
    }

    @Test
    fun `callable uang TIDAK diulang lewat relay saat timeout`() = runBlocking {
        val e = try {
            CallableFallback.call("payOrder", mapOf("orderId" to "x"))
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue("harus melempar error asli", e is SocketTimeoutException)
        assertEquals("relay TIDAK boleh dicoba untuk callable uang", 0, hit.get())
    }

    @Test
    fun `kegagalan DNS tetap membuka jalur relay untuk callable uang`() = runBlocking {
        CallableFallback.panggilLangsung = { _, _ -> throw UnknownHostException("diblokir") }
        CallableFallback.call("payOrder", emptyMap<String, Any?>())
        assertEquals("DNS gagal = pasti belum sampai server", 1, hit.get())
    }

    @Test
    fun `error jenis tak dikenal tetap membuka relay untuk OTP`() = runBlocking {
        CallableFallback.panggilLangsung = { _, _ -> throw IllegalStateException("koneksi putus entah kenapa") }
        CallableFallback.call("requestEmailOtp", emptyMap<String, Any?>())
        assertEquals("OTP aman diulang walau jenis errornya belum dikenal", 1, hit.get())
    }

    @Test
    fun `error jenis tak dikenal TIDAK membuka relay untuk callable uang`() = runBlocking {
        CallableFallback.panggilLangsung = { _, _ -> throw IllegalStateException("entah apa") }
        val e = try {
            CallableFallback.call("payOrder", emptyMap<String, Any?>())
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue(e is IllegalStateException)
        assertEquals("uang tetap dilarang diulang otomatis", 0, hit.get())
    }
}
