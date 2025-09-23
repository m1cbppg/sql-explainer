package com.github.m1cbppg.sqlexplainer.editor

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.EditorCustomElementRenderer
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Icon

/**
 * Simple block inlay renderer that paints a small icon above the line.
 * It does not occupy gutter space and so does not conflict with other plugins' gutter icons.
 */
class SelectionIconRenderer(private val icon: Icon) : EditorCustomElementRenderer {

    private val paddingX = 4
    private val paddingY = 2

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // Width is not strictly used for block inlays, but return icon width + padding to be safe.
        return icon.iconWidth + paddingX * 2
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return icon.iconHeight + paddingY * 2
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        val x = targetRegion.x + paddingX
        val y = targetRegion.y + (targetRegion.height - icon.iconHeight) / 2
        icon.paintIcon(null, g, x, y)
    }
}

