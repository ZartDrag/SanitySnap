package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.R
import com.rupeewise.sanitysnap.data.db.ConflictEntity
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.repo.ConflictDetail
import com.rupeewise.sanitysnap.domain.ConflictAnalysis
import com.rupeewise.sanitysnap.domain.ConflictText
import com.rupeewise.sanitysnap.domain.DiffOp
import com.rupeewise.sanitysnap.domain.DiffSegment
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.ExpensePayload
import com.rupeewise.sanitysnap.domain.FieldChange
import com.rupeewise.sanitysnap.domain.FieldKeys
import com.rupeewise.sanitysnap.domain.FieldValue
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.InlineDiff
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.domain.Place
import com.rupeewise.sanitysnap.domain.Snapshot
import com.rupeewise.sanitysnap.domain.analyzeConflict
import com.rupeewise.sanitysnap.domain.expenseSnapshot
import com.rupeewise.sanitysnap.domain.genericSnapshot
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.SectionCard
import com.rupeewise.sanitysnap.ui.addedStyle
import com.rupeewise.sanitysnap.ui.diffAnnotated
import com.rupeewise.sanitysnap.ui.removedStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Git-style conflict UI, organised around CHANGES: what was changed on your phone vs on the other
 * phone, each compared with the version before both edits, with the actual edit highlighted.
 * Diff logic lives in domain/ChangeDiff.kt (pure Kotlin, unit-tested).
 */
@Composable
fun ConflictResolutionScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.repo
    val conflicts by repo.conflicts.collectAsStateWithLifecycle(emptyList())

    FsScaffold(title = stringResource(R.string.conflicts_title), onBack = onBack) {
        if (conflicts.isEmpty()) Text(stringResource(R.string.conflicts_empty))
        conflicts.forEach { c -> ConflictCard(c) }
    }
}

@Composable
private fun ConflictCard(c: ConflictEntity) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    var detail by remember(c.id, c.status) { mutableStateOf<ConflictDetail?>(null) }
    LaunchedEffect(c.id, c.status) { detail = repo.conflictDetail(c) }
    val model = remember(c, detail) { buildConflictCardModel(c, detail) }
    ConflictCardContent(
        model,
        onKeepMine = { scope.launch { repo.resolveConflict(c.id, keepOurs = true) } },
        onKeepTheirs = { scope.launch { repo.resolveConflict(c.id, keepOurs = false) } },
    )
}

// ------------------------------------------------------------------ model

data class ConflictSide(
    val place: Place,
    /** "@Zart · this phone · 6 Oct, 12:41 pm" */
    val whoWhen: String,
    val changes: List<FieldChange>,
    val deleted: Boolean,
)

data class ConflictCardModel(
    val title: String,
    val noun: String,
    val open: Boolean,
    val resolutionEventShort: String?,
    val resolvedDifferentlyBy: String?,
    val ours: ConflictSide,
    val theirs: ConflictSide,
    val analysis: ConflictAnalysis,
    val oursSnapshot: Snapshot,
    val theirsSnapshot: Snapshot,
) {
    val oursSummary get() = ConflictText.sideSummary(ours.place, ours.changes, ours.deleted, noun, analysis)
    val theirsSummary get() = ConflictText.sideSummary(theirs.place, theirs.changes, theirs.deleted, noun, analysis)
    val clashLines get() = ConflictText.clashLines(analysis, ours.place, theirs.place, noun)
    val keepMineText get() = ConflictText.keepLine(true, analysis, oursSnapshot, theirsSnapshot, noun)
    val keepTheirsText get() = ConflictText.keepLine(false, analysis, oursSnapshot, theirsSnapshot, noun)
}

private val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN"))

fun buildConflictCardModel(c: ConflictEntity, d: ConflictDetail?): ConflictCardModel {
    val names = d?.userNames.orEmpty()
    fun name(id: String) = names[id] ?: id.take(8)
    fun snap(json: String): Snapshot =
        if (c.entityType == EntityType.EXPENSE) {
            runCatching { FsJson.decodeFromString<ExpensePayload>(json) }.getOrNull()
                ?.let { expenseSnapshot(it, ::name) { gid -> d?.groupNames?.get(gid) } } ?: genericSnapshot(json)
        } else {
            genericSnapshot(json)
        }
    val ours = snap(c.oursPayload)
    val theirs = snap(c.theirsPayload)
    val base = d?.basePayload?.let(::snap)
    val a = analyzeConflict(base, ours, theirs)

    val meId = d?.oursEvent?.takeIf { it.deviceId == d.localDeviceId }?.authorUserId
    fun side(e: EventEntity?, deviceId: String, changes: List<FieldChange>, deleted: Boolean): ConflictSide {
        val author = e?.let { name(it.authorUserId) } ?: "someone"
        val thisPhone = deviceId == d?.localDeviceId
        val place = Place.of(thisPhone, author, authorIsMe = meId != null && e?.authorUserId == meId, deviceShort = deviceId.take(6))
        val where = if (thisPhone) "this phone" else "device ${deviceId.take(6)}"
        val whoWhen = listOfNotNull(author, where, e?.let { timeFmt.format(Date(it.createdAt)) }).joinToString(" · ")
        return ConflictSide(place, whoWhen, changes, deleted)
    }
    val bothResolves = d?.oursEvent?.op == Op.RESOLVE && d.theirsEvent?.op == Op.RESOLVE
    val noun = if (c.entityType == EntityType.EXPENSE) "expense" else c.entityType.lowercase()
    val title = (base ?: ours)[FieldKeys.DESCRIPTION]?.value?.display ?: noun
    return ConflictCardModel(
        title = "${noun.replaceFirstChar { it.uppercase() }} \u201C$title\u201D",
        noun = noun,
        open = c.status == "OPEN",
        resolutionEventShort = c.resolutionEventId?.take(8),
        resolvedDifferentlyBy = if (bothResolves) d?.theirsEvent?.let { name(it.authorUserId) } ?: "the other person" else null,
        ours = side(d?.oursEvent, c.oursDeviceId, a.oursChanges, a.oursDeleted),
        theirs = side(d?.theirsEvent, c.theirsDeviceId, a.theirsChanges, a.theirsDeleted),
        analysis = a,
        oursSnapshot = ours,
        theirsSnapshot = theirs,
    )
}

