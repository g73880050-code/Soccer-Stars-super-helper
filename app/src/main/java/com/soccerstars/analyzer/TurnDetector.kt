package com.soccerstars.analyzer

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class TurnDetector {

    private var prevGray: Mat? = null

    fun isYourTurn(
        frame: Mat,
        player: DetectedObject?,
        config: TurnDetectorConfig
    ): Boolean {
        if (!config.enabled || player == null) {
            prevGray?.release(); prevGray = null
            return false
        }
        return detectAimingLine(frame, player, config) || detectMotion(frame, player, config)
    }

    private fun detectAimingLine(frame: Mat, player: DetectedObject, config: TurnDetectorConfig): Boolean {
        val (roi, cx, cy) = roi(frame, player, config.scanRadius)
        if (roi.empty()) { roi.release(); return false }

        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        roi.release()

        val mask = Mat()
        Imgproc.threshold(gray, mask, config.lineBrightness.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        gray.release()

        val discR = maxOf(player.radius + 10, 18)
        Imgproc.circle(mask, Point(cx.toDouble(), cy.toDouble()), discR, Scalar(0.0), -1)

        val lines = Mat()
        Imgproc.HoughLinesP(
            mask, lines, 1.0, Math.PI / 180.0,
            config.houghThreshold,
            config.minLineLength.toDouble(),
            config.maxLineGap.toDouble()
        )
        mask.release()
        val found = lines.rows() > 0
        lines.release()
        return found
    }

    private fun detectMotion(frame: Mat, player: DetectedObject, config: TurnDetectorConfig): Boolean {
        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)

        val r  = config.scanRadius
        val h  = gray.rows(); val w = gray.cols()
        val x1 = maxOf(0, player.x - r); val y1 = maxOf(0, player.y - r)
        val x2 = minOf(w, player.x + r); val y2 = minOf(h, player.y + r)
        val curr = gray.submat(y1, y2, x1, x2)

        val prev = prevGray
        prevGray = gray.clone()

        if (prev == null || prev.size() != gray.size()) {
            prev?.release(); curr.release(); gray.release()
            return false
        }

        val prevRoi = prev.submat(y1, y2, x1, x2)
        if (curr.size() != prevRoi.size() || curr.empty()) {
            prevRoi.release(); prev.release(); curr.release(); gray.release()
            return false
        }

        val diff = Mat()
        Core.absdiff(curr, prevRoi, diff)
        prevRoi.release(); prev.release(); curr.release(); gray.release()

        val thresh = Mat()
        Imgproc.threshold(diff, thresh, config.motionPixelThresh.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        diff.release()

        val changed  = Core.countNonZero(thresh).toDouble()
        val fraction = changed / (thresh.rows() * thresh.cols())
        thresh.release()

        return fraction > config.motionAreaFraction
    }

    private fun roi(frame: Mat, player: DetectedObject, radius: Int): Triple<Mat, Int, Int> {
        val h = frame.rows(); val w = frame.cols()
        val x1 = maxOf(0, player.x - radius); val y1 = maxOf(0, player.y - radius)
        val x2 = minOf(w, player.x + radius); val y2 = minOf(h, player.y + radius)
        return Triple(frame.submat(y1, y2, x1, x2).clone(), player.x - x1, player.y - y1)
    }

    fun release() {
        prevGray?.release(); prevGray = null
    }
}
