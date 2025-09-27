package com.github.m1cbppg.sqlexplainer.ui

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.swing.*

/**
 * Popup that connects to /explain/stream and live-renders the result.
 * - Shows spinner and "正在分析…" while streaming
 * - Streams summary and normalized SQL incrementally
 * - Populates warnings/functions/tables/relationships on final JSON
 * - Deduplicates list items
 */
object ExplainPopup {
    private val LOG = Logger.getInstance(ExplainPopup::class.java)
    private val gson = Gson()

    data class ExplainFunction(
        val name: String? = null,
        val description: String? = null,
        val notes: String? = null,
    )

    data class ExplainRelationship(
        val left: String? = null,
        val right: String? = null,
        @SerializedName("join_type") val joinType: String? = null,
        val predicates: List<String>? = null,
        @SerializedName("predicates_count") val predicatesCount: Int? = null,
        val cardinality: String? = null,
    )

    data class ExplainResponse(
        val status: String? = null,
        val summary: String? = null,
        @SerializedName("normalized_sql") val normalizedSql: String? = null,
        val warnings: List<String>? = null,
        val functions: List<ExplainFunction>? = null,
        val tables: List<String>? = null,
        val relationships: List<ExplainRelationship>? = null,
    )

    fun show(project: Project, editor: Editor, sql: String) {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Header
        val header = JBPanel<JBPanel<*>>(BorderLayout())
        val leftHeader = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        val spinner = JLabel(AnimatedIcon.Default())
        val title = JBLabel("SQL Explainer").apply { font = JBFont.medium().asBold() }
        val subtitle = JBLabel("正在分析…").apply { foreground = JBColor.GRAY }
        leftHeader.add(spinner)
        leftHeader.add(title)
        leftHeader.add(subtitle)
        val closeBtn = JButton(object : AbstractAction("关闭") {
            override fun actionPerformed(e: ActionEvent) {
                SwingUtilities.getWindowAncestor(panel)?.isVisible = false
                popupRef?.cancel()
            }
        }).apply {
            putClientProperty("JButton.buttonType", "roundRect")
            isFocusable = false
        }
        header.add(leftHeader, BorderLayout.WEST)
        header.add(closeBtn, BorderLayout.EAST)

        // Sections
        val content = JBPanel<JBPanel<*>>(VerticalLayout(JBUIScale.scale(8)))
        content.border = JBUI.Borders.emptyTop(6)

        // Summary
        val summaryArea = makeTextArea()
        val summaryCard = card("总结", summaryArea)

        // Normalized SQL (monospace)
        val sqlArea = makeTextArea(Font("Monospaced", Font.PLAIN, UIUtil.getLabelFont().size))
        val sqlCard = card("标准化 SQL", sqlArea)

        // Warnings
        val warningsList = JBPanel<JBPanel<*>>(VerticalLayout(JBUIScale.scale(4)))
        val warningsCard = card("注意事项", JBScrollPane(warningsList))

        // Functions
        val functionsList = JBPanel<JBPanel<*>>(VerticalLayout(JBUIScale.scale(4)))
        val functionsCard = card("函数", JBScrollPane(functionsList))

        // Tables
        val tablesPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 6))
        val tablesCard = card("涉及表", tablesPanel)

        // Relationships
        val relList = JBPanel<JBPanel<*>>(VerticalLayout(JBUIScale.scale(4)))
        val relCard = card("关联关系", JBScrollPane(relList))

        content.add(summaryCard)
        content.add(sqlCard)
        content.add(warningsCard)
        content.add(functionsCard)
        content.add(tablesCard)
        content.add(relCard)

        panel.add(header, BorderLayout.NORTH)
        panel.add(content, BorderLayout.CENTER)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, summaryArea)
            .setTitle(null)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnOtherWindowOpen(true)
            .setRequestFocus(true)
            .setMinSize(Dimension(JBUIScale.scale(440), JBUIScale.scale(360)))
            .createPopup()

        popupRef = popup
        popup.showInBestPositionFor(editor)

        // Start streaming in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                streamExplain(
                    sql,
                    onSummary = { text -> invokeLater { summaryArea.text = text } },
                    onNormalized = { text -> invokeLater { sqlArea.text = text } },
                    onWarning = { w -> invokeLater {
                        if (!hasChildWithText(warningsList, "• $w")) {
                            warningsList.add(bullet("$w"))
                            warningsList.revalidate(); warningsList.repaint()
                        }
                    } },
                    onComplete = { full -> invokeLater {
                        // Finalize core fields in case streaming missed some
                        if (summaryArea.text.isBlank() && !full.summary.isNullOrBlank()) summaryArea.text = full.summary
                        if (sqlArea.text.isBlank() && !full.normalizedSql.isNullOrBlank()) sqlArea.text = full.normalizedSql

                        // Populate lists, dedup
                        full.warnings?.forEach { w -> if (!hasChildWithText(warningsList, "• $w")) warningsList.add(bullet(w)) }
                        full.functions?.forEach { f ->
                            val row = when {
                                !f.name.isNullOrBlank() && !f.description.isNullOrBlank() -> "${f.name}: ${f.description}"
                                !f.name.isNullOrBlank() -> f.name
                                else -> return@forEach
                            }
                            if (!hasChildWithText(functionsList, "• $row")) functionsList.add(bullet(row))
                        }
                        full.tables?.forEach { t -> if (!hasChipWithText(tablesPanel, t)) tablesPanel.add(chip(t)) }
                        full.relationships?.forEach { r -> relList.add(JBLabel(formatRel(r))) }

                        warningsList.revalidate(); warningsList.repaint()
                        functionsList.revalidate(); functionsList.repaint()
                        tablesPanel.revalidate(); tablesPanel.repaint()
                        relList.revalidate(); relList.repaint()

                        subtitle.text = "分析完成"
                        spinner.isVisible = false
                    } },
                    onError = { err -> invokeLater {
                        subtitle.text = "分析失败"
                        spinner.isVisible = false
                        warningsList.add(bullet("请求失败：$err"))
                        warningsList.revalidate(); warningsList.repaint()
                    } }
                )
            } catch (t: Throwable) {
                LOG.warn("Explain stream exception", t)
                invokeLater {
                    subtitle.text = "分析失败"
                    spinner.isVisible = false
                    warningsList.add(bullet("异常：${t.message}"))
                    warningsList.revalidate(); warningsList.repaint()
                }
            }
        }
    }

    private fun card(title: String, center: JComponent): JBPanel<*> =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            background = UIUtil.getPanelBackground()
            add(JBLabel(title).apply {
                font = JBFont.small().asBold()
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(6, 6, 0, 6)
            }, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
        }

    private fun makeTextArea(font: Font? = null) = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        border = JBUI.Borders.empty(6)
        background = UIUtil.getPanelBackground()
        if (font != null) this.font = font
    }

    private fun bullet(text: String): JComponent = JBLabel("• $text").apply {
        border = JBUI.Borders.empty(0, 6, 0, 6)
    }

    private fun chip(text: String): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val lab = JBLabel(text)
        lab.border = JBUI.Borders.empty(3, 8)
        add(lab, BorderLayout.CENTER)
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        background = UIUtil.getPanelBackground()
    }

    private fun hasChildWithText(container: JComponent, text: String): Boolean {
        for (i in 0 until container.componentCount) {
            val c = container.getComponent(i)
            if (c is JLabel && c.text == text) return true
        }
        return false
    }

    private fun hasChipWithText(container: JComponent, text: String): Boolean {
        for (i in 0 until container.componentCount) {
            val p = container.getComponent(i)
            if (p is JComponent && p.componentCount > 0 && p.getComponent(0) is JLabel) {
                val lbl = p.getComponent(0) as JLabel
                if (lbl.text == text) return true
            }
        }
        return false
    }

    private fun formatRel(r: ExplainRelationship): String {
        val lr = listOfNotNull(r.left, r.joinType, r.right).joinToString(" ")
        val preds = r.predicates?.joinToString(" AND ")
        return if (preds.isNullOrBlank()) lr else "$lr ON $preds"
    }

    private fun invokeLater(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    @Volatile private var popupRef: JBPopup? = null

    // Core streaming client. Performs two passes over incoming chars:
    // 1) Full JSON object collector: detects complete {...} objects and parses them.
    //    Ignores status-only objects; completes once on the first non-status object.
    // 2) Minimal incremental key/value extractor for summary, normalized_sql, warnings.
    private fun streamExplain(
        sql: String,
        onSummary: (String) -> Unit,
        onNormalized: (String) -> Unit,
        onWarning: (String) -> Unit,
        onComplete: (ExplainResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val url = URL("http://127.0.0.1:9091/explain/stream")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            readTimeout = 0
            connectTimeout = 5000
        }
        val payload = gson.toJson(mapOf("text" to sql))
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))

        // Aggregator for final JSON objects
        val jsonBuf = StringBuilder()
        var jsonDepth = 0
        var inStringJson = false
        var escapeJson = false
        var startedJson = false
        var completed = false

        fun feedJson(s: String) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                jsonBuf.append(c)
                if (escapeJson) {
                    escapeJson = false
                } else if (c == '\\') {
                    escapeJson = true
                } else if (c == '"') {
                    inStringJson = !inStringJson
                } else if (!inStringJson) {
                    when (c) {
                        '{' -> { jsonDepth++; startedJson = true }
                        '}' -> {
                            jsonDepth--
                            if (startedJson && jsonDepth == 0) {
                                val text = jsonBuf.toString().trim()
                                try {
                                    val resp = gson.fromJson(text, ExplainResponse::class.java)
                                    val hasPayload = !resp.summary.isNullOrBlank() || !resp.normalizedSql.isNullOrBlank() ||
                                        (resp.tables?.isNotEmpty() == true) || (resp.functions?.isNotEmpty() == true) ||
                                        (resp.relationships?.isNotEmpty() == true) || (resp.warnings?.isNotEmpty() == true)
                                    if (hasPayload && !completed) {
                                        completed = true
                                        onComplete(resp)
                                    }
                                } catch (_: Throwable) {
                                    // ignore malformed partial
                                } finally {
                                    jsonBuf.setLength(0)
                                    inStringJson = false
                                    escapeJson = false
                                    startedJson = false
                                    jsonDepth = 0
                                }
                            }
                        }
                    }
                }
                i++
            }
        }

        // Minimal incremental extractor for selected fields
        val summaryBuf = StringBuilder()
        val normalizedBuf = StringBuilder()
        val warningBuf = StringBuilder()
        var inStringStream = false
        var escapeStream = false
        var readingKey = false
        var currentKey: String? = null
        val keyBuf = StringBuilder()
        var afterColon = false
        var inSummary = false
        var inNormalized = false
        var inWarningsArray = false
        var inOneWarning = false

        fun feedStream(s: String) {
            var i = 0
            while (i < s.length) {
                val c = s[i]

                // pass-through to JSON collector
                feedJson(c.toString())

                if (escapeStream) {
                    escapeStream = false
                } else if (c == '\\') {
                    escapeStream = true
                } else if (c == '"') {
                    inStringStream = !inStringStream
                    if (inStringStream && !readingKey && !afterColon) {
                        readingKey = true
                        keyBuf.setLength(0)
                    } else if (!inStringStream && readingKey) {
                        readingKey = false
                    }
                } else if (readingKey) {
                    keyBuf.append(c)
                } else if (!inStringStream && c == ':') {
                    afterColon = true
                    currentKey = keyBuf.toString()
                } else if (afterColon) {
                    when (c) {
                        ' ', '\t', '\r', '\n' -> {}
                        '"' -> {
                            inStringStream = true
                            when (currentKey) {
                                "summary" -> inSummary = true
                                "normalized_sql" -> inNormalized = true
                            }
                            afterColon = false
                        }
                        '[' -> {
                            if (currentKey == "warnings") inWarningsArray = true
                            afterColon = false
                        }
                        else -> afterColon = false
                    }
                } else {
                    if (inStringStream) {
                        if (c == '"') {
                            // end of string value
                            when {
                                inSummary -> inSummary = false
                                inNormalized -> inNormalized = false
                                inWarningsArray && inOneWarning -> {
                                    inOneWarning = false
                                    val w = warningBuf.toString()
                                    warningBuf.setLength(0)
                                    if (w.isNotBlank()) onWarning(w)
                                }
                            }
                            inStringStream = false
                        } else {
                            when {
                                inSummary -> { summaryBuf.append(c); onSummary(summaryBuf.toString()) }
                                inNormalized -> { normalizedBuf.append(c); onNormalized(normalizedBuf.toString()) }
                                inWarningsArray && inOneWarning -> warningBuf.append(c)
                            }
                        }
                    } else if (inWarningsArray) {
                        when (c) {
                            ']' -> inWarningsArray = false
                            '"' -> { inStringStream = true; inOneWarning = true }
                        }
                    }
                }
                i++
            }
        }

        // Read SSE
        reader.use { br ->
            var line: String?
            while (true) {
                line = br.readLine() ?: break
                if (line!!.startsWith("data:")) {
                    // do NOT trim: spaces inside JSON string are meaningful
                    val payload = line!!.substring(5)
                    if (payload.isEmpty()) continue
                    feedStream(payload)
                } else if (line!!.startsWith("{") || line!!.startsWith("}")) {
                    // some servers print final JSON after SSE blocks; feed it too
                    feedStream(line!!)
                }
            }
        }
    }
}

