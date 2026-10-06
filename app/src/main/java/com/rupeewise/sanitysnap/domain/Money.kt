package com.rupeewise.sanitysnap.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

const val DEFAULT_CURRENCY = "INR"

object Money {
    /** Parses a user-entered decimal like "123.45" into minor units (paise). Returns null if invalid. */
    fun parseToMinor(text: String): Long? {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return null
        return try {
            BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    fun format(minor: Long, currency: String = DEFAULT_CURRENCY): String {
        val nf = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        runCatching { nf.currency = Currency.getInstance(currency) }
        return nf.format(BigDecimal.valueOf(minor, 2))
    }

    fun plain(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
}
