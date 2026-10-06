package com.rupeewise.sanitysnap.domain

/** Minimal inputs for balance math so it can be unit-tested without Room. */
data class ExpenseForBalance(
    val id: String,
    val groupId: String?,
    val payers: Map<String, Long>,
    val shares: Map<String, Long>,
)

data class PaymentForBalance(
    val groupId: String?,
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
)

/**
 * Pairwise debt ledger. `debts[a][b] = x` means a owes b x (minor units), already netted, so
 * only one direction of each pair is positive. No "simplify debts" (explicitly out of V1).
 */
class Ledger(private val raw: MutableMap<Pair<String, String>, Long> = mutableMapOf()) {

    fun add(debtor: String, creditor: String, amount: Long) {
        if (debtor == creditor || amount == 0L) return
        raw[debtor to creditor] = (raw[debtor to creditor] ?: 0L) + amount
    }

    /** Net amount [a] owes [b] (negative = b owes a). */
    fun netOwed(a: String, b: String): Long = (raw[a to b] ?: 0L) - (raw[b to a] ?: 0L)

    /** Net position per user: positive = is owed money overall, negative = owes. */
    fun netPositions(): Map<String, Long> {
        val m = mutableMapOf<String, Long>()
        raw.forEach { (k, v) ->
            m[k.first] = (m[k.first] ?: 0L) - v
            m[k.second] = (m[k.second] ?: 0L) + v
        }
        return m
    }

    fun counterparties(of: String): Set<String> =
        raw.keys.filter { it.first == of || it.second == of }.map { if (it.first == of) it.second else it.first }.toSet()

    fun allUsers(): Set<String> = raw.keys.flatMap { listOf(it.first, it.second) }.toSet()
}

object BalanceCalculator {

    /**
     * Builds the ledger. For each expense, each participant's owed share is attributed to the
     * payers in proportion to how much each payer paid.
     */
    fun ledger(
        expenses: List<ExpenseForBalance>,
        payments: List<PaymentForBalance>,
        groupId: String? = null,
        filterByGroup: Boolean = false,
    ): Ledger {
        val l = Ledger()
        expenses.filter { !filterByGroup || it.groupId == groupId }.forEach { e ->
            val totalPaid = e.payers.values.sum()
            if (totalPaid <= 0) return@forEach
            val payerIds = e.payers.keys.sorted()
            e.shares.forEach { (debtor, owed) ->
                val parts = SplitCalculator.distribute(owed, payerIds.map { (e.payers[it] ?: 0L).toDouble() })
                payerIds.zip(parts).forEach { (payer, part) -> l.add(debtor, payer, part) }
            }
        }
        payments.filter { !filterByGroup || it.groupId == groupId }.forEach { p ->
            // A payment from A to B reduces what A owes B => model as B "owing" A the amount.
            l.add(p.toUserId, p.fromUserId, p.amountMinor)
        }
        return l
    }
}
