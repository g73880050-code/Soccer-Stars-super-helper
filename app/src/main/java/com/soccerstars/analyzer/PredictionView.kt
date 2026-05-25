package com.soccerstars.analyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

class PredictionView(context: Context) : View(context) {

    var waypoints:    List<PointF>      = emptyList()
    var ball:         DetectedObject?   = null
    var player:       DetectedObject?   = null
    var turnDetected: Boolean           = false
    var hibernating:  Boolean           = false
    var autoDetect:   Boolean           = true

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        color       = Color.GREEN
    }
    private val bouncePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFA500")
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 3f
        color       = Color.parseColor("#2196F3")
    }
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 3f
        color       = Color.parseColor("#F44336")
    }
    private val dotFillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRingPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE
    }

    fun update(result: AnalysisResult) {
        waypoints    = result.waypoints
        ball         = result.ball
        player       = result.player
        turnDetected = result.turnDetected
        hibernating  = result.hibernating
        postInvalidate()
    }

    fun clear() {
        waypoints    = emptyList()
        ball         = null
        player       = null
        turnDetected = false
        hibernating  = true
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        if (waypoints.size >= 2) {
            strokePaint.color = Color.GREEN
            for (i in 1 until waypoints.size) {
                val a = waypoints[i - 1]; val b = waypoints[i]
                canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
            }
            for (pt in waypoints.subList(1, waypoints.size - 1)) {
                canvas.drawCircle(pt.x, pt.y, 12f, bouncePaint)
            }
        }

        ball?.let {
            canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), it.radius + 6f, ballPaint)
        }
        player?.let {
            canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), it.radius + 6f, playerPaint)
        }

        val dotColor = when {
            turnDetected || !hibernating -> Color.parseColor("#4CAF50")
            autoDetect                  -> Color.parseColor("#FFEB3B")
            else                        -> Color.parseColor("#9E9E9E")
        }
        dotFillPaint.color = dotColor
        canvas.drawCircle(172f, 200f, 11f, dotFillPaint)
        canvas.drawCircle(172f, 200f, 11f, dotRingPaint)
    }
}
