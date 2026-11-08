package com.wledambilight.tv

/**
 * Maps the four screen edges onto the physical LED strip.
 * Mirrors the Windows version so both apps behave the same.
 */
object Layout {
    private val SIDES = charArrayOf('T', 'R', 'B', 'L')
    private val NAMES = mapOf('T' to "Top", 'R' to "Right", 'B' to "Bottom", 'L' to "Left")
    private val TEST_COLORS = mapOf(
        'T' to floatArrayOf(255f, 0f, 0f),
        'R' to floatArrayOf(0f, 255f, 0f),
        'B' to floatArrayOf(0f, 80f, 255f),
        'L' to floatArrayOf(255f, 200f, 0f)
    )

    const val ASPECT_AUTO = "Auto (detect black bars)"
    const val ASPECT_FULL = "Full screen"
    const val ASPECT_WIDE = "Widescreen 2.39:1"
    val ASPECTS = listOf(ASPECT_AUTO, ASPECT_FULL, ASPECT_WIDE)

    val STARTS = listOf("Bottom-left", "Top-left", "Top-right", "Bottom-right")
    val DIRECTIONS = listOf("Clockwise", "Counter-clockwise")

    /** Sides in the order the strip passes through them, with whether each runs reversed. */
    fun plan(s: Settings): List<Pair<Char, Boolean>> {
        val cw = s.direction != "Counter-clockwise"
        val base: List<Pair<Char, Boolean>> =
            if (cw) SIDES.map { it to false } else SIDES.reversed().map { it to true }
        val corners: Map<String, Int> =
            if (cw) mapOf("Top-left" to 0, "Top-right" to 1, "Bottom-right" to 2, "Bottom-left" to 3)
            else mapOf("Top-left" to 0, "Bottom-left" to 1, "Bottom-right" to 2, "Top-right" to 3)
        val i = corners[s.start] ?: 0
        return base.drop(i) + base.take(i)
    }

    /**
     * sideColors: per side, RGB floats (3 per LED) listed in clockwise order
     * (top left->right, right top->bottom, bottom right->left, left bottom->top).
     * Returns RGB floats in physical strip order.
     */
    fun assemble(sideColors: Map<Char, FloatArray>, s: Settings): FloatArray {
        val total = s.total
        val out = FloatArray(total * 3)
        var pos = 0
        for ((side, reversed) in plan(s)) {
            val src = sideColors[side] ?: continue
            val n = src.size / 3
            for (k in 0 until n) {
                if (pos >= total) break
                val from = if (reversed) n - 1 - k else k
                out[pos * 3] = src[from * 3]
                out[pos * 3 + 1] = src[from * 3 + 1]
                out[pos * 3 + 2] = src[from * 3 + 2]
                pos++
            }
        }
        if (total == 0) return out
        val off = ((s.offset % total) + total) % total
        if (off == 0) return out
        val rolled = FloatArray(out.size)
        for (i in 0 until total) {
            val j = (i + off) % total
            rolled[j * 3] = out[i * 3]
            rolled[j * 3 + 1] = out[i * 3 + 1]
            rolled[j * 3 + 2] = out[i * 3 + 2]
        }
        return rolled
    }

    fun describe(s: Settings): String {
        val parts = plan(s).filter { s.count(it.first) > 0 }
            .map { "${NAMES[it.first]} (${s.count(it.first)})" }
        val list = if (parts.isEmpty()) "no LEDs set" else parts.joinToString(" -> ")
        return "$list   |   total ${s.total} LEDs"
    }

    /** First LED white, Top red, Right green, Bottom blue, Left yellow. */
    fun testFrame(s: Settings): ByteArray {
        val sides = HashMap<Char, FloatArray>()
        for (side in SIDES) {
            val n = s.count(side)
            val c = TEST_COLORS.getValue(side)
            val arr = FloatArray(n * 3)
            for (i in 0 until n) {
                arr[i * 3] = c[0]
                arr[i * 3 + 1] = c[1]
                arr[i * 3 + 2] = c[2]
            }
            sides[side] = arr
        }
        val colors = assemble(sides, s)
        if (colors.size >= 3) {
            colors[0] = 255f
            colors[1] = 255f
            colors[2] = 255f
        }
        return toBytes(colors)
    }

    fun toBytes(colors: FloatArray): ByteArray {
        val out = ByteArray(colors.size)
        for (i in colors.indices) {
            val v = colors[i]
            out[i] = (if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()).toByte()
        }
        return out
    }
}
