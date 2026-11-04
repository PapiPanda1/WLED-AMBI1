package com.wledambilight.tv

import android.content.Context

data class Settings(
    val ip: String = "192.168.1.50",
    val top: Int = 60,
    val right: Int = 30,
    val bottom: Int = 0,
    val left: Int = 30,
    val start: String = "Bottom-left",
    val direction: String = "Clockwise",
    val offset: Int = 0,
    val fps: Int = 30,
    val smooth: Float = 0.5f,
    val saturation: Float = 1.3f,
    val brightness: Float = 1f,
    val gamma: Float = 1f,
    val depth: Float = 0.12f,
    val blackCutoff: Int = 0
) {
    fun count(side: Char): Int {
        val n = when (side) {
            'T' -> top
            'R' -> right
            'B' -> bottom
            else -> left
        }
        return if (n < 0) 0 else n
    }

    val total: Int
        get() = count('T') + count('R') + count('B') + count('L')

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ip", ip)
            .putInt("top", top)
            .putInt("right", right)
            .putInt("bottom", bottom)
            .putInt("left", left)
            .putString("start", start)
            .putString("direction", direction)
            .putInt("offset", offset)
            .putInt("fps", fps)
            .putFloat("smooth", smooth)
            .putFloat("saturation", saturation)
            .putFloat("brightness", brightness)
            .putFloat("gamma", gamma)
            .putFloat("depth", depth)
            .putInt("blackCutoff", blackCutoff)
            .apply()
    }

    companion object {
        private const val PREFS = "wled_ambilight"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                ip = p.getString("ip", d.ip) ?: d.ip,
                top = p.getInt("top", d.top),
                right = p.getInt("right", d.right),
                bottom = p.getInt("bottom", d.bottom),
                left = p.getInt("left", d.left),
                start = p.getString("start", d.start) ?: d.start,
                direction = p.getString("direction", d.direction) ?: d.direction,
                offset = p.getInt("offset", d.offset),
                fps = p.getInt("fps", d.fps),
                smooth = p.getFloat("smooth", d.smooth),
                saturation = p.getFloat("saturation", d.saturation),
                brightness = p.getFloat("brightness", d.brightness),
                gamma = p.getFloat("gamma", d.gamma),
                depth = p.getFloat("depth", d.depth),
                blackCutoff = p.getInt("blackCutoff", d.blackCutoff)
            )
        }
    }
}
