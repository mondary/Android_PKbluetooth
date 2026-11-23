package com.vibepop.overlay

import android.content.Context

/**
 * Popup size calculations shared by overlay and lock-screen activity.
 */
object PopupMetrics {

    /**
     * Target popup height: 40% of screen height.
     */
    fun targetPopupHeight(context: Context): Int {
        return (context.resources.displayMetrics.heightPixels * 0.40f).toInt()
    }
}
