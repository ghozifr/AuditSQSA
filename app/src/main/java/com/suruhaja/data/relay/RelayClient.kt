package com.suruhaja.data.relay

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Klien jalur cadangan (relay) — dipakai HANYA kalau perangkat tidak bisa mencapai
 * Google langsung (kasus operator by.U/Telkomsel).
 *
 * Relay: https://relay.suruhaja.my.id (laptop rumah lewat Cloudflare Tunnel).
 * Sifatnya BACA-SAJA + login/daftar; semua penulisan tetap ke Firebase.
 *
 * PENTING: semua fungsi di sini BLOKIRAN — panggil dari thread IO/dispatcher.
 */
object RelayClient {

    const val BASE = "https://relay.suruhaja.my.id"
    // 20 dtk (dulu 6, lalu 12): jaringan seluler (mis. by.U) sering lambat membangun
    // koneksi TLS ke Cloudflare; batas pendek membuat relay seolah tidak terjangkau —
    // relay jadi tidak pernah terpakai walau browsernya bisa membuka alamat relay.
    private const val TIMEOUT_MS = 20000
    private const val PREFS = "suruhaja_relay_session"

    val policy = RelayModePolicy()
    val modeCadangan: Boolean get() = policy.modeCadangan

    /** URL relay yang bisa ditimpa saat pengujian (mis. arahkan ke alamat lain). */
    @Volatile
    var baseUrl: String = BASE

