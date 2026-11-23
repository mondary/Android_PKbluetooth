package app.budpop.art

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Where one bud is drawn. Angles in degrees; [size] = on-screen height of the bud (head top to stem tip) in px. */
data class BudPose(
    val x: Float,
    val y: Float,
    val size: Float,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val alpha: Float = 1f,
    val squashX: Float = 1f,
    val squashY: Float = 1f,
)

/**
 * AirPods-style earbud as a tiny 3D model: a glossy white head (sphere), a tapered stem with a silver
 * tip, a dark speaker grille on the inner side and a sensor dot on the outer side. Rotated and
 * perspective-projected onto a plain Canvas. [side] -1 = left bud, +1 = right bud (mirror images).
 *
 * Nothing is allocated per frame (the popup redraws every vsync, up to 165 Hz).
 */
class Earbuds3D {
    var white = Color.rgb(0xF7, 0xF8, 0xFA)
    var shade = Color.rgb(0xB4, 0xBA, 0xC4)
    var silver = Color.rgb(0xA9, 0xAF, 0xB8)
    var grille = Color.rgb(0x33, 0x36, 0x3D)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val sm = Matrix()
    private val oval = RectF()
    private val r = FloatArray(9)
    private val m9 = FloatArray(9)

    // Unit shaders positioned per frame with a local matrix (no new shader objects while animating).
    private val headShader by lazy {
        RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, white, blend(white, shade, 0.55f), shade), floatArrayOf(0f, 0.32f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }
    private val stemShader by lazy {
        LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(shade, Color.WHITE, white, shade), floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }
    private val tipShader by lazy {
        LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(blend(silver, Color.BLACK, 0.25f), blend(silver, Color.WHITE, 0.55f), silver, blend(silver, Color.BLACK, 0.3f)), floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }
    private val shadowShader = RadialGradient(0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)

    // Projection state.
    private var px = 0f
    private var py = 0f
    private var k = 1f
    private var sqx = 1f
    private var sqy = 1f
    private val p0 = FloatArray(4)
    private val p1 = FloatArray(4)
    private val p2 = FloatArray(4)
    private val v = FloatArray(3)

    /** Draws one bud. */
    fun draw(c: Canvas, p: BudPose, side: Int) {
        if (p.alpha <= 0.01f || p.size <= 1f) return
        rotation(p.yaw, p.pitch, p.roll)
        px = p.x; py = p.y
        k = p.size / H
        sqx = p.squashX; sqy = p.squashY
        val a8 = (p.alpha.coerceIn(0f, 1f) * 255).toInt()
        val s = side.toFloat()

        // Head centre and stem ends in model space (y down, z away from the viewer).
        proj(0f, 0f, 0f, p0)
        val headZ = p0[2]
        val headX = p0[0]; val headY = p0[1]; val headS = p0[3]
        proj(s * 5f, 22f, 7f, p1)
        proj(s * 9f, STEM_END, 12f, p2)
        val stemZ = (p1[2] + p2[2]) / 2

        if (stemZ > headZ) { stem(c, a8, s); head(c, headX, headY, headS, a8, s) }
        else { head(c, headX, headY, headS, a8, s); stem(c, a8, s) }
    }

    /** Soft contact shadow under a bud; [lift] 0 = resting. */
    fun shadow(c: Canvas, x: Float, floorY: Float, size: Float, lift: Float, strength: Float = 1f) {
        val w = size * (0.55f - 0.18f * lift)
        val h = w * 0.18f
        val a = ((0.35f - 0.25f * lift) * strength).coerceIn(0f, 1f)
        sm.setScale(w / 2, w / 2); sm.postTranslate(x, floorY)
        shadowShader.setLocalMatrix(sm)
        fill.shader = shadowShader; fill.alpha = (a * 255).toInt()
        c.save()
        c.scale(1f, h / w * 2f, x, floorY)
        c.drawCircle(x, floorY, w / 2, fill)
        c.restore()
        fill.shader = null
    }

    private fun stem(c: Canvas, a8: Int, s: Float) {
        // Tapered stem from p1 (under the head) to p2 (tip); width shrinks a little towards the tip.
        val dx = p2[0] - p1[0]; val dy = p2[1] - p1[1]
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len; val ny = dx / len
        val w0 = 2 * 10.5f * k * p1[3]
        val w1 = 2 * 8.5f * k * p2[3]
        val wm = (w0 + w1) / 2
        gradientAcross(stemShader, (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2, nx, ny, wm / 2)
        line.shader = stemShader; line.alpha = a8; line.strokeWidth = wm
        c.drawLine(p1[0], p1[1], p2[0], p2[1], line)
        // Silver tip: the last stretch of the stem.
        val t = 1f - 9f / (STEM_END - 22f)
        val tx = p1[0] + dx * t; val ty = p1[1] + dy * t
        gradientAcross(tipShader, (tx + p2[0]) / 2, (ty + p2[1]) / 2, nx, ny, w1 / 2)
        line.shader = tipShader; line.strokeWidth = w1 * 0.98f
        c.drawLine(tx, ty, p2[0], p2[1], line)
        line.shader = null
        // Mic slit on the outer face of the stem when it faces us.
        rotVec(s, 0f, -0.4f)
        if (v[2] < -0.15f) {
            val mx = p1[0] + dx * 0.62f + nx * s * w0 * 0.12f
            val my = p1[1] + dy * 0.62f + ny * s * w0 * 0.12f
            fill.color = blend(shade, Color.BLACK, 0.45f); fill.alpha = (a8 * (-v[2]).coerceAtMost(1f)).toInt()
            c.save()
            c.rotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(), mx, my)
            oval.set(mx - wm * 0.16f, my - wm * 0.07f, mx + wm * 0.16f, my + wm * 0.07f)
            c.drawOval(oval, fill)
            c.restore()
        }
    }

