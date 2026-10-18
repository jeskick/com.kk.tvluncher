package com.kk.tvlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * 左右只在当前行移动。任意一格按上或下，进入上一行或下一行的同一列。
 * 目标行比较短时，落到这一行的最后一格。
 */
class TvGridRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    /** 已经在第一行还按上。返回 true 表示外面已经接走焦点。 */
    var onLeaveUp: (() -> Boolean)? = null

    /** 已经在最后一行还按下。返回 true 表示外面已经接走焦点。 */
    var onLeaveDown: (() -> Boolean)? = null

    override fun focusSearch(focused: View, direction: Int): View? {
        val grid = layoutManager as? GridLayoutManager ?: return super.focusSearch(focused, direction)
        val pos = getChildAdapterPosition(focused)
        val count = adapter?.itemCount ?: 0
        if (pos == NO_POSITION || count <= 0) return super.focusSearch(focused, direction)
        val span = grid.spanCount.coerceAtLeast(1)
        val row = pos / span
        val col = pos % span
        val lastRow = (count - 1) / span
        when (direction) {
            FOCUS_LEFT -> {
                if (col == 0) return focused
                return show(grid, pos - 1)
            }
            FOCUS_RIGHT -> {
                val rowEnd = min((row + 1) * span, count) - 1
                if (pos >= rowEnd) return focused
                return show(grid, pos + 1)
            }
            FOCUS_UP -> {
                if (row == 0) {
                    if (onLeaveUp?.invoke() == true) return focused
                    return super.focusSearch(focused, direction)
                }
                return show(grid, cellAt(row - 1, col, span, count))
            }
            FOCUS_DOWN -> {
                if (row >= lastRow) {
                    if (onLeaveDown?.invoke() == true) return focused
                    return focused
                }
                return show(grid, cellAt(row + 1, col, span, count))
            }
        }
        return super.focusSearch(focused, direction)
    }

    private fun cellAt(row: Int, col: Int, span: Int, count: Int): Int {
        val start = row * span
        val end = min(start + span, count) - 1
        return start + col.coerceAtMost(end - start)
    }

    private fun show(grid: GridLayoutManager, index: Int): View {
        val shown = grid.findViewByPosition(index)
        if (shown != null) return shown
        scrollToPosition(index)
        post { grid.findViewByPosition(index)?.requestFocus() }
        return focusedChild ?: this
    }
}
