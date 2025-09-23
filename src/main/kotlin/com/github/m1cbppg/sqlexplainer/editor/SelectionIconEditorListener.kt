package com.github.m1cbppg.sqlexplainer.editor

import com.github.m1cbppg.sqlexplainer.icons.PluginIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.util.Key
import com.intellij.codeInsight.hint.HintManager
import com.intellij.psi.PsiDocumentManager
import com.github.m1cbppg.sqlexplainer.sql.SqlSelectionAnalyzer

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

        // Mouse listener for click handling on the inlay icon
        val mouseListener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                if (event.area != EditorMouseEventArea.EDITING_AREA) return
                if (event.editor != editor) return
                val inlay = editor.getUserData(INLAY_KEY) ?: return

                val isHit = when {
                    event.inlay == inlay -> true
                    else -> inlay.bounds?.contains(event.mouseEvent.point) == true
                }
                if (!isHit) return

                val project = editor.project ?: return
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return

                val result = SqlSelectionAnalyzer.analyze(editor, psiFile)
                val message = if (result.isSql) {
                    "检测到 SQL 片段：" + result.reason
                } else {
                    "未检测到 SQL 相关内容：" + result.reason
                }
                HintManager.getInstance().showInformationHint(editor, message)
            }
        }
        editor.addEditorMouseListener(mouseListener)
        editor.putUserData(MOUSE_LISTENER_KEY, mouseListener)

        // Optional: change cursor to hand when hovering over the inlay
        val motionListener = object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                if (e.editor != editor) return
                val inlay = editor.getUserData(INLAY_KEY)
                val over = inlay != null && (e.inlay == inlay || inlay.bounds?.contains(e.mouseEvent.point) == true)
                val comp = editor.contentComponent
                comp.cursor = if (over) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                else java.awt.Cursor.getDefaultCursor()
            }
        }
        editor.addEditorMouseMotionListener(motionListener)
        editor.putUserData(MOUSE_MOTION_LISTENER_KEY, motionListener)

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
        editor.getUserData(MOUSE_LISTENER_KEY)?.let {
            editor.removeEditorMouseListener(it)
            editor.putUserData(MOUSE_LISTENER_KEY, null)
        }
        editor.getUserData(MOUSE_MOTION_LISTENER_KEY)?.let {
            editor.removeEditorMouseMotionListener(it)
            editor.putUserData(MOUSE_MOTION_LISTENER_KEY, null)
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
        private val MOUSE_LISTENER_KEY = Key.create<EditorMouseListener>("sql-explainer.mouse-listener")
        private val MOUSE_MOTION_LISTENER_KEY = Key.create<EditorMouseMotionListener>("sql-explainer.mouse-motion-listener")
    }
}
