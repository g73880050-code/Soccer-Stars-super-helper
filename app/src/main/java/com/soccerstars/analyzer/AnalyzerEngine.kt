package com.soccerstars.analyzer

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DetectedObject(val x: Int, val y: Int, val radius: Int) {
    fun scaledUp(factor: Float): DetectedObject {
        if (factor >= 1.0f) return this
        val inv = 1.0f / factor
        return DetectedObject(
            (x * inv).roundToInt(),
            (y * inv).roundToInt(),
            (radius * inv).roundToInt()
        )
    }
}

data class AnalysisResult(
    val waypoints: List<PointF>,
    val ball: DetectedObject?,
    val player: DetectedObject?,
    val turnDetected: Boolean = false,
    val hibernating: Boolean = false
)

class AnalyzerEngine {

    fun analyseFrame(bitmap: Bitmap, config: AnalyzerConfig): AnalysisResult {
        val rgbaMat = Mat()
        Utils.bitmapToMat(bitmap, rgbaMat)

        val bgrMat = Mat()
        Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
        rgbaMat.release()

        val result = analyseFrame(bgrMat, config)
        bgrMat.release()
        return result
    }

    fun analyseFrame(bgr: Mat, config: AnalyzerConfig): AnalysisResult {
        val sf = config.scaleFactor
        val small: Mat
        val shouldRelease: Boolean

        if (sf < 1.0f) {
            small = Mat()
            Imgproc.resize(bgr, small, Size(), sf.toDouble(), sf.toDouble(), Imgproc.INTER_LINEAR)
            shouldRelease = true
        } else {
            small = bgr
            shouldRelease = false
        }

        val ballSmall   = detectByColour(small, config.ballRange,   config.ballMinArea)
        val playerSmall = detectByColour(small, config.playerRange, config.playerMinArea)

        if (shouldRelease) small.release()

        val ball   = ballSmall?.scaledUp(sf)
        val player = playerSmall?.scaledUp(sf)

        val waypoints: List<PointF> = if (ball != null && player != null) {
            computeTrajectory(ball, player, bgr.cols(), bgr.rows(), config)
        } else emptyList()

        return AnalysisResult(waypoints, ball, player)
    }

    fun detectByColour(frame: Mat, range: HsvRange, minArea: Int): DetectedObject? {
        val hsv  = Mat()
        val mask = Mat()

        Imgproc.cvtColor(frame, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, range.lowerScalar, range.upperScalar, mask)
        hsv.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,  kernel, Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
        kernel.release()

        val contours  = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()
        hierarchy.release()

        val valid = contours.filter { Imgproc.contourArea(it) >= minArea }
        if (valid.isEmpty()) return null

        val largest    = valid.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val mp2f       = MatOfPoint2f(*largest.toArray())
        val center     = Point()
        val radiusArr  = FloatArray(1)
        Imgproc.minEnclosingCircle(mp2f, center, radiusArr)
        mp2f.release()

        return DetectedObject(center.x.roundToInt(), center.y.roundToInt(), radiusArr[0].roundToInt())
    }

    fun computeTrajectory(
        ball: DetectedObject,
        player: DetectedObject,
        width: Int,
        height: Int,
        config: AnalyzerConfig
    ): List<PointF> {
        val m    = config.margin.toDouble()
        val xMin = m;           val xMax = width  - m
        val yMin = m;           val yMax = height - m

        var dx = (ball.x - player.x).toDouble()
        var dy = (ball.y - player.y).toDouble()
        val norm = sqrt(dx * dx + dy * dy)
        if (norm < 1e-6) return listOf(PointF(ball.x.toFloat(), ball.y.toFloat()))

        dx /= norm; dy /= norm

        var px = ball.x.toDouble()
        var py = ball.y.toDouble()
        val waypoints = mutableListOf(PointF(ball.x.toFloat(), ball.y.toFloat()))

        repeat(config.maxBounces + 1) {
            var bestT  = config.rayLength.toDouble()
            var bestX  = px + dx * bestT
            var bestY  = py + dy * bestT
            var normalX = 0.0; var normalY = 0.0

            intersectV(px, py, dx, dy, xMin, yMin, yMax)?.let { (t, iy) ->
                if (t < bestT) { bestT = t; bestX = xMin; bestY = iy; normalX =  1.0; normalY = 0.0 }
            }
            intersectV(px, py, dx, dy, xMax, yMin, yMax)?.let { (t, iy) ->
                if (t < bestT) { bestT = t; bestX = xMax; bestY = iy; normalX = -1.0; normalY = 0.0 }
            }
            intersectH(px, py, dx, dy, yMin, xMin, xMax)?.let { (t, ix) ->
                if (t < bestT) { bestT = t; bestX = ix; bestY = yMin; normalX = 0.0; normalY =  1.0 }
            }
            intersectH(px, py, dx, dy, yMax, xMin, xMax)?.let { (t, ix) ->
                if (t < bestT) { bestT = t; bestX = ix; bestY = yMax; normalX = 0.0; normalY = -1.0 }
            }

            waypoints.add(PointF(bestX.toFloat(), bestY.toFloat()))

            if (bestT >= config.rayLength || (normalX == 0.0 && normalY == 0.0)) {
                return waypoints
            }

            val dot = dx * normalX + dy * normalY
            dx -= 2.0 * dot * normalX
            dy -= 2.0 * dot * normalY
            px = bestX; py = bestY
        }

        return waypoints
    }

    private fun intersectV(
        ox: Double, oy: Double, dx: Double, dy: Double,
        xWall: Double, yMin: Double, yMax: Double
    ): Pair<Double, Double>? {
        if (abs(dx) < 1e-9) return null
        val t = (xWall - ox) / dx
        if (t <= 1e-3) return null
        val y = oy + t * dy
        return if (y in yMin..yMax) t to y else null
    }

    private fun intersectH(
        ox: Double, oy: Double, dx: Double, dy: Double,
        yWall: Double, xMin: Double, xMax: Double
    ): Pair<Double, Double>? {
        if (abs(dy) < 1e-9) return null
        val t = (yWall - oy) / dy
        if (t <= 1e-3) return null
        val x = ox + t * dx
        return if (x in xMin..xMax) t to x else null
    }
}