    private fun head(c: Canvas, x: Float, y: Float, persp: Float, a8: Int, s: Float) {
        val rad = R * k * persp
        // Sphere lit from the upper left: the highlight stays put while the bud turns, like a real gloss.
        sm.setScale(rad * 1.25f * sqx, rad * 1.25f * sqy); sm.postTranslate(x - rad * 0.38f, y - rad * 0.42f)
        headShader.setLocalMatrix(sm)
        fill.shader = headShader; fill.alpha = a8
        oval.set(x - rad * sqx, y - rad * sqy, x + rad * sqx, y + rad * sqy)
        c.drawOval(oval, fill)
        fill.shader = null
        // Faint edge so the white head reads on light backgrounds too.
        line.color = blend(shade, Color.BLACK, 0.2f); line.alpha = (a8 * 0.35f).toInt(); line.strokeWidth = max(1f, rad * 0.035f)
        c.drawOval(oval, line)

        // Speaker grille on the inner side (towards the ear), and the dark sensor on the outer side.
        disc(c, x, y, rad, -s * 0.9f, 0.1f, 0.35f, 0.44f, grille, a8, ring = true)
        disc(c, x, y, rad, s * 0.5f, -0.35f, -0.79f, 0.13f, blend(grille, Color.BLACK, 0.4f), a8, ring = false)
    }

    /** A flat round patch on the head's surface, centred on unit normal (nx,ny,nz) in model space, radius [f]×head radius. */
    private fun disc(c: Canvas, x: Float, y: Float, rad: Float, nx: Float, ny: Float, nz: Float, f: Float, color: Int, a8: Int, ring: Boolean) {
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        rotVec(nx / l, ny / l, nz / l)
        val vz = v[2]
        if (vz > -0.05f) return // facing away
        val face = (-vz).coerceIn(0f, 1f)
        // Centre sits slightly inside the silhouette; the ellipse squashes along the normal's screen direction.
        val cx = x + v[0] * rad * 0.9f * sqx
        val cy = y + v[1] * rad * 0.9f * sqy
        val major = rad * f
        val minor = major * face
        val ang = Math.toDegrees(atan2(v[1].toDouble(), v[0].toDouble())).toFloat()
        c.save()
        c.rotate(ang, cx, cy)
        oval.set(cx - minor, cy - major, cx + minor, cy + major)
        fill.color = color; fill.alpha = (a8 * (0.4f + 0.6f * face)).toInt()
        c.drawOval(oval, fill)
        if (ring) {
            line.color = blend(color, Color.WHITE, 0.35f); line.alpha = (a8 * 0.6f * face).toInt(); line.strokeWidth = max(1f, major * 0.08f)
            c.drawOval(oval, line)
        }
        c.restore()
    }

    // ---------- geometry ----------

    private fun gradientAcross(sh: Shader, cx: Float, cy: Float, nx: Float, ny: Float, half: Float) {
        // Unit gradient runs along x from -1 to 1; map x to the stem's normal, scaled to its half width.
        m9[0] = nx * half; m9[1] = -ny * half; m9[2] = cx
        m9[3] = ny * half; m9[4] = nx * half; m9[5] = cy
        m9[6] = 0f; m9[7] = 0f; m9[8] = 1f
        sm.setValues(m9)
        sh.setLocalMatrix(sm)
    }

    private fun rotation(yawDeg: Float, pitchDeg: Float, rollDeg: Float) {
        val y = Math.toRadians(yawDeg.toDouble()); val p = Math.toRadians(pitchDeg.toDouble()); val rr = Math.toRadians(rollDeg.toDouble())
        val cy = cos(y).toFloat(); val sy = sin(y).toFloat()
        val cp = cos(p).toFloat(); val sp = sin(p).toFloat()
        val cr = cos(rr).toFloat(); val sr = sin(rr).toFloat()
        // R = Rz(roll) · Rx(pitch) · Ry(yaw)
        val a00 = cy; val a02 = sy
        val a10 = sp * sy; val a11 = cp; val a12 = -sp * cy
        val a20 = -cp * sy; val a21 = sp; val a22 = cp * cy
        r[0] = cr * a00 - sr * a10; r[1] = -sr * a11; r[2] = cr * a02 - sr * a12
        r[3] = sr * a00 + cr * a10; r[4] = cr * a11; r[5] = sr * a02 + cr * a12
        r[6] = a20; r[7] = a21; r[8] = a22
    }

    private fun rotVec(x: Float, y: Float, z: Float) {
        v[0] = r[0] * x + r[1] * y + r[2] * z
        v[1] = r[3] * x + r[4] * y + r[5] * z
        v[2] = r[6] * x + r[7] * y + r[8] * z
    }

    /** Model point (head centre at origin, model centre at y = [CY]) → [screenX, screenY, depth, perspective]. */
    private fun proj(x: Float, y: Float, z: Float, out: FloatArray) {
        rotVec(x, y - CY, z)
        val f = 420f
        val s = f / (f + v[2])
        out[0] = px + v[0] * s * k * sqx
        out[1] = py + v[1] * s * k * sqy
        out[2] = v[2]
        out[3] = s
    }

    companion object {
        const val R = 30f
        const val STEM_END = 96f
        /** Model height (head top to stem tip) and the point it spins around. */
        const val H = R + STEM_END + 9f
        const val CY = 30f

        fun blend(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.argb(
                255,
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }
    }
}
