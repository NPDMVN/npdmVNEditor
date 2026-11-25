package com.dmedit.core.nativebridge

import com.dmedit.core.model.DataFormat
import com.dmedit.core.model.DiffEntry
import com.dmedit.core.model.ValidationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Stateful editing session backed by Rust (`RustCoreEngine`) for lossless JSON patching,
 * formatting, validation, and structural diff calculation, plus in-memory Undo/Redo history.
 */
class RustDocumentSession(
    private val rustCore: RustCoreEngine,
    val fileName: String,
    val format: DataFormat,
    initialContent: String,
    private val maxHistory: Int = 50
) {
    private val parserJson = Json { ignoreUnknownKeys = true }

    var originalText: String = initialContent
        private set
    var currentText: String = initialContent
        private set

    var currentJson: JsonElement? = tryParseJson(initialContent)
        private set

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    val isDirty: Boolean get() = currentText != originalText
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun markSaved() {
        originalText = currentText
    }

    fun updateRawText(newText: String, pushHistory: Boolean = true) {
        if (newText == currentText) return
        if (pushHistory) pushUndo(currentText)
        currentText = newText
        currentJson = tryParseJson(newText)
    }

    fun patchJsonPath(path: String, newValue: JsonElement): Boolean {
        val updated = rustCore.patchJsonPath(currentText, path, newValue)
        if (updated == currentText) return false
        pushUndo(currentText)
        currentText = updated
        currentJson = tryParseJson(updated)
        return true
    }

    fun patchMapEntry(mapPath: String, key: String, newValue: JsonElement): Boolean {
        val updated = rustCore.patchMapEntry(currentText, mapPath, key, newValue)
        if (updated == currentText) return false
        pushUndo(currentText)
        currentText = updated
        currentJson = tryParseJson(updated)
        return true
    }

    fun batchSetMap(mapPath: String, newValue: JsonElement): Boolean {
        val updated = rustCore.batchSetMap(currentText, mapPath, newValue)
        if (updated == currentText) return false
        pushUndo(currentText)
        currentText = updated
        currentJson = tryParseJson(updated)
        return true
    }

    fun batchUpdateNestedPairs(
        mapPath: String,
        groupKey: String?,
        valueField: String,
        newValue: JsonElement
    ): Boolean {
        val updated = rustCore.batchUpdateNestedPairs(currentText, mapPath, groupKey, valueField, newValue)
        if (updated == currentText) return false
        pushUndo(currentText)
        currentText = updated
        currentJson = tryParseJson(updated)
        return true
    }

    fun mergeJsonPreset(
        targetPath: String,
        presetElement: JsonElement,
        overwriteExisting: Boolean,
        dedupeKey: String? = null
    ): Boolean {
        val updated = rustCore.mergeJsonPreset(
            jsonText = currentText,
            targetPath = targetPath,
            presetElement = presetElement,
            overwriteExisting = overwriteExisting,
            dedupeKey = dedupeKey
        )
        if (updated == currentText) return false
        pushUndo(currentText)
        currentText = updated
        currentJson = tryParseJson(updated)
        return true
    }

    fun beautify(): Result<String> {
        if (format != DataFormat.JSON) return Result.success(currentText)
        return rustCore.beautifyJson(currentText).onSuccess { pretty ->
            updateRawText(pretty, pushHistory = true)
        }
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(currentText)
        currentText = prev
        currentJson = tryParseJson(prev)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(currentText)
        currentText = next
        currentJson = tryParseJson(next)
        return true
    }

    fun validate(): ValidationResult = rustCore.validateContent(currentText, format)

    fun computeDiffs(): List<DiffEntry> = rustCore.diffTexts(originalText, currentText)

    private fun pushUndo(snapshot: String) {
        undoStack.addLast(snapshot)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun tryParseJson(text: String): JsonElement? {
        if (format != DataFormat.JSON) return null
        return runCatching { parserJson.parseToJsonElement(text) }.getOrNull()
    }
}
