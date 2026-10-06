package com.rupeewise.sanitysnap

import com.rupeewise.sanitysnap.domain.AmountPart
import com.rupeewise.sanitysnap.domain.ConflictText
import com.rupeewise.sanitysnap.domain.DiffOp
import com.rupeewise.sanitysnap.domain.DiffSegment
import com.rupeewise.sanitysnap.domain.ExpensePayload
import com.rupeewise.sanitysnap.domain.FieldKeys
import com.rupeewise.sanitysnap.domain.InlineDiff
import com.rupeewise.sanitysnap.domain.PersonChange
import com.rupeewise.sanitysnap.domain.Place
import com.rupeewise.sanitysnap.domain.analyzeConflict
import com.rupeewise.sanitysnap.domain.diffFields
import com.rupeewise.sanitysnap.domain.expenseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeDiffTest {
    private val names = mapOf("z" to "@Zart", "r" to "@riya")
    private fun snap(p: ExpensePayload) = expenseSnapshot(p, { names[it] ?: it }) { if (it == "g") "Trip with @Zart" else null }

    private val base = ExpensePayload(
        id = "e", groupId = "g", description = "Dinner", amountMinor = 1_200_000,
        payers = listOf(AmountPart("z", 1_200_000)), shares = listOf(AmountPart("r", 600_000), AmountPart("z", 600_000)),
    )
    private val mine = Place("your phone", "@Zart")
    private val riyas = Place("@riya's phone", "@riya")

    @Test
    fun inlineDiff_highlightsOnlyTheAddedSuffix() {
        val d = InlineDiff.diff("Dinner", "Dinner [edited on this phone]")
        assertEquals(listOf(DiffSegment("Dinner", DiffOp.SAME), DiffSegment(" [edited on this phone]", DiffOp.ADDED)), d)
        assertEquals(listOf(DiffSegment("Dinner", DiffOp.SAME)), InlineDiff.before(d))
    }

    @Test
    fun inlineDiff_wordReplacementAndCharFallback() {
        val d = InlineDiff.diff("Dinner at Goa", "Lunch at Goa")
        assertEquals(DiffSegment("Dinner", DiffOp.REMOVED), d[0])
        assertEquals(DiffSegment("Lunch", DiffOp.ADDED), d[1])
        assertEquals(DiffSegment(" at Goa", DiffOp.SAME), d[2])
        // single word → character level
        val c = InlineDiff.diff("Diner", "Dinner")
        assertEquals("Diner", InlineDiff.before(c).joinToString("") { it.text })
        assertEquals(listOf(DiffOp.ADDED), c.filter { it.op != DiffOp.SAME }.map { it.op })
    }

    @Test
    fun fieldDiff_amountDeltaAndOnlyChangedPeople() {
        val edited = base.copy(amountMinor = 1_250_000, payers = listOf(AmountPart("z", 1_250_000)),
            shares = listOf(AmountPart("r", 600_000), AmountPart("z", 650_000)))
        val ch = diffFields(snap(base), snap(edited)).associateBy { it.key }
        assertEquals(setOf(FieldKeys.AMOUNT, FieldKeys.PAID_BY, FieldKeys.SPLIT), ch.keys)
        assertEquals(50_000L, ch[FieldKeys.AMOUNT]!!.deltaMinor)
        assertEquals("+₹500.00", ConflictText.deltaText(50_000))
        assertEquals(listOf(PersonChange("@Zart", 600_000, 650_000)), ch[FieldKeys.SPLIT]!!.people) // @riya unchanged → not listed
    }

    @Test
    fun screenshotExample_bothChangedDescription() {
        val ours = base.copy(description = "Dinner [edited on this phone]")
        val theirs = base.copy(description = "Dinner [edited on Device B]")
        val a = analyzeConflict(snap(base), snap(ours), snap(theirs))
        assertEquals(listOf(FieldKeys.DESCRIPTION), a.clashKeys)
        assertEquals(listOf(FieldKeys.AMOUNT, FieldKeys.PAID_BY, FieldKeys.SPLIT_TYPE, FieldKeys.SPLIT, FieldKeys.GROUP), a.unchanged.map { it.key })
        assertEquals("On your phone, @Zart changed the description.", ConflictText.sideSummary(mine, a.oursChanges, false, "expense", a))
        assertEquals("On @riya's phone, @riya changed the description.", ConflictText.sideSummary(riyas, a.theirsChanges, false, "expense", a))
        assertEquals(listOf("Both changed the description differently."), ConflictText.clashLines(a, mine, riyas, "expense"))
        assertEquals("Keep mine: description stays “Dinner [edited on this phone]”", ConflictText.keepLine(true, a, snap(ours), snap(theirs), "expense"))
        assertEquals("Keep theirs: description becomes “Dinner [edited on Device B]”", ConflictText.keepLine(false, a, snap(ours), snap(theirs), "expense"))
    }

    @Test
    fun oneSidedFieldIsNoClash() {
        val ours = base.copy(description = "Dinner!")
        val theirs = base.copy(description = "Dinner?", amountMinor = 1_250_000, payers = listOf(AmountPart("z", 1_250_000)))
        val a = analyzeConflict(snap(base), snap(ours), snap(theirs))
        assertEquals("On @riya's phone, @riya changed the description, the amount and who paid.",
            ConflictText.sideSummary(riyas, a.theirsChanges, false, "expense", a))
        val lines = ConflictText.clashLines(a, mine, riyas, "expense")
        assertEquals("Both changed the description differently.", lines[0])
        assertEquals("Only @riya's phone changed the amount and who paid, so there's no clash there.", lines[1])
        assertTrue(ConflictText.keepLine(true, a, snap(ours), snap(theirs), "expense").contains("amount stays “₹12,000.00”"))
    }

    @Test
    fun deleteVersusEdit() {
        val ours = base.copy(deleted = true)
        val theirs = base.copy(description = "Dinner + drinks")
        val a = analyzeConflict(snap(base), snap(ours), snap(theirs))
        assertTrue(a.oursDeleted)
        assertEquals("On your phone, @Zart deleted the expense.", ConflictText.sideSummary(mine, a.oursChanges, a.oursDeleted, "expense", a))
        assertEquals(listOf("Your phone deleted the expense while @riya's phone edited it. Keep one outcome."), ConflictText.clashLines(a, mine, riyas, "expense"))
        assertEquals("Keep mine: the expense stays deleted", ConflictText.keepLine(true, a, snap(ours), snap(theirs), "expense"))
        assertTrue(ConflictText.keepLine(false, a, snap(ours), snap(theirs), "expense").startsWith("Keep theirs: the expense is kept (not deleted)"))
    }

    @Test
    fun differentFieldsOnEachSide_explainsPickOne() {
        val ours = base.copy(description = "Dinner!")
        val theirs = base.copy(groupId = null)
        val a = analyzeConflict(snap(base), snap(ours), snap(theirs))
        assertTrue(a.clashKeys.isEmpty())
        val lines = ConflictText.clashLines(a, mine, riyas, "expense")
        assertEquals("Each version is missing the other's change, so pick one.", lines.last())
    }

    @Test
    fun otherDevicePlace() {
        assertEquals("your other device 450b1e", Place.of(false, "@Zart", true, "450b1e").name)
        assertEquals("@riya's phone", Place.of(false, "@riya", false, "450b1e").name)
        assertEquals("your phone", Place.of(true, "@Zart", true, "aaaaaa").name)
    }
}
