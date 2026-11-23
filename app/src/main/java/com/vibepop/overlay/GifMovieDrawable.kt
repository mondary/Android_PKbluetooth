@file:Suppress("DEPRECATION")

package com.vibepop.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.min

/**
 * GIF frame animation Drawable backed by android.graphics.Movie.
 * Fallback for Android 8.0/8.1 (API 26-27); API 28+ prefers ImageDecoder.
 */
class GifMovieDrawable(private val movie: Movie) : Drawable() {

    private var startTime = -1L

    override fun draw(canvas: Canvas) {
        val movieWidth = movie.width()
        val movieHeight = movie.height()
        if (movieWidth <= 0 || movieHeight <= 0) return

        val now = SystemClock.uptimeMillis()
        if (startTime < 0) {
            startTime = now
        }
        val duration = movie.duration()
        val relativeTime = if (duration > 0) ((now - startTime) % duration).toInt() else 0
        movie.setTime(relativeTime)

        // Center-crop the frame.
        val scale = min(
            bounds.width().toFloat() / movieWidth,
            bounds.height().toFloat() / movieHeight
        )
        canvas.save()
        canvas.translate(
            bounds.left + (bounds.width() - movieWidth * scale) / 2f,
            bounds.top + (bounds.height() - movieHeight * scale) / 2f
        )
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        canvas.restore()

        // Keep refreshing the next frame.
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        // Movie does not support alpha; ignored.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Movie does not support color filters; ignored.
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}