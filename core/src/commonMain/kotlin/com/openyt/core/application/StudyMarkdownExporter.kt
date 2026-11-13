package com.openyt.core.application

import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyRemovalPreview

/**
 * Describes whether the active host OS/platform can genuinely receive and
 * dispatch `openyt://library/entry/<id>?t=<ms>` clickable links.
 * Hosts must leave [supportsClickableEntryLinks] as false until OS dispatch
 * has been verified.
 */
data class StudyHostLinkCapability(
    val supportsClickableEntryLinks: Boolean,
    val hostLabel: String = "",
)

/** Options controlling Markdown study-note export formatting. */
data class StudyExportOptions(
    val includeLocalLinks: Boolean = false,
    val includeBookmarks: Boolean = true,
    val includeSavedRanges: Boolean = true,
    val language: AppLanguage = AppLanguage.EN,
)

/** Outcome of exporting Markdown study notes through a platform file picker. */
sealed interface TextExportOutcome {
    data class Exported(val destinationLabel: String) : TextExportOutcome
    data object Canceled : TextExportOutcome
    data class Failed(val message: String) : TextExportOutcome
}

/** Platform port for exporting generated Markdown study documents without filesystem access in core/ui. */
interface TextFileExporter {
    val canExport: Boolean
    suspend fun exportMarkdown(suggestedFilename: String, content: String): TextExportOutcome
}

/**
 * Pure Kotlin Markdown exporter for study notes, bookmarks, and saved A/B ranges.
 *
 * Security and privacy guarantees:
 * - Escapes untrusted title/author/note/bookmark text so injected HTML or active
 *   external Markdown links/images/scripts cannot become executable links.
 * - Never exports local filesystem paths, cookies, tokens, or remote tracking URLs.
 * - Only emits clickable `openyt://library/entry/...` links when the host explicitly
 *   supports and enables local deep-link dispatch.
 */