// ------------------------------------------------------------------ UI

private val BadgeColor = Color(0xFFFFC857)

@Composable
fun ConflictCardContent(model: ConflictCardModel, onKeepMine: () -> Unit, onKeepTheirs: () -> Unit) {
    SectionCard(model.title) {
        StatusChip(model.open)

        // 1. Plain summary: one line per side, then what actually clashes
        Text(model.oursSummary, style = MaterialTheme.typography.bodyMedium)
        Text(model.theirsSummary, style = MaterialTheme.typography.bodyMedium)
        if (model.resolvedDifferentlyBy != null) {
            Text(ConflictText.resolvedDifferently(model.resolvedDifferentlyBy), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        model.clashLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }

        // 2. Changes per side; side by side when there is room, stacked on phones
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 560.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SideSection(model.ours, model, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    SideSection(model.theirs, model, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SideSection(model.ours, model, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                    SideSection(model.theirs, model, MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth())
                }
            }
        }

        // 4. Unchanged details, collapsed
        UnchangedDetails(model)

        HorizontalDivider()
        if (model.open) {
            // 5. Plain words for what each button keeps (behaviour unchanged)
            Text(model.keepMineText, style = MaterialTheme.typography.bodySmall)
            Text(model.keepTheirsText, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onKeepMine, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conflict_keep_mine)) }
                OutlinedButton(onClick = onKeepTheirs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conflict_keep_theirs)) }
            }
            Text(stringResource(R.string.conflict_choice_note), style = MaterialTheme.typography.bodySmall)
            // TODO(conflicts): field-by-field manual merge (pick per field) instead of whole-version choice.
        } else {
            Text(stringResource(R.string.conflict_resolved_with, model.resolutionEventShort ?: ""), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SideSection(side: ConflictSide, model: ConflictCardModel, accent: Color, modifier: Modifier) {
    Column(
        modifier
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.conflict_changed_on, side.place.name), style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold)
        Text(side.whoWhen, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            side.deleted -> Text(stringResource(R.string.conflict_deleted_item, model.noun), color = OweColor, fontWeight = FontWeight.Bold)
            side.changes.isEmpty() -> Text(stringResource(R.string.conflict_no_changes), style = MaterialTheme.typography.bodySmall)
            else -> side.changes.forEach { ch -> ChangeRow(ch, clash = ch.key in model.analysis.clashKeys) }
        }
    }
}

@Composable
private fun ChangeRow(ch: FieldChange, clash: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ch.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (clash) ConflictBadge()
        }
        val notIncluded = stringResource(R.string.conflict_not_included)
        val removed = stringResource(R.string.conflict_removed)
        when {
            ch.segments != null -> Text(diffAnnotated(ch.segments), style = MaterialTheme.typography.bodyMedium)
            ch.deltaMinor != null -> {
                val b = ch.before as FieldValue.Amount
                val a = ch.after as FieldValue.Amount
                Text(
                    buildAnnotatedString {
                        withStyle(removedStyle()) { append(b.display) }
                        append(" \u2192 ")
                        withStyle(addedStyle()) { append(a.display) }
                        append("  (${ConflictText.deltaText(ch.deltaMinor, a.currency)})")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ch.people != null -> {
                val currency = (ch.after as? FieldValue.People)?.currency ?: (ch.before as? FieldValue.People)?.currency ?: "INR"
                ch.people.forEach { p ->
                    Text(
                        buildAnnotatedString {
                            append("${p.name}: ")
                            withStyle(removedStyle()) { append(p.before?.let { Money.format(it, currency) } ?: notIncluded) }
                            append(" \u2192 ")
                            withStyle(addedStyle()) { append(p.after?.let { Money.format(it, currency) } ?: removed) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> Text("${ch.before?.display ?: ""} \u2192 ${ch.after?.display ?: ""}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConflictBadge() {
    Text(
        stringResource(R.string.conflict_badge),
        color = Color.Black,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(BadgeColor, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun UnchangedDetails(model: ConflictCardModel) {
    val fields = model.analysis.unchanged
    if (fields.isEmpty()) return
    var expanded by rememberSaveable(model.title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.conflict_unchanged_details, fields.size), style = MaterialTheme.typography.labelLarge)
            Text(if (expanded) "\u25B4" else "\u25BE", style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            fields.forEach { f ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(f.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.value.display, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(open: Boolean) {
    Text(
        stringResource(if (open) R.string.conflict_status_open else R.string.conflict_status_resolved),
        color = if (open) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(if (open) BadgeColor else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
