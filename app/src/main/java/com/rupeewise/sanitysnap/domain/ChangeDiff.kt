package com.rupeewise.sanitysnap.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Pure-Kotlin diff logic for the conflict card (no Android/Compose imports, unit-tested):
 *  - Snapshot: an entity version as ordered, typed fields
 *  - diffFields: what one side changed compared with the common base
 *  - analyzeConflict: per-field classification (clash / only one side / same change / unchanged)
 *  - InlineDiff: word-level LCS (character-level for single words) to highlight the actual edit
 *  - ConflictText: the plain-English sentences shown on the card
 */

// ------------------------------------------------------------------ model

sealed class FieldValue {
    abstract val display: String

    data class Text(val value: String) : FieldValue() {
        override val display get() = value
    }

    data class Amount(val minor: Long, val currency: String = DEFAULT_CURRENCY) : FieldValue() {
        override val display get() = Money.format(minor, currency)
    }

    /** Per-person money (payers or shares), keyed by display name, in a stable order. */
    data class People(val parts: Map<String, Long>, val currency: String = DEFAULT_CURRENCY) : FieldValue() {
        override val display get() = if (parts.isEmpty()) "nobody" else parts.entries.joinToString { "${it.key} ${Money.format(it.value, currency)}" }
    }
}

data class Field(val key: String, val label: String, val value: FieldValue)

data class Snapshot(val fields: List<Field>, val deleted: Boolean = false) {
    operator fun get(key: String): Field? = fields.firstOrNull { it.key == key }
}

object FieldKeys {
    const val DESCRIPTION = "description"
    const val AMOUNT = "amount"
    const val PAID_BY = "paidBy"
    const val SPLIT_TYPE = "splitType"
    const val SPLIT = "split"
    const val GROUP = "group"
}

/** Builds the typed snapshot of an expense. [name] maps userId → "@username". */
fun expenseSnapshot(p: ExpensePayload, name: (String) -> String, groupName: (String) -> String?): Snapshot = Snapshot(
    listOf(
        Field(FieldKeys.DESCRIPTION, "Description", FieldValue.Text(p.description)),
        Field(FieldKeys.AMOUNT, "Amount", FieldValue.Amount(p.amountMinor, p.currency)),
        Field(FieldKeys.PAID_BY, "Paid by", FieldValue.People(p.payers.associate { name(it.userId) to it.amountMinor }, p.currency)),
        Field(FieldKeys.SPLIT_TYPE, "Split type", FieldValue.Text(p.splitType.lowercase().replaceFirstChar { it.uppercase() })),
        Field(FieldKeys.SPLIT, "Split", FieldValue.People(p.shares.associate { name(it.userId) to it.amountMinor }, p.currency)),
        Field(FieldKeys.GROUP, "Group", FieldValue.Text(p.groupId?.let { groupName(it) ?: it.take(8) } ?: "No group")),
    ),
    deleted = p.deleted,
)

/** Fallback for non-expense entities: every top-level JSON property becomes a text field. */
fun genericSnapshot(json: String): Snapshot {
    val o = runCatching { FsJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val deleted = runCatching { o["deleted"]?.jsonPrimitive?.content == "true" }.getOrDefault(false)
    return Snapshot(
        o.entries.filter { it.key != "deleted" && it.key != "id" }.map { (k, v) ->
            Field(k, k.replaceFirstChar { it.uppercase() }, FieldValue.Text(v.toString().removeSurrounding("\"")))
        },
        deleted,
    )
}

// ------------------------------------------------------------------ inline text diff

@kotlinx.serialization.Serializable
enum class DiffOp { SAME, ADDED, REMOVED }

@kotlinx.serialization.Serializable
data class DiffSegment(val text: String, val op: DiffOp)

object InlineDiff {
    private val TOKEN = Regex("""\s+|[^\s]+""")
    private const val MAX_CELLS = 250_000

    /** Interleaved diff of [a] → [b]. Words (and whitespace runs) are the unit; single words fall back to characters. */
    fun diff(a: String, b: String): List<DiffSegment> {
        if (a == b) return if (a.isEmpty()) emptyList() else listOf(DiffSegment(a, DiffOp.SAME))
        var ta = TOKEN.findAll(a).map { it.value }.toList()
        var tb = TOKEN.findAll(b).map { it.value }.toList()
        if (ta.size <= 1 && tb.size <= 1) {
            ta = a.map { it.toString() }
            tb = b.map { it.toString() }
        }
        return merge(lcs(ta, tb))
    }

    /** Segments for the old text: unchanged + removed parts. */
    fun before(segs: List<DiffSegment>) = segs.filter { it.op != DiffOp.ADDED }

    /** Segments for the new text: unchanged + added parts. */
    fun after(segs: List<DiffSegment>) = segs.filter { it.op != DiffOp.REMOVED }

    private fun lcs(a: List<String>, b: List<String>): List<DiffSegment> {
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return listOf(DiffSegment(a.joinToString(""), DiffOp.REMOVED), DiffSegment(b.joinToString(""), DiffOp.ADDED))
        }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<DiffSegment>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += DiffSegment(a[i], DiffOp.SAME); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffSegment(a[i], DiffOp.REMOVED); i++ }
                else -> { out += DiffSegment(b[j], DiffOp.ADDED); j++ }
            }
        }
        while (i < a.size) out += DiffSegment(a[i++], DiffOp.REMOVED)
        while (j < b.size) out += DiffSegment(b[j++], DiffOp.ADDED)
        return out
    }

    /** Joins adjacent tokens of the same kind; whitespace between two equal-kind changes joins them. */
    private fun merge(segs: List<DiffSegment>): List<DiffSegment> {
        val out = mutableListOf<DiffSegment>()
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && last.op == s.op) out[out.size - 1] = last.copy(text = last.text + s.text) else out += s
        }
        return out
    }
}

