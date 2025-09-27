package com.github.m1cbppg.sqlexplainer.editor

import com.github.m1cbppg.sqlexplainer.icons.PluginIcons
import com.github.m1cbppg.sqlexplainer.sql.SqlSelectionAnalyzer
import com.github.m1cbppg.sqlexplainer.ui.ExplainPopup
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager

/**
 * Attaches a selection listener to each editor and shows a gutter icon on the
 * first line of the current selection. Clicking the icon triggers local SQL
 * detection for the selected text and displays a hint with the result.
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
        removeExistingHighlighter(editor)
    }

    private fun updateInlayForSelection(editor: Editor) {
        val selectionModel = editor.selectionModel
        val document = editor.document

        if (!selectionModel.hasSelection()) {
            removeExistingHighlighter(editor)
            return
        }

        val startOffset = selectionModel.selectionStart.coerceAtMost(document.textLength)
        val line = document.getLineNumber(startOffset)
        val existing = editor.getUserData(HIGHLIGHTER_KEY)
        if (existing != null && existing.isValid) {
            val existingLine = document.getLineNumber(existing.startOffset)
            if (existingLine == line) {
                return
            }
        }

        removeExistingHighlighter(editor)

        val highlighter = editor.markupModel.addLineHighlighter(
            line,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            null as TextAttributes?
        )
        if (highlighter != null) {
            highlighter.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon() = PluginIcons.selection
                override fun getAlignment() = Alignment.LEFT
                override fun getTooltipText(): String = "SQL 解释：点击分析选中 SQL"
                override fun isNavigateAction(): Boolean = true
                override fun getClickAction(): com.intellij.openapi.actionSystem.AnAction? = object : AnAction("Analyze SQL") {
                    override fun actionPerformed(e: AnActionEvent) {
                        val project = editor.project ?: return
                        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
                        val result = SqlSelectionAnalyzer.analyze(editor, psiFile)
                        if (result.isSql) {
                            val sql = editor.selectionModel.selectedText?.trim() ?: return
                            // Show streaming popup and start explaining
                            ExplainPopup.show(project, editor, sql)
                        } else {
                            val message = "未检测到 SQL 相关内容：" + result.reason
                            HintManager.getInstance().showInformationHint(editor, message)
                        }
                    }
                }
                override fun equals(other: Any?): Boolean = other === this
                override fun hashCode(): Int = System.identityHashCode(this)
            }
            editor.putUserData(HIGHLIGHTER_KEY, highlighter)
        }
    }

    private fun removeExistingHighlighter(editor: Editor) {
        val existing = editor.getUserData(HIGHLIGHTER_KEY)
        if (existing != null && existing.isValid) {
            existing.dispose()
        }
        editor.putUserData(HIGHLIGHTER_KEY, null)
    }

    companion object {
        private val SELECTION_LISTENER_KEY = Key.create<SelectionListener>("sql-explainer.selection-listener")
        private val HIGHLIGHTER_KEY = Key.create<RangeHighlighter>("sql-explainer.selection-gutter-highlighter")
    }
}