object StudyMarkdownExporter {
    private val RESERVED_WINDOWS_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    fun suggestedFilename(entryTitle: String): String {
        val cleaned = entryTitle
            .trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]+"""), "-")
            .replace(Regex("""\.{2,}"""), ".")
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-', '.')
            .take(64)
        val stem = if (cleaned.isEmpty() || cleaned.uppercase() in RESERVED_WINDOWS_NAMES) {
            "openyt-entry"
        } else {
            cleaned
        }
        return "$stem-study-notes.md"
    }

    fun formatTimestamp(millis: Long): String {
        val clamped = millis.coerceAtLeast(0L)
        val totalSeconds = clamped / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        }
    }

    fun formatInterval(startMillis: Long, endMillis: Long?): String {
        val start = formatTimestamp(startMillis)
        return if (endMillis != null && endMillis > startMillis) {
            "$start - ${formatTimestamp(endMillis)}"
        } else {
            start
        }
    }

    /**
     * Escapes inline Markdown and HTML metacharacters so untrusted metadata or note text
     * cannot inject clickable external links, raw HTML, scripts, images, or fake headings.
     */
    fun escapeMarkdownInline(raw: String): String {
        val singleLine = raw
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        return escapeMarkdownChars(singleLine)
    }

    /**
     * Escapes potentially multi-line user note text cleanly while preserving line breaks.
     */
    fun escapeNoteBlock(raw: String): String {
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return ""
        return normalized.lines().joinToString("  \n") { line ->
            escapeMarkdownChars(line.trimEnd())
        }
    }

    fun exportEntryBundle(
        entry: LibraryEntry,
        bundle: EntryStudyBundle,
        options: StudyExportOptions = StudyExportOptions(),
        hostCapability: StudyHostLinkCapability = StudyHostLinkCapability(supportsClickableEntryLinks = false),
    ): String = export(
        entryId = entry.id,
        title = entry.title,
        author = entry.author,
        isStudied = bundle.studyState.isStudied,
        notes = bundle.notes,
        bookmarks = if (options.includeBookmarks) bundle.bookmarks else emptyList(),
        savedRanges = if (options.includeSavedRanges) bundle.savedRanges else emptyList(),
        options = options,
        hostCapability = hostCapability,
    )

    fun exportRemovalPreview(
        preview: StudyRemovalPreview,
        options: StudyExportOptions = StudyExportOptions(),
        hostCapability: StudyHostLinkCapability = StudyHostLinkCapability(supportsClickableEntryLinks = false),
    ): String = export(
        entryId = preview.entryId,
        title = preview.entryTitle ?: preview.entryId,
        author = null,
        isStudied = preview.isMarkedStudied,
        notes = preview.notes,
        bookmarks = if (options.includeBookmarks) preview.bookmarks else emptyList(),
        savedRanges = if (options.includeSavedRanges) preview.savedRanges else emptyList(),
        options = options,
        hostCapability = hostCapability,
    )

    private fun export(
        entryId: String,
        title: String,
        author: String?,
        isStudied: Boolean,
        notes: List<StudyNote>,
        bookmarks: List<StudyBookmark>,
        savedRanges: List<SavedStudyRange>,
        options: StudyExportOptions,
        hostCapability: StudyHostLinkCapability,
    ): String {
        val pt = options.language == AppLanguage.PT_BR
        val emitLinks = options.includeLocalLinks && hostCapability.supportsClickableEntryLinks

        return buildString {
            append("# ")
            append(escapeMarkdownInline(title))
            appendLine()
            appendLine()
            author?.trim()?.takeIf { it.isNotEmpty() }?.let { safeAuthor ->
                append(if (pt) "- Autor: " else "- Author: ")
                appendLine(escapeMarkdownInline(safeAuthor))
            }
            append(if (pt) "- Identificador da biblioteca: " else "- Library entry ID: ")
            append('`').append(escapeCodeSpan(entryId)).appendLine('`')
            append(if (pt) "- Estado de estudo: " else "- Study status: ")
            appendLine(
                if (isStudied) {
                    if (pt) "Estudado" else "Studied"
                } else {
                    if (pt) "Em andamento" else "In progress"
                },
            )
            if (emitLinks) {
                OpenYtEntryLink.create(entryId, null)?.let { entryLink ->
                    append(if (pt) "- Abrir item no OpenYT: " else "- Open item in OpenYT: ")
                    append('[').append(if (pt) "Abrir na biblioteca" else "Open in library").append("](").append(entryLink).appendLine(')')
                }
            } else {
                append(if (pt) "- Links clicáveis locais: " else "- Clickable local links: ")
                appendLine(
                    if (pt) {
                        "não habilitados ou não verificados neste dispositivo (códigos de tempo preservados abaixo)."
                    } else {
                        "not enabled or unverified on this host (timecodes preserved below)."
                    },
                )
            }
            appendLine()

            appendLine(if (pt) "## Notas de estudo" else "## Study notes")
            appendLine()
            if (notes.isEmpty()) {
                appendLine(if (pt) "_Nenhuma nota registrada._" else "_No study notes recorded._")
            } else {
                notes.forEach { note ->
                    val label = formatInterval(note.timestampMillis, note.endMillis)
                    val formattedTime = formatTimeReference(entryId, note.timestampMillis, label, emitLinks)
                    append("- ").append(formattedTime).appendLine()
                    val body = escapeNoteBlock(note.body)
                    if (body.isNotEmpty()) {
                        body.lines().forEach { line ->
                            append("  ").appendLine(line)
                        }
                    }
                }
            }

            if (options.includeBookmarks) {
                appendLine()
                appendLine(if (pt) "## Marcadores de tempo" else "## Timestamp bookmarks")
                appendLine()
                if (bookmarks.isEmpty()) {
                    appendLine(if (pt) "_Nenhum marcador registrado._" else "_No bookmarks recorded._")
                } else {
                    bookmarks.forEach { bookmark ->
                        val label = formatTimestamp(bookmark.timestampMillis)
                        val formattedTime = formatTimeReference(entryId, bookmark.timestampMillis, label, emitLinks)
                        append("- ").append(formattedTime).append(": ").appendLine(escapeMarkdownInline(bookmark.label))
                    }
                }
            }

            if (options.includeSavedRanges) {
                appendLine()
                appendLine(if (pt) "## Trechos de repetição A/B" else "## Saved A/B repeat ranges")
                appendLine()
                if (savedRanges.isEmpty()) {
                    appendLine(if (pt) "_Nenhum trecho A/B salvo._" else "_No saved A/B ranges._")
                } else {
                    savedRanges.forEach { range ->
                        val label = formatInterval(range.startMillis, range.endMillis)
                        val formattedTime = formatTimeReference(entryId, range.startMillis, label, emitLinks)
                        append("- ").append(formattedTime)
                        range.label?.trim()?.takeIf { it.isNotEmpty() }?.let { safeLabel ->
                            append(": ").append(escapeMarkdownInline(safeLabel))
                        }
                        appendLine()
                    }
                }
            }
        }
    }

    private fun formatTimeReference(
        entryId: String,
        timestampMillis: Long,
        displayLabel: String,
        emitLinks: Boolean,
    ): String {
        val safeLabel = "[$displayLabel]"
        if (!emitLinks) return safeLabel
        val link = OpenYtEntryLink.create(entryId, timestampMillis) ?: return safeLabel
        return "[$safeLabel]($link)"
    }

    private fun escapeCodeSpan(raw: String): String =
        raw.filterNot { it.isISOControl() }.replace("`", "\\`")

    private fun escapeMarkdownChars(text: String): String {
        if (text.isEmpty()) return ""
        val escaped = buildString(text.length + 16) {
            for (ch in text) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '[' -> append("\\[")
                    ']' -> append("\\]")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    '`' -> append("\\`")
                    '*' -> append("\\*")
                    '_' -> append("\\_")
                    '!' -> append("\\!")
                    '#' -> append("\\#")
                    '|' -> append("\\|")
                    else -> if (!ch.isISOControl()) append(ch)
                }
            }
        }
        return neutralizeProtocolAutolinks(escaped)
    }

    private fun neutralizeProtocolAutolinks(text: String): String =
        text.replace(Regex("""(?i)\b(javascript|data|vbscript|file):""")) { match ->
            "${match.groupValues[1]}\\:"
        }
}
