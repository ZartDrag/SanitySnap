package com.rupeewise.sanitysnap

import com.rupeewise.sanitysnap.domain.BalanceCalculator
import com.rupeewise.sanitysnap.domain.ExpenseForBalance
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.domain.PaymentForBalance
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitCalculator
import com.rupeewise.sanitysnap.domain.SplitResult
import com.rupeewise.sanitysnap.domain.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAndBalanceTest {
    private val abc = listOf(ShareInput("a"), ShareInput("b"), ShareInput("c"))

    @Test fun equalSplitDistributesRemainder() {
        val r = SplitCalculator.compute(10_000, SplitType.EQUAL, abc) as SplitResult.Ok
        assertEquals(listOf(3334L, 3333L, 3333L), r.shares.map { it.amountMinor })
    }

    @Test fun exactMustSumToTotal() {
        val bad = SplitCalculator.compute(10_000, SplitType.EXACT, listOf(ShareInput("a", 50.0), ShareInput("b", 40.0)))
        assertTrue(bad is SplitResult.Error)
        val ok = SplitCalculator.compute(10_000, SplitType.EXACT, listOf(ShareInput("a", 60.0), ShareInput("b", 40.0))) as SplitResult.Ok
        assertEquals(listOf(6000L, 4000L), ok.shares.map { it.amountMinor })
    }

    @Test fun percentAndShares() {
        val p = SplitCalculator.compute(999, SplitType.PERCENT, listOf(ShareInput("a", 50.0), ShareInput("b", 50.0))) as SplitResult.Ok
        assertEquals(999L, p.shares.sumOf { it.amountMinor })
        val s = SplitCalculator.compute(12_000, SplitType.SHARES, listOf(ShareInput("a", 2.0), ShareInput("b", 1.0))) as SplitResult.Ok
        assertEquals(listOf(8000L, 4000L), s.shares.map { it.amountMinor })
    }

    @Test fun ledgerNetsExpensesAndPayments() {
        val e = ExpenseForBalance("e1", "g", payers = mapOf("a" to 9000L), shares = mapOf("a" to 3000L, "b" to 3000L, "c" to 3000L))
        val l = BalanceCalculator.ledger(listOf(e), listOf(PaymentForBalance("g", "b", "a", 1000L)))
        assertEquals(2000L, l.netOwed("b", "a"))
        assertEquals(3000L, l.netOwed("c", "a"))
        assertEquals(5000L, l.netPositions()["a"])
    }

    @Test fun moneyParsing() {
        assertEquals(12345L, Money.parseToMinor("123.45"))
        assertEquals(100L, Money.parseToMinor("1"))
        assertEquals(null, Money.parseToMinor("abc"))
    }
}
