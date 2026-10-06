package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.rupeewise.sanitysnap.data.db.ConflictEntity
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.repo.ConflictDetail
import com.rupeewise.sanitysnap.domain.AmountPart
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.ExpensePayload
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.ui.theme.FairShareTheme
import kotlinx.serialization.encodeToString

/** Sample from Kartik's screenshot (used by the @Preview and the screenshot test). */
object ConflictCardSamples {
    fun dinner(): ConflictCardModel {
        val base = ExpensePayload(
            id = "e1", groupId = "g1", description = "Dinner", amountMinor = 1_200_000,
            payers = listOf(AmountPart("zart", 1_200_000)), shares = listOf(AmountPart("riya", 600_000), AmountPart("zart", 600_000)),
        )
        val ours = base.copy(description = "Dinner [edited on this phone]")
        val theirs = base.copy(description = "Dinner [edited on Device B]")
        fun ev(id: String, dev: String, author: String, at: Long, payload: ExpensePayload, baseId: String?) = EventEntity(
            id, dev, 1, 1, author, EntityType.EXPENSE, "e1", if (baseId == null) Op.CREATE else Op.UPDATE,
            FsJson.encodeToString(payload), baseId, at, "", "",
        )
        val baseEv = ev("b", "aaaaaa0000000000", "zart", 1_791_270_000_000, base, null)
        val oursEv = ev("o", "aaaaaa0000000000", "zart", 1_791_272_460_000, ours, "b")
        val theirsEv = ev("t", "450b1e0000000000", "riya", 1_791_272_520_000, theirs, "b")
        val c = ConflictEntity(
            "c1", EntityType.EXPENSE, "e1", "o", "t", oursEv.payload, theirsEv.payload,
            oursEv.deviceId, theirsEv.deviceId, "OPEN", null, theirsEv.createdAt,
        )
        val d = ConflictDetail(
            basePayload = baseEv.payload, baseEvent = baseEv, oursEvent = oursEv, theirsEvent = theirsEv,
            localDeviceId = "aaaaaa0000000000", userNames = mapOf("zart" to "@Zart", "riya" to "@riya"),
            groupNames = mapOf("g1" to "Trip with @Zart"),
        )
        return buildConflictCardModel(c, d)
    }
}

@Preview(widthDp = 360, heightDp = 900, showBackground = true, backgroundColor = 0xFF121212, uiMode = 0x21)
@Composable
private fun ConflictCardPreview() {
    FairShareTheme { ConflictCardContent(ConflictCardSamples.dinner(), {}, {}) }
}
