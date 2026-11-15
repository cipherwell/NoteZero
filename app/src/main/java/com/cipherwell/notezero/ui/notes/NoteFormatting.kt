package com.cipherwell.notezero.ui.notes

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers for the notes list. Deliberately free of Android/Compose
 * imports so they can be unit-tested on the JVM with a fixed clock.
 */
object NoteFormatting {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** Human relative time, e.g. "Just now", "5 min ago", "Yesterday", "3 days ago", "15 Nov". */
    fun relativeTime(updatedAt: Long, now: Long): String {
        if (updatedAt <= 0L) return ""
        val delta = (now - updatedAt).coerceAtLeast(0L)
        return when {
            delta < MINUTE -> "Just now"
            delta < HOUR -> "${delta / MINUTE} min ago"
            delta < DAY -> "${delta / HOUR} h ago"
            delta < 2 * DAY -> "Yesterday"
            delta < 7 * DAY -> "${delta / DAY} days ago"
            else -> formatDate(updatedAt, now)
        }
    }

    private fun formatDate(timestamp: Long, now: Long): String {
        val date = Date(timestamp)
        val current = Date(now)
        val sameYear = SimpleDateFormat("yyyy", Locale.US).format(date) ==
            SimpleDateFormat("yyyy", Locale.US).format(current)
        val pattern = if (sameYear) "d MMM" else "d MMM yyyy"
        return SimpleDateFormat(pattern, Locale.US).format(date)
    }

    /**
     * One-line, syntax-stripped preview of a Markdown body for list rows.
     * Never returns Markdown markup for the common block/inline constructs.
     */
    fun preview(body: String, maxLength: Int = 140): String {
        if (body.isBlank()) return ""
        val joined = body.lineSequence()
            .map { stripBlockSyntax(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return truncate(stripInlineSyntax(joined), maxLength)
    }

    private fun stripBlockSyntax(line: String): String {
        var value = line.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("```")) return ""
        value = value.replace(Regex("^#{1,6}\\s+"), "")
        value = value.replace(Regex("^[-*+]\\s+\\[[ xX]]\\s+"), "")
        value = value.replace(Regex("^[-*+]\\s+"), "")
        value = value.replace(Regex("^\\d+\\.\\s+"), "")
        value = value.replace(Regex("^>\\s?"), "")
        if (value.matches(Regex("^[-*_]{3,}$"))) return ""
        return value
    }

    private fun stripInlineSyntax(text: String): String {
        if (text.isEmpty()) return ""
        var value = text.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)")) { match ->
            match.groupValues[1]
        }
        value = value.replace(Regex("[*_~`]+"), "")
        return value.trim()
    }

    private fun truncate(text: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        if (text.length <= maxLength) return text
        return text.take(maxLength - 1).trimEnd() + "…"
    }

    /** Display title for a note that may not have been titled yet. */
    fun displayTitle(title: String): String = title.trim().ifEmpty { "New Note" }
}
