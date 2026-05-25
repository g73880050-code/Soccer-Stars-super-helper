package com.soccerstars.analyzer

import android.content.Context
import android.content.SharedPreferences
import org.opencv.core.Scalar

data class HsvRange(
    val hLow: Int,
    val sLow: Int,
    val vLow: Int,
    val hHigh: Int,
    val sHigh: Int,
    val vHigh: Int
) {
    val lowerScalar: Scalar get() = Scalar(hLow.toDouble(), sLow.toDouble(), vLow.toDouble())
    val upperScalar: Scalar get() = Scalar(hHigh.toDouble(), sHigh.toDouble(), vHigh.toDouble())
}

data class AnalyzerConfig(
    val ballRange: HsvRange = DEFAULT_BALL,
    val playerRange: HsvRange = DEFAULT_PLAYER,
    val ballMinArea: Int = 200,
    val playerMinArea: Int = 500,
    val maxBounces: Int = 5,
    val rayLength: Int = 800,
    val margin: Int = 10,
    val scaleFactor: Float = 0.5f
) {
    companion object {
        val DEFAULT_BALL = HsvRange(0, 0, 200, 180, 40, 255)
        val DEFAULT_PLAYER = HsvRange(100, 150, 100, 130, 255, 255)
    }
}

data class TurnDetectorConfig(
    val enabled: Boolean = true,
    val scanRadius: Int = 180,
    val lineBrightness: Int = 180,
    val houghThreshold: Int = 20,
    val minLineLength: Int = 40,
    val maxLineGap: Int = 15,
    val motionPixelThresh: Int = 25,
    val motionAreaFraction: Float = 0.02f
)

object HsvPrefs {

    private const val PREFS_NAME = "hsv_prefs"

    private const val BALL_H_LO  = "ball_h_lo"
    private const val BALL_S_LO  = "ball_s_lo"
    private const val BALL_V_LO  = "ball_v_lo"
    private const val BALL_H_HI  = "ball_h_hi"
    private const val BALL_S_HI  = "ball_s_hi"
    private const val BALL_V_HI  = "ball_v_hi"

    private const val PLAY_H_LO  = "play_h_lo"
    private const val PLAY_S_LO  = "play_s_lo"
    private const val PLAY_V_LO  = "play_v_lo"
    private const val PLAY_H_HI  = "play_h_hi"
    private const val PLAY_S_HI  = "play_s_hi"
    private const val PLAY_V_HI  = "play_v_hi"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): AnalyzerConfig {
        val p = prefs(ctx)
        val d = AnalyzerConfig.DEFAULT_BALL
        val dp = AnalyzerConfig.DEFAULT_PLAYER
        return AnalyzerConfig(
            ballRange = HsvRange(
                p.getInt(BALL_H_LO, d.hLow),  p.getInt(BALL_S_LO, d.sLow),
                p.getInt(BALL_V_LO, d.vLow),  p.getInt(BALL_H_HI, d.hHigh),
                p.getInt(BALL_S_HI, d.sHigh), p.getInt(BALL_V_HI, d.vHigh)
            ),
            playerRange = HsvRange(
                p.getInt(PLAY_H_LO, dp.hLow),  p.getInt(PLAY_S_LO, dp.sLow),
                p.getInt(PLAY_V_LO, dp.vLow),  p.getInt(PLAY_H_HI, dp.hHigh),
                p.getInt(PLAY_S_HI, dp.sHigh), p.getInt(PLAY_V_HI, dp.vHigh)
            )
        )
    }

    fun save(ctx: Context, ball: HsvRange, player: HsvRange) {
        prefs(ctx).edit().apply {
            putInt(BALL_H_LO, ball.hLow);   putInt(BALL_S_LO, ball.sLow)
            putInt(BALL_V_LO, ball.vLow);   putInt(BALL_H_HI, ball.hHigh)
            putInt(BALL_S_HI, ball.sHigh);  putInt(BALL_V_HI, ball.vHigh)
            putInt(PLAY_H_LO, player.hLow); putInt(PLAY_S_LO, player.sLow)
            putInt(PLAY_V_LO, player.vLow); putInt(PLAY_H_HI, player.hHigh)
            putInt(PLAY_S_HI, player.sHigh);putInt(PLAY_V_HI, player.vHigh)
            apply()
        }
    }
}
