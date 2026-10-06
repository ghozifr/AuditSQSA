package com.suruhaja.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Utilitas QRIS — port dari lib qris-dinamis (verssache/qris-dinamis).
 * Fungsi inti: CRC16-CCITT, parse TLV, konversi static -> dynamic (injeksi nominal
 * tag 54), dan render bitmap QR.
 *
 * Merchant GoPay "Suruh Aja" berperan sebagai "bank": semua uang fisik masuk ke
 * QRIS statis ini, saldo per-akun cuma angka di Firestore.
 */
object QrisUtil {

    // QRIS statis merchant GoPay — sebagai penerima tunggal semua pembayaran.
    const val STATIC_QRIS =
        "00020101021126610014COM.GO-JEK.WWW01189360091430996166250210G0996166250303UMI" +
        "51440014ID.CO.QRIS.WWW0215ID10265731433550303UMI5204899953033605802ID5925" +
        "Suruh Aja, Digital & Krea6012ACEH SELATAN61052376162140703A01110362163040288"

    private data class TLV(val tag: String, val value: String)

    /** CRC16-CCITT (poly 0x1021, init 0xFFFF), hasil 4 hex uppercase. */
    fun crc16(str: String): String {
        var crc = 0xFFFF
        for (c in str) {
            crc = crc xor (c.code shl 8)
            for (j in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }

    /** Cek apakah CRC (4 hex terakhir) valid. */
    fun isValidCrc(qris: String): Boolean {
        if (qris.length < 4) return false
        return crc16(qris.substring(0, qris.length - 4)) == qris.substring(qris.length - 4)
    }

    private fun parseTLV(data: String): List<TLV> {
        val out = mutableListOf<TLV>()
        var pos = 0
        while (pos + 4 <= data.length) {
            val tag = data.substring(pos, pos + 2)
            val len = data.substring(pos + 2, pos + 4).toIntOrNull() ?: break
            if (pos + 4 + len > data.length) break
            out.add(TLV(tag, data.substring(pos + 4, pos + 4 + len)))
            pos += 4 + len
        }
        return out
    }

    /**
     * Konversi QRIS statis -> dinamis dengan nominal tertentu.
     * Ganti method "11" -> "12", sisipkan tag 54 (amount), hitung ulang CRC.
     */
    fun convertToDynamic(staticQris: String, amount: Long): String {
        val result = mutableListOf<TLV>()
        var inserted = false
        val skip = setOf("54", "55", "56", "57", "63")
        for (el in parseTLV(staticQris)) {
            if (el.tag in skip) continue
            if (el.tag == "01") {
                result.add(TLV("01", "12"))
                continue
            }
            if (el.tag == "58" && !inserted) {
                result.add(TLV("54", amount.toString()))
                inserted = true
            }
            result.add(el)
        }
        val body = result.joinToString("") {
            it.tag + it.value.length.toString().padStart(2, '0') + it.value
        }
        val crcInput = body + "6304"
        return crcInput + crc16(crcInput)
    }

    /** Render string QRIS menjadi bitmap hitam-putih (hitam = foreground). */
    fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
