package app.budpop.popup

import android.view.View
import app.budpop.core.BudState

/** A popup card as the host sees it. */
interface PopupView {
    val root: View
    /** True when the window starts out covering the whole screen (fly-in); otherwise it hugs the card. */
    val startsFullScreen: Boolean
    val gravity: Int
    val offsetY: Int
    fun bind(s: BudState)
    fun enter()
    fun exit(done: () -> Unit)
}

/** What a card can ask its host to do. */
interface PopupActions {
    fun close()
    /** The user touched the card: keep it on screen a little longer. */
    fun touched()
    /** Switch the overlay between covering the screen (fly-in/out) and hugging the card. */
    fun fullScreen(on: Boolean)
}

