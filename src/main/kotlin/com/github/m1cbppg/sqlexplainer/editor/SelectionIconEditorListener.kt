package com.github.m1cbppg.sqlexplainer.editor

import com.github.m1cbppg.sqlexplainer.icons.PluginIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.util.Key

/**
 * Attaches a selection listener to each editor and shows a block inlay icon
 * above the first line of the current selection. The inlay is placed in the
 * editor content area (not the gutter) to avoid conflicts with other plugins
 * like GitHub Copilot that use gutter icons.
 */
class SelectionIconEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val listener = object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                updateInlayForSelection(editor)
            }
        }

        editor.selectionModel.addSelectionListener(listener)
        editor.putUserData(SELECTION_LISTENER_KEY, listener)

        // Initialize state in case selection already exists
        updateInlayForSelection(editor)

    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        // Remove listener if present
        editor.getUserData(SELECTION_LISTENER_KEY)?.let {
            editor.selectionModel.removeSelectionListener(it)
            editor.putUserData(SELECTION_LISTENER_KEY, null)
        }
        removeExistingInlay(editor)
    }

    private fun updateInlayForSelection(editor: Editor) {
        val selectionModel = editor.selectionModel
        val document = editor.document

        if (!selectionModel.hasSelection()) {
            removeExistingInlay(editor)
            return
        }

        val startOffset = selectionModel.selectionStart.coerceAtMost(document.textLength)
        val line = document.getLineNumber(startOffset)
        val lineStartOffset = document.getLineStartOffset(line)

        val existing = editor.getUserData(INLAY_KEY)
        if (existing != null && existing.isValid && existing.offset == lineStartOffset) {
            // Already correct
            return
        }

        removeExistingInlay(editor)

        val renderer = SelectionIconRenderer(PluginIcons.selection)

        // Place a block inlay above the line start inside the editor content area.
        val inlay = editor.inlayModel.addBlockElement(
            lineStartOffset,
            /* showAbove = */ true,
            /* relatesToPrecedingText = */ true,
            /* priority = */ 0,
            renderer
        )

        if (inlay != null) {
            editor.putUserData(INLAY_KEY, inlay)
        }
    }

    private fun removeExistingInlay(editor: Editor) {
        val existing = editor.getUserData(INLAY_KEY)
        if (existing != null && existing.isValid) {
            existing.dispose()
        }
        editor.putUserData(INLAY_KEY, null)
    }

    companion object {
        private val INLAY_KEY = Key.create<com.intellij.openapi.editor.Inlay<*>>("sql-explainer.selection-inlay")
        private val SELECTION_LISTENER_KEY = Key.create<SelectionListener>("sql-explainer.selection-listener")
    }
}
