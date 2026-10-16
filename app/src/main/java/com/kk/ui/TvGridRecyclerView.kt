package com.kk.tvlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 上下键按列换行，不必先移到这一行的尽头。 */
class TvGridRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    override fun focusSearch(focused: View, direction: Int): View? {
        val grid = layoutManager as? GridLayoutManager
        if (grid != null && (direction == FOCUS_UP || direction == FOCUS_DOWN)) {
            val pos = getChildAdapterPosition(focused)
            val count = adapter?.itemCount ?: 0
            if (pos != NO_POSITION && count > 0) {
                val target = pos + if (direction == FOCUS_DOWN) grid.spanCount else -grid.spanCount
                if (target !in 0 until count) return focused
                val found = grid.findViewByPosition(target)
                if (found != null) return found
                scrollToPosition(target)
                post { grid.findViewByPosition(target)?.requestFocus() }
                return focused
            }
        }
        return super.focusSearch(focused, direction)
    }
}
