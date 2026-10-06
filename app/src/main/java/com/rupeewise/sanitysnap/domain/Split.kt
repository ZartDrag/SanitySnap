package com.rupeewise.sanitysnap.domain

import kotlin.math.roundToLong

enum class SplitType { EQUAL, EXACT, PERCENT, SHARES }

data class ShareInput(val userId: String, val value: Double = 0.0)

data class ComputedShare(val userId: String, val amountMinor: Long, val inputValue: Double?)

sealed class SplitResult {
    data class Ok(val shares: List<ComputedShare>) : SplitResult()
    data class Error(val message: String) : SplitResult()
}

/**
 * Pure split math (unit-tested). Rounding remainders (paise) are distributed one-by-one to
 * participants in the given order so totals always add up exactly.
 */
object SplitCalculator {

    fun compute(totalMinor: Long, type: SplitType, inputs: List<ShareInput>): SplitResult {
        if (totalMinor <= 0) return SplitResult.Error("Amount must be greater than zero")
        if (inputs.isEmpty()) return SplitResult.Error("Pick at least one participant")
        return when (type) {
            SplitType.EQUAL -> SplitResult.Ok(distribute(totalMinor, inputs.map { 1.0 }).zip(inputs) { amt, i ->
                ComputedShare(i.userId, amt, null)
            })
            SplitType.EXACT -> {
                val amounts = inputs.map { (it.value * 100).roundToLong() }
                if (amounts.any { it < 0 }) return SplitResult.Error("Amounts cannot be negative")
                val sum = amounts.sum()
                if (sum != totalMinor) {
                    SplitResult.Error("Exact amounts add up to ${Money.plain(sum)}, expected ${Money.plain(totalMinor)}")
                } else {
                    SplitResult.Ok(inputs.zip(amounts) { i, a -> ComputedShare(i.userId, a, i.value) })
                }
            }
            SplitType.PERCENT -> {
                if (inputs.any { it.value < 0 }) return SplitResult.Error("Percentages cannot be negative")
                val sum = inputs.sumOf { it.value }
                if (kotlin.math.abs(sum - 100.0) > 0.001) {
                    SplitResult.Error("Percentages add up to ${"%.2f".format(sum)}%, expected 100%")
                } else {
                    SplitResult.Ok(distribute(totalMinor, inputs.map { it.value }).zip(inputs) { a, i ->
                        ComputedShare(i.userId, a, i.value)
                    })
                }
            }
            SplitType.SHARES -> {
                if (inputs.any { it.value < 0 }) return SplitResult.Error("Shares cannot be negative")
                if (inputs.sumOf { it.value } <= 0.0) {
                    SplitResult.Error("Total shares must be greater than zero")
                } else {
                    SplitResult.Ok(distribute(totalMinor, inputs.map { it.value }).zip(inputs) { a, i ->
                        ComputedShare(i.userId, a, i.value)
                    })
                }
            }
        }
    }

    /** Largest-remainder style proportional distribution of [total] by [weights]. */
    fun distribute(total: Long, weights: List<Double>): List<Long> {
        val wSum = weights.sum()
        if (wSum <= 0.0) return weights.map { 0L }
        val raw = weights.map { total * it / wSum }
        val floors = raw.map { kotlin.math.floor(it).toLong() }.toMutableList()
        var remainder = total - floors.sum()
        val order = raw.indices.sortedWith(compareByDescending<Int> { raw[it] - floors[it] }.thenBy { it })
        var k = 0
        while (remainder > 0 && order.isNotEmpty()) {
            val idx = order[k % order.size]
            if (weights[idx] > 0.0) {
                floors[idx] = floors[idx] + 1
                remainder--
            }
            k++
        }
        return floors
    }
}
