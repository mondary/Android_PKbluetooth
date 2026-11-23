package com.vibepop.qa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import app.budpop.core.BudState
import app.budpop.core.Kind
import app.budpop.popup.BareCard
import app.budpop.popup.FlyCard
import app.budpop.popup.PopupActions
import com.vibepop.R
import java.io.File
import java.io.FileOutputStream

object StyleFrameRenderer {
    fun render(context: Context) {
        val themed = ContextThemeWrapper(context, R.style.Theme_VibePop)
        val width = 1080
        val height = 900
        val rootDir = File(context.getExternalFilesDir(null), "style-frames").apply {
            deleteRecursively()
            mkdirs()
        }
        val actions = object : PopupActions {
            override fun close() = Unit
            override fun touched() = Unit
            override fun fullScreen(on: Boolean) = Unit
        }
        val state = BudState(name = "Écouteurs Bluetooth", battery = 82, connected = true, kind = Kind.EARBUDS)
        val styles = listOf("lovely" to true, "fanciest" to false)
        for ((name, mini) in styles) {
            val card = FlyCard(themed, actions, mini = mini, kind = Kind.EARBUDS)
            card.bind(state)
            layout(card.root, width, height)
            val dir = File(rootDir, name).apply { mkdirs() }
            for (i in 0..75) {
                val t = i * 33L
                card.renderAt(t)
                forceBatteryFull(card)
                layout(card.root, width, height)
                save(card.root, File(dir, "%04d.png".format(i)))
            }
        }
        // Minimal is a static card; one frame is enough.
        val bare = BareCard(themed, actions, Kind.EARBUDS)
        bare.bind(state)
        layout(bare.root, width, height)
        val dir = File(rootDir, "minimal").apply { mkdirs() }
        save(bare.root, File(dir, "0000.png"))
        File(rootDir, "done.txt").writeText("frames ready")
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    private fun save(view: View, file: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(13, 17, 23))
        view.draw(canvas)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** The preview is deterministic; make the battery bar show its target value in every frame. */
    private fun forceBatteryFull(card: FlyCard) {
        try {
            val batteryField = card.javaClass.getDeclaredField("battery").apply { isAccessible = true }
            val battery = batteryField.get(card) ?: return
            val startAt = battery.javaClass.getDeclaredField("startAt").apply { isAccessible = true }
            startAt.setLong(battery, SystemClock.uptimeMillis() - 2000)
        } catch (_: Exception) {
        }
    }
}
