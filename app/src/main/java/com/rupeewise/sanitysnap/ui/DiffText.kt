package com.rupeewise.sanitysnap.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.rupeewise.sanitysnap.domain.DiffOp
import com.rupeewise.sanitysnap.domain.DiffSegment
import com.rupeewise.sanitysnap.domain.InlineDiff

/** Removed text: red + strikethrough. Added text: green + bold. Shared by Conflicts and Sync summary. */
fun removedStyle() = SpanStyle(color = OweColor, textDecoration = TextDecoration.LineThrough)
fun addedStyle() = SpanStyle(color = OwedColor, fontWeight = FontWeight.Bold)

/** “old” → “new”, where only the removed/added runs are highlighted. */
fun diffAnnotated(segs: List<DiffSegment>): AnnotatedString = buildAnnotatedString {
    val before = InlineDiff.before(segs)
    val after = InlineDiff.after(segs)
    fun part(list: List<DiffSegment>) = list.forEach { s ->
        when (s.op) {
            DiffOp.SAME -> append(s.text)
            DiffOp.REMOVED -> withStyle(removedStyle()) { append(s.text) }
            DiffOp.ADDED -> withStyle(addedStyle()) { append(s.text) }
        }
    }
    if (before.isNotEmpty()) {
        append("\u201C"); part(before); append("\u201D \u2192 ")
    }
    append("\u201C"); part(after); append("\u201D")
}

/** old → new for plain values (amounts etc.), with optional delta. */
fun valueChangeAnnotated(before: String?, after: String?, delta: String? = null): AnnotatedString = buildAnnotatedString {
    if (before != null) {
        withStyle(removedStyle()) { append(before) }
        append(" \u2192 ")
    }
    withStyle(addedStyle()) { append(after ?: "") }
    if (delta != null) append("  ($delta)")
}