    /**
     * Kegagalan yang menandakan MASALAH JARINGAN (bukan izin/bug) — hanya ini yang
     * boleh memicu mode cadangan.
     */
    fun isNetworkError(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 6) {
            when (e) {
                // Semua kegagalan transport: DNS gagal, koneksi ditolak / habis waktu,
                // DAN koneksi di-reset / diputus di tengah jalan — bentuk yang khas
                // dipakai operator untuk memblokir (dulu jenis ini LOLOS, relay tak dicoba).
                is UnknownHostException, is ConnectException, is SocketTimeoutException,
                is SocketException -> return true
                is FirebaseFirestoreException -> {
                    if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                        e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
                    ) return true
                }
                is FirebaseFunctionsException -> {
                    // SDK memetakan kegagalan jaringan jadi INTERNAL. Hanya INTERNAL
                    // yang pesannya kosong/"internal" yang dianggap masalah jaringan —
                    // pesan asli dari server jangan ikut dialihkan ke relay.
                    val p = e.message?.trim()?.lowercase().orEmpty()
                    val sentinel = p.isEmpty() || p == "internal"
                    if (e.code == FirebaseFunctionsException.Code.INTERNAL && sentinel) return true
                    if (e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                        e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
                    ) return true
                }
            }
            val m = e.message?.lowercase().orEmpty()
            if (m.contains("network") || m.contains("unable to resolve host") ||
                m.contains("failed to connect") || m.contains("timeout") ||
                m.contains("connection reset") || m.contains("connection abort") ||
                m.contains("broken pipe") || m.contains("connection closed") ||
                m.contains("no route to host") || m.contains("unexpected end of stream")
            ) return true
            e = e.cause
            depth++
        }
        return false
    }

    /** Catat kegagalan/keberhasilan jalur langsung (menggerakkan histeresis). */
    /**
     * Kegagalan yang PASTI belum sampai ke server ⇒ aman diulang.
     *
     * Bedanya dengan isNetworkError: TIMEOUT / DEADLINE_EXCEEDED TIDAK dianggap aman,
     * karena permintaan bisa saja sudah dieksekusi server. Dipakai untuk menahan
     * pengulangan callable yang MENULIS (anti dobel order / dobel topup / dobel QRIS).
     */
    fun pastiBelumSampai(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 6) {
            when (e) {
                // DNS gagal / koneksi ditolak ⇒ permintaan tidak pernah terkirim.
                is UnknownHostException, is ConnectException -> return true
                // Habis waktu ⇒ status tidak diketahui, anggap belum aman.
                is SocketTimeoutException -> return false
                is FirebaseFunctionsException -> {
                    val p = e.message?.trim()?.lowercase().orEmpty()
                    val sentinel = p.isEmpty() || p == "internal"
                    if (e.code == FirebaseFunctionsException.Code.INTERNAL && sentinel) return true
                    if (e.code == FirebaseFunctionsException.Code.UNAVAILABLE) return true
                    if (e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED) return false
                }
                is FirebaseFirestoreException -> {
                    when (e.code) {
                        FirebaseFirestoreException.Code.UNAVAILABLE -> return true
                        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> return false
                        else -> Unit
                    }
                }
            }
            val m = e.message?.lowercase().orEmpty()
            // Paket TIDAK mungkin terkirim: nama tak bisa di-resolve, koneksi ditolak,
            // atau OS memang tidak punya rute sama sekali.
            if (m.contains("unable to resolve host") || m.contains("failed to connect") ||
                m.contains("no route to host") || m.contains("network is unreachable")
            ) return true
            if (m.contains("timeout") || m.contains("deadline")) return false
            e = e.cause
            depth++
        }
        return false
    }

    /**
     * Apakah error ini BALASAN SUNGGUHAN dari Cloud Function?
     *
     * Dipakai callable yang aman diulang (baca + OTP): kalau server sudah menjawab —
     * termasuk penolakan ber-alasan — mengulang lewat relay tidak mengubah jawabannya.
     * Sebaliknya, error apa pun yang BUKAN balasan server (masalah jaringan, koneksi
     * di-reset, bahkan jenis error yang belum pernah kita lihat) tetap layak dicoba
     * lewat relay. Ini menghapus ketergantungan pada tebakan jenis error operator.
     */
    internal fun balasanServerSungguhan(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 6) {
            if (e is FirebaseFunctionsException) return kodePesanBisnis(e.code.name)
            e = e.cause
            depth++
        }
        return false
    }

    /** Tiga kode ini milik SDK untuk masalah TRANSPORT, bukan pesan bisnis server. */
    internal fun kodePesanBisnis(kode: String?): Boolean =
        kode != null && kode.uppercase() !in setOf("INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED")

    fun catatGagalJaringan() = policy.catatGagalJaringan()
    fun catatSuksesLangsung() = policy.catatSuksesLangsung()

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
    ): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("accept", "application/json")
                if (token != null) setRequestProperty("authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, "UTF-8")).use { r -> r.readText() }
            }.orEmpty()
            if (text.isBlank()) JSONObject().put("ok", code in 200..299).put("httpCode", code)
            else JSONObject(text).put("httpCode", code)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun health(): Boolean = request("GET", "/health")?.optBoolean("ok") == true

    /** config/discovery (radius) + config/versioning (gate versi). */
    fun config(): JSONObject? = request("GET", "/config")?.takeIf { it.optBoolean("ok") }

    fun login(email: String, password: String): JSONObject? =
        request(
            "POST", "/auth/login",
            JSONObject().put("email", email).put("password", password)
        )

    fun register(email: String, password: String): JSONObject? =
        request(
            "POST", "/auth/register",
            JSONObject().put("email", email).put("password", password)
        )

    fun refresh(refreshToken: String): JSONObject? =
        request("POST", "/auth/refresh", JSONObject().put("refreshToken", refreshToken))

    fun me(idToken: String): JSONObject? = request("GET", "/me", token = idToken)

    // ── Konteks aplikasi (di-set dari MainActivity) untuk simpan/ambil sesi ──
    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    // ── Sesi relay (dipakai saat login lewat relay) ─────────────────────────

    data class Sesi(
        val uid: String,
        val idToken: String,
        val refreshToken: String,
        val expiresAt: Long,
    )

    fun simpanSesi(ctx: Context, sesi: Sesi) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uid", sesi.uid)
            .putString("id_token", sesi.idToken)
            .putString("refresh_token", sesi.refreshToken)
            .putLong("expires_at", sesi.expiresAt)
            .apply()
    }

    fun ambilSesi(ctx: Context): Sesi? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uid = p.getString("uid", null) ?: return null
        val id = p.getString("id_token", null) ?: return null
        val rt = p.getString("refresh_token", null) ?: return null
        return Sesi(uid, id, rt, p.getLong("expires_at", 0L))
    }

    fun hapusSesi(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Perbarui idToken lewat relay. Return sesi baru, atau null kalau gagal. */
    fun perbaruiSesi(ctx: Context): Sesi? {
        val lama = ambilSesi(ctx) ?: return null
        val r = refresh(lama.refreshToken) ?: return null
        if (!r.optBoolean("ok")) {
            hapusSesi(ctx)
            return null
        }
        val baru = Sesi(
            uid = r.optString("uid", lama.uid),
            idToken = r.optString("idToken"),
            refreshToken = r.optString("refreshToken", lama.refreshToken),
            expiresAt = System.currentTimeMillis() + r.optLong("expiresIn", 3600L) * 1000L,
        )
        simpanSesi(ctx, baru)
        return baru
    }

    /** idToken siap pakai (diperbarui kalau tinggal < 5 menit). */
    fun idTokenSiap(ctx: Context): String? {
        val s = ambilSesi(ctx) ?: return null
        if (s.expiresAt - System.currentTimeMillis() > 5 * 60_000L) return s.idToken
        return perbaruiSesi(ctx)?.idToken
    }

    /** Versi tanpa Context: pakai appCtx hasil init() dari MainActivity. */
    fun simpanSesi(sesi: Sesi) {
        appCtx?.let { simpanSesi(it, sesi) }
    }

    fun ambilSesi(): Sesi? = appCtx?.let { ambilSesi(it) }

    fun hapusSesi() {
        appCtx?.let { hapusSesi(it) }
    }

    fun perbaruiSesi(): Sesi? = appCtx?.let { perbaruiSesi(it) }

    fun idTokenSiap(): String? = appCtx?.let { idTokenSiap(it) }

    /** Dokumen milik pemanggil (READ): pakai path "/driver" atau "/merchant". */
    fun dokumenSendiri(path: String, idToken: String): JSONObject? =
        request("GET", path, token = idToken)

    /** Bangun objek Sesi dari respons /auth/login atau /auth/register. */
    fun sesiDariRespons(r: JSONObject): Sesi? {
        if (!r.optBoolean("ok")) return null
        val id = r.optString("idToken")
        if (id.isBlank()) return null
        return Sesi(
            uid = r.optString("uid"),
            idToken = id,
            refreshToken = r.optString("refreshToken"),
            expiresAt = System.currentTimeMillis() + r.optLong("expiresIn", 3600L) * 1000L,
        )
    }

// ── Jalur cadangan callable (relay) ─────────────────────────────────────
    // Dipakai HANYA kalau perangkat tak bisa menembak cloudfunctions.net sendiri
    // (mis. operator by.U). Isi payload & token MILIK USER diteruskan apa adanya.

    /** Callable bisa lebih lambat dari endpoint lain; relay sendiri menyerah di 25 dtk. */
    private const val TIMEOUT_CALL_MS = 28000

    /**
     * Kegagalan jalur cadangan. `dariRelay = true` berarti yang gagal adalah
     * relay/transport (bukan balasan resmi Cloud Function) — pemanggil memakai
     * penanda itu untuk menampilkan error jaringan yang asli.
     */
    class RelayCallException(
        val httpCode: Int,
        val dariRelay: Boolean,
        message: String,
        /**
         * Isi `error.details` dari Cloud Function (kalau ada). Dipakai pemanggil
         * untuk mengenali alasan BISNIS, mis. ORDER_ALREADY_TAKEN /
         * INSUFFICIENT_BALANCE — tanpa ini, lewat jalur relay semua penolakan
         * tampak sebagai "ERROR" biasa.
         */
        val detail: String = "",
    ) : Exception(message)

    /** Ubah nilai Kotlin apa pun jadi bentuk JSON yang aman di semua versi Android. */
    fun keJson(nilai: Any?): Any = when (nilai) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o ->
            nilai.forEach { (k, v) -> o.put(k.toString(), keJson(v)) }
        }
        is Iterable<*> -> JSONArray().also { a -> nilai.forEach { v -> a.put(keJson(v)) } }
        is Array<*> -> JSONArray().also { a -> nilai.forEach { v -> a.put(keJson(v)) } }
        else -> nilai
    }

    /** Body callable: {"data": {...}} — bentuk yang diterima Cloud Functions. */
    fun bodyCallable(payload: Any?): JSONObject =
        JSONObject().put("data", keJson(payload ?: emptyMap<String, Any?>()))

    /**
     * Balasan JSON ⇒ Map/List biasa. Perlu, karena `JSONObject` BUKAN `Map` —
     * tanpa ini `data["voucher"] as? Map<*, *>` selalu null.
     */
    fun kePeta(nilai: Any?): Any? = when (nilai) {
        is JSONObject -> nilai.keys().asSequence().associateWith { kePeta(nilai.opt(it)) }
        is JSONArray -> (0 until nilai.length()).map { kePeta(nilai.opt(it)) }
        JSONObject.NULL -> null
        else -> nilai
    }

    /**
     * Baca balasan relay: 2xx ⇒ data; selain itu ⇒ RelayCallException.
     * `dariRelay=true` kalau yang menolak relay (bukan fungsi).
     */
    fun bacaBalasanCallable(httpCode: Int, body: String): Any? {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RelayCallException(httpCode, true, "Balasan server tidak terbaca")
        }
        if (httpCode in 200..299) return kePeta(json.opt("result"))
        val errObj = json.optJSONObject("error")
        if (errObj != null) {
            val pesan = errObj.optString("message").ifBlank { "Gagal menghubungi server ($httpCode)" }
            val detail = errObj.opt("details")
                ?.let { if (it == JSONObject.NULL) "" else it.toString() }
                .orEmpty()
            throw RelayCallException(httpCode, false, pesan, detail)
        }
        throw RelayCallException(
            httpCode,
            true,
            json.optString("error").ifBlank { "Jalur cadangan gagal ($httpCode)" },
        )
    }

    /** Jenis exception transport terakhir dari jalur relay (untuk laporan galat). */
    @Volatile
    private var sebabTransport: String = ""

    /** POST yang mengembalikan (kode, body mentah) — dipakai jalur callable. */
    private fun postMentah(
        path: String,
        body: String,
        token: String?,
        timeoutMs: Int,
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("accept", "application/json")
                setRequestProperty("content-type", "application/json")
                if (token != null) setRequestProperty("authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, "UTF-8")).use { r -> r.readText() }
            }.orEmpty()
            code to text
        } catch (e: Exception) {
            // Jenis kegagalannya disimpan, bukan ditelan: tanpa ini kita tidak bisa
            // membedakan DNS gagal / habis waktu / TLS ditolak saat dilaporkan ke user.
            sebabTransport = e.javaClass.simpleName
            -1 to ""
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Panggil `nama` lewat relay. Return data mentah (bisa null), lempar
     * RelayCallException kalau relay tak terjangkau / fungsi membalas error.
     */
    fun callable(nama: String, payload: Any?, idToken: String?): Any? {
        val (code, body) = postMentah(
            "/call/$nama",
            bodyCallable(payload).toString(),
            idToken,
            TIMEOUT_CALL_MS,
        )
        if (code == -1) {
            val sebab = if (sebabTransport.isNotBlank()) " ($sebabTransport)" else ""
            throw RelayCallException(-1, true, "Jalur cadangan tidak terjangkau$sebab")
        }
        return bacaBalasanCallable(code, body)
    }
}