// ------------------------------------------------------------------ field diff

data class PersonChange(val name: String, val before: Long?, val after: Long?)

data class FieldChange(
    val key: String,
    val label: String,
    val before: FieldValue?,
    val after: FieldValue?,
) {
    /** Inline diff for text fields. */
    val segments: List<DiffSegment>? =
        if (before is FieldValue.Text? && after is FieldValue.Text?) InlineDiff.diff(before?.value ?: "", after?.value ?: "") else null

    /** after - before for amounts. */
    val deltaMinor: Long? = if (before is FieldValue.Amount && after is FieldValue.Amount) after.minor - before.minor else null

    /** Only the people whose amount changed (null = not involved on that side). */
    val people: List<PersonChange>? = if (before is FieldValue.People? && after is FieldValue.People? && (before != null || after != null)) {
        val b = before?.parts.orEmpty()
        val a = after?.parts.orEmpty()
        (b.keys + a.keys).distinct().filter { b[it] != a[it] }.map { PersonChange(it, b[it], a[it]) }
    } else {
        null
    }
}

/** Fields [side] changed compared with [base] (in display order). */
fun diffFields(base: Snapshot, side: Snapshot): List<FieldChange> {
    val keys = (side.fields.map { it.key } + base.fields.map { it.key }).distinct()
    return keys.mapNotNull { k ->
        val b = base[k]
        val s = side[k]
        if (b?.value == s?.value) null else FieldChange(k, (s ?: b)!!.label, b?.value, s?.value)
    }
}

data class ConflictAnalysis(
    val baseKnown: Boolean,
    val oursChanges: List<FieldChange>,
    val theirsChanges: List<FieldChange>,
    /** Both sides changed the field, to different values: the real clash. */
    val clashKeys: List<String>,
    /** Both sides made exactly the same change. */
    val sameChangeKeys: List<String>,
    val onlyOursKeys: List<String>,
    val onlyTheirsKeys: List<String>,
    val oursDeleted: Boolean,
    val theirsDeleted: Boolean,
    /** Fields neither side touched (value from the base, or ours when the base is unknown). */
    val unchanged: List<Field>,
    /** Fields whose final value differs between the two versions: what the buttons decide. */
    val decidingKeys: List<String>,
    val labels: Map<String, String>,
)

fun analyzeConflict(base: Snapshot?, ours: Snapshot, theirs: Snapshot): ConflictAnalysis {
    val ref = base ?: ours
    val labels = (ours.fields + theirs.fields + ref.fields).associate { it.key to it.label }
    val oursCh = if (base != null) diffFields(base, ours) else diffFields(theirs, ours).map { it.copy(before = null) }
    val theirsCh = if (base != null) diffFields(base, theirs) else diffFields(ours, theirs).map { it.copy(before = null) }
    val oKeys = oursCh.map { it.key }
    val tKeys = theirsCh.map { it.key }
    val both = oKeys.filter { it in tKeys }
    val clash = both.filter { ours[it]?.value != theirs[it]?.value }
    val deciding = (ours.fields.map { it.key } + theirs.fields.map { it.key }).distinct().filter { ours[it]?.value != theirs[it]?.value }
    return ConflictAnalysis(
        baseKnown = base != null,
        oursChanges = oursCh,
        theirsChanges = theirsCh,
        clashKeys = clash,
        sameChangeKeys = both - clash.toSet(),
        onlyOursKeys = oKeys - tKeys.toSet(),
        onlyTheirsKeys = tKeys - oKeys.toSet(),
        oursDeleted = ours.deleted && base?.deleted != true,
        theirsDeleted = theirs.deleted && base?.deleted != true,
        unchanged = ref.fields.filter { it.key !in oKeys && it.key !in tKeys },
        decidingKeys = deciding,
        labels = labels,
    )
}

