package com.github.m1cbppg.sqlexplainer.sql

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.openapi.util.TextRange

/**
 * Heuristic, local (no-network) SQL detection for selected text.
 * Tries to cover common appearances: raw SQL, string literals in annotations (e.g., MyBatis @Select, Spring @Query),
 * and MyBatis Mapper XML. Lambda-style builder SQL (e.g., MyBatis-Plus) is intentionally out-of-scope.
 */
object SqlSelectionAnalyzer {

    data class Result(val isSql: Boolean, val reason: String)

    private val VERBS = listOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH",
        "CREATE", "ALTER", "DROP", "TRUNCATE", "REPLACE", "UPSERT",
        "CALL", "GRANT", "REVOKE", "EXPLAIN", "DESCRIBE", "SHOW"
    )

    private val AUX = listOf(
        "FROM", "WHERE", "JOIN", "ON", "INTO", "VALUES", "GROUP", "ORDER", "BY",
        "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT"
    )

    private val ANNO_HINTS = listOf(
        // MyBatis
        "@Select", "@Update", "@Insert", "@Delete",
        "@SelectProvider", "@UpdateProvider", "@InsertProvider", "@DeleteProvider",
        // Spring Data / JPA / Micronaut Data
        "@Query", "@NativeQuery", "@NamedQuery", "@NamedNativeQuery", "@NamedQueries", "@NamedNativeQueries",
        // Hibernate specific
        "@SQLInsert", "@SQLUpdate", "@SQLDelete", "@SQLDeleteAll",
        // JDBI
        "@SqlQuery", "@SqlUpdate", "@SqlBatch", "@SqlScript"
    )

    private val XML_HINTS = listOf(
        "<select", "</select", "<update", "</update", "<insert", "</insert", "<delete", "</delete",
        // MyBatis dynamic SQL helpers
        "<where", "</where", "<set", "</set", "<trim", "</trim", "<foreach", "</foreach",
        "<choose", "</choose", "<when", "</when", "<otherwise", "</otherwise",
        "<bind", "</bind", "<include", "</include", "<sql", "</sql", "<if", "</if"
    )

    private val METHOD_CONTEXT_HINTS = listOf(
        // Spring JDBC
        "jdbcTemplate.query(", "jdbcTemplate.update(", "jdbcTemplate.batchUpdate(", "jdbcTemplate.execute(",
        "namedParameterJdbcTemplate.query(", "namedParameterJdbcTemplate.update(", "namedParameterJdbcTemplate.batchUpdate(",
        // JPA / Hibernate programmatic
        "entityManager.createNativeQuery(", "entityManager.createQuery(",
        "session.createSQLQuery(", "session.createNativeQuery(",
        // Apache DbUtils
        "queryRunner.query(", "queryRunner.update(",
        // R2DBC
        "databaseClient.sql("
    )

    fun analyze(editor: Editor, psiFile: PsiFile): Result {
        val selectionModel = editor.selectionModel
        val raw = selectionModel.selectedText?.trim() ?: return Result(false, "未选择文本")
        if (raw.length < 3) return Result(false, "选择内容过短")

        val around = safeAroundText(editor, 200)

        // Normalize common containers: quoted strings and XML CDATA
        val text = normalize(raw)

        // XML hints based on surroundings and selection
        val isXmlFile = psiFile.fileType.name.equals("XML", ignoreCase = true) || psiFile.name.endsWith(".xml", true)
        if (isXmlFile) {
            // If it's a MyBatis mapper, the SQL often sits inside <select>/<update>/...
            if (XML_HINTS.any { around.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true) }) {
                if (looksLikeSql(text)) return Result(true, "XML Mapper 中的 SQL 片段")
                // 即使当前选中仅为动态标签或占位符，仍视为 SQL 相关
                if (isMyBatisParam(text) || containsXmlDynamicTag(text)) return Result(true, "XML Mapper 动态 SQL 片段/占位符")
            }
        }

        // Annotation context hints (works for Java/Kotlin without PSI deps)
        if (ANNO_HINTS.any { around.contains(it, ignoreCase = true) }) {
            if (looksLikeSql(text)) return Result(true, "注解字符串中的 SQL/JPQL 片段 (${nearestAnno(around)})")
            // Provider / 命名查询场景，允许将相关语句片段判定为 SQL 相关
            return Result(true, "与注解 (${nearestAnno(around)}) 关联的 SQL 相关内容")
        }

        // Method call context hints (e.g., jdbcTemplate/entityManager/DbUtils)
        if (METHOD_CONTEXT_HINTS.any { around.contains(it, ignoreCase = true) }) {
            if (looksLikeSql(text)) return Result(true, "数据访问方法调用中的 SQL 片段 (${nearestMethod(around)})")
            // 若上下文强烈指向 SQL 方法，即便文本为片段也视为相关
            if (isLikelySqlFragment(text)) return Result(true, "数据访问方法调用中的 SQL 相关片段 (${nearestMethod(around)})")
        }

        // General, raw SQL detection
        if (looksLikeSql(text)) return Result(true, "检测到常见 SQL 关键字与结构")

        // Not detected
        return Result(false, "未匹配到 SQL 关键字/结构或上下文线索")
    }

    private fun looksLikeSql(text: String): Boolean {
        val up = text.toUpperCase()

        // Early exit: must include at least one SQL verb
        val hasVerb = VERBS.any { regexWord(up, it) }
        if (!hasVerb) return false

        // Reinforce with auxiliary tokens or typical punctuation balance
        val hasAux = AUX.count { regexWord(up, it) } >= 1
        val hasSemi = up.contains(';')
        val hasJoinFromWhere = listOf("FROM", "WHERE", "JOIN").count { regexWord(up, it) }

        // Any of these signals are good enough combined with verb
        if (hasAux || hasSemi || hasJoinFromWhere >= 1) return true

        // Fallback: presence of parentheses often in SQL (e.g., INSERT INTO t(a,b) VALUES ...)
        val paren = up.count { it == '(' } >= 1 && up.count { it == ')' } >= 1
        return paren
    }

    private fun regexWord(text: String, token: String): Boolean {
        // Fast word-boundary check without regex engine; treat '_' as word char
        val idx = text.indexOf(token)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !isWordChar(text[idx - 1])
        val afterOk = idx + token.length >= text.length || !isWordChar(text[idx + token.length])
        return beforeOk && afterOk
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun normalize(raw: String): String {
        var t = raw.trim()
        // Strip wrapping quotes if present (Kotlin/Java style)
        if (t.length >= 6 && t.startsWith("\"\"\"") && t.endsWith("\"\"\"")) {
            t = t.substring(3, t.length - 3)
        } else if (t.length >= 2 &&
            ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))
        ) {
            t = t.substring(1, t.length - 1)
        }
        // Handle common string literal escapes for multi-line SQL
        t = t.replace("\\n", "\n").replace("\\t", "\t")
        // Remove Java/Kotlin string concat artifacts like "..." + "..."
        t = t.replace(Regex("\"\\s*\\+\\s*\""), "")
        t = t.replace(Regex("'\\s*\\+\\s*'"), "")
        // Trim XML CDATA markers if the selection included them
        if (t.startsWith("<![CDATA[")) t = t.removePrefix("<![CDATA[")
        if (t.endsWith("]]>")) t = t.removeSuffix("]]>" )
        // Decode simple XML entities often found in XML-based SQL
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return t.trim()
    }

    private fun safeAroundText(editor: Editor, radius: Int): String {
        val doc = editor.document
        val selStart = editor.selectionModel.selectionStart
        val selEnd = editor.selectionModel.selectionEnd
        val start = (selStart - radius).coerceAtLeast(0)
        val end = (selEnd + radius).coerceAtMost(doc.textLength)
        return try {
            doc.getText(TextRange(start, end))
        } catch (_: Throwable) {
            ""
        }
    }

    private fun nearestAnno(around: String): String {
        val idx = ANNO_HINTS.map { it to around.indexOf(it, ignoreCase = true) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }?.first
        return idx ?: "@?"
    }

    private fun nearestMethod(around: String): String {
        val idx = METHOD_CONTEXT_HINTS.map { it to around.indexOf(it, ignoreCase = true) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }?.first
        return idx ?: "method?"
    }

    private fun isMyBatisParam(text: String): Boolean {
        val hasHashParam = text.contains("#{") || containsDollarBrace(text)
        val hasXmlPlaceholders = text.contains("<![CDATA[") || text.contains("</") || text.contains("<if", ignoreCase = true)
        return hasHashParam || hasXmlPlaceholders
    }

    private fun containsXmlDynamicTag(text: String): Boolean =
        XML_HINTS.any { text.contains(it, ignoreCase = true) }

    private fun isLikelySqlFragment(text: String): Boolean {
        val up = text.toUpperCase()
        // fragments like column lists, conditions, joins, placeholder parameters
        if (up.contains("WHERE ") || up.contains(" FROM ") || up.contains(" JOIN ")) return true
        if (up.contains("= :") || up.contains(":") || up.contains("?")) return true
        if (up.contains("#{") || containsDollarBrace(up)) return true
        // comma-separated identifiers typical for column lists
        if ("," in text && text.count { it.isLetterOrDigit() } >= 1) return true
        return false
    }

    private fun containsDollarBrace(text: String): Boolean {
        val idx = text.indexOf('$')
        return idx >= 0 && idx + 1 < text.length && text[idx + 1] == '{'
    }
}
