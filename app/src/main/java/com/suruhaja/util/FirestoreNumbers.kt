package com.suruhaja.util

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Tolerant numeric reads. Firestore stores integers as Long and fractional
 * values as Double; reading a Double via getLong()/toObject(Int) throws and can
 * crash an unguarded snapshot listener. These coerce via Number so legacy
 * fractional data degrades gracefully instead of killing the app.
 */
fun DocumentSnapshot.longOr(field: String, default: Long = 0L): Long =
    (get(field) as? Number)?.toLong() ?: default

fun DocumentSnapshot.doubleOr(field: String, default: Double = 0.0): Double =
    (get(field) as? Number)?.toDouble() ?: default

fun DocumentSnapshot.intOr(field: String, default: Int = 0): Int =
    (get(field) as? Number)?.toInt() ?: default