// ------------------------------------------------------------------ sentences

/** Where an edit happened, e.g. "your phone" / "@riya's phone" / "your other device 450b1e". */
data class Place(val name: String, val who: String) {
    val title get() = name.replaceFirstChar { it.uppercase() }

    companion object {
        fun of(isThisPhone: Boolean, authorName: String, authorIsMe: Boolean, deviceShort: String) = when {
            isThisPhone -> Place("your phone", authorName)
            authorIsMe -> Place("your other device $deviceShort", authorName)
            else -> Place("$authorName's phone", authorName)
        }
    }
}

object ConflictText {
    fun phrase(key: String, label: String): String = when (key) {
        FieldKeys.PAID_BY -> "who paid"
        else -> "the ${label.lowercase()}"
    }

    fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun phrases(a: ConflictAnalysis, keys: List<String>) = joinAnd(keys.map { phrase(it, a.labels[it] ?: it) })

    /** "On your phone, @Zart changed the description." */
    fun sideSummary(place: Place, changes: List<FieldChange>, deleted: Boolean, noun: String, a: ConflictAnalysis): String = when {
        deleted -> "On ${place.name}, ${place.who} deleted the $noun."
        changes.isEmpty() -> "On ${place.name}, ${place.who} made no changes to the $noun."
        else -> "On ${place.name}, ${place.who} changed ${phrases(a, changes.map { it.key })}."
    }

    /** One or more lines explaining what actually clashes. */
    fun clashLines(a: ConflictAnalysis, ours: Place, theirs: Place, noun: String): List<String> {
        if (a.oursDeleted && a.theirsDeleted) return listOf("Both deleted the $noun.")
        if (a.oursDeleted) return listOf("${ours.title} deleted the $noun while ${theirs.name} edited it. Keep one outcome.")
        if (a.theirsDeleted) return listOf("${theirs.title} deleted the $noun while ${ours.name} edited it. Keep one outcome.")
        if (!a.baseKnown) return listOf("The two versions differ in ${phrases(a, a.decidingKeys)}.")
        val out = mutableListOf<String>()
        if (a.clashKeys.isNotEmpty()) out += "Both changed ${phrases(a, a.clashKeys)} differently."
        if (a.sameChangeKeys.isNotEmpty()) out += "Both made the same change to ${phrases(a, a.sameChangeKeys)}."
        if (a.onlyOursKeys.isNotEmpty()) out += "Only ${ours.name} changed ${phrases(a, a.onlyOursKeys)}, so there's no clash there."
        if (a.onlyTheirsKeys.isNotEmpty()) out += "Only ${theirs.name} changed ${phrases(a, a.onlyTheirsKeys)}, so there's no clash there."
        if (a.clashKeys.isEmpty() && a.decidingKeys.isNotEmpty()) out += "Each version is missing the other's change, so pick one."
        return out
    }

    fun resolvedDifferently(otherName: String) = "You and $otherName resolved this differently. Agree on one version."

    /**
     * What a button keeps, e.g. "Keep mine: description stays “Dinner [edited on this phone]”".
     * [keepOurs] = true for "Keep mine".
     */
    fun keepLine(keepOurs: Boolean, a: ConflictAnalysis, ours: Snapshot, theirs: Snapshot, noun: String): String {
        val button = if (keepOurs) "Keep mine" else "Keep theirs"
        val chosen = if (keepOurs) ours else theirs
        val verb = if (keepOurs) "stays" else "becomes"
        val chosenDeleted = if (keepOurs) a.oursDeleted else a.theirsDeleted
        val otherDeleted = if (keepOurs) a.theirsDeleted else a.oursDeleted
        if (chosenDeleted && !otherDeleted) return "$button: the $noun ${if (keepOurs) "stays deleted" else "is deleted"}"
        val parts = a.decidingKeys.mapNotNull { k ->
            val v = chosen[k]?.value ?: return@mapNotNull null
            "${(a.labels[k] ?: k).lowercase()} $verb \u201C${v.display}\u201D"
        }
        val restored = if (otherDeleted && !chosenDeleted) "the $noun is kept (not deleted)" else null
        val all = listOfNotNull(restored) + parts
        return if (all.isEmpty()) "$button: no visible difference" else "$button: " + all.joinToString("; ")
    }

    fun deltaText(deltaMinor: Long, currency: String = DEFAULT_CURRENCY): String =
        (if (deltaMinor >= 0) "+" else "\u2212") + Money.format(kotlin.math.abs(deltaMinor), currency)
}
