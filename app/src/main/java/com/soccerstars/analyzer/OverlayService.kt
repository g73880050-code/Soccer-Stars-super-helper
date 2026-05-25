package com.soccerstars.analyzer

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE   = "result_code"
        const val EXTRA_RESULT_DATA   = "result_data"
        const val EXTRA_AUTO_DETECT   = "auto_detect"
        const val EXTRA_UPDATE_CONFIG = "update_config"

        const val BROADCAST_STATUS     = "com.soccerstars.analyzer.STATUS"
        const val BROADCAST_RESULT     = "com.soccerstars.analyzer.RESULT"
        const val EXTRA_IS_RUNNING     = "is_running"
        const val EXTRA_TURN_DETECTED  = "turn_detected"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "SoccerStarsChannel"

        private const val TARGET_FPS        = 15
        private const val FRAME_MS          = 1000L / TARGET_FPS
        private const val HIBERNATE_MS      = 500L
        private const val WAKE_HOLD_MS      = 3_000L
    }

    private lateinit var windowManager: WindowManager
    private var predictionView:   PredictionView? = null
    private var floatingBtn:      ImageButton?    = null
    private var floatingParams:   WindowManager.LayoutParams? = null
    private var canvasVisible  = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler?      = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader:     ImageReader?     = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var screenWidth  = 1080
    private var screenHeight = 1920
    private var screenDpi    = 480

    private val engine       = AnalyzerEngine()
    private val turnDetector = TurnDetector()
    private lateinit var config: AnalyzerConfig
    private var tdConfig = TurnDetectorConfig()

    private var powerActive  = false
    private var wakeUntil    = 0L
    private var autoDetect   = true
    private var running      = false

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasExtra(EXTRA_AUTO_DETECT)) {
                autoDetect = intent.getBooleanExtra(EXTRA_AUTO_DETECT, true)
                predictionView?.let { it.autoDetect = autoDetect; it.postInvalidate() }
            }
            if (intent.getBooleanExtra(EXTRA_UPDATE_CONFIG, false)) {
                config = HsvPrefs.load(applicationContext)
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        OpenCVLoader.initLocal()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        config        = HsvPrefs.load(this)
        getScreenMetrics()
        createNotificationChannel()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(configReceiver, IntentFilter(BROADCAST_STATUS))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY

        autoDetect = intent?.getBooleanExtra(EXTRA_AUTO_DETECT, true) ?: true
        config     = HsvPrefs.load(this)

        startForeground(NOTIFICATION_ID, buildNotification())
        setupOverlayViews()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != -1 && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        }

        startAnalysisLoop()
        running = true

        broadcastStatus(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        stopAnalysisLoop()
        teardownOverlayViews()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        turnDetector.release()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(configReceiver)
        broadcastStatus(false)
        super.onDestroy()
    }

    private fun getScreenMetrics() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenWidth  = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDpi    = dm.densityDpi
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SoccerStarsCapture",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun setupOverlayViews() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val baseFlags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val btnParams = WindowManager.LayoutParams(
            120.dpToPx(), 120.dpToPx(),
            overlayType,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40.dpToPx(); y = 180.dpToPx()
        }
        floatingParams = btnParams

        val btn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            alpha = 0.88f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener(DragToggleTouchListener(
                wm      = windowManager,
                lp      = btnParams,
                onDown  = { setPowerActive(true); wakeUntil = SystemClock.elapsedRealtime() + WAKE_HOLD_MS },
                onTap   = { toggleCanvas() }
            ))
        }
        floatingBtn = btn
        windowManager.addView(btn, btnParams)

        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val pv = PredictionView(this).apply { autoDetect = this@OverlayService.autoDetect }
        predictionView = pv
        windowManager.addView(pv, canvasParams)
    }

    private fun teardownOverlayViews() {
        mainHandler.post {
            try { floatingBtn?.let    { windowManager.removeView(it) } } catch (_: Exception) {}
            try { predictionView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            floatingBtn    = null
            predictionView = null
        }
    }

    private fun toggleCanvas() {
        canvasVisible = !canvasVisible
        predictionView?.visibility = if (canvasVisible) View.VISIBLE else View.GONE
        setPowerActive(canvasVisible)
    }

    private fun setPowerActive(active: Boolean) {
        powerActive = active
        if (!active) predictionView?.clear()
    }

    private fun startAnalysisLoop() {
        val ht = HandlerThread("AnalysisThread", Process.THREAD_PRIORITY_DEFAULT)
        ht.start()
        analysisThread  = ht
        analysisHandler = Handler(ht.looper)
        analysisHandler?.post(analysisRunnable)
    }

    private fun stopAnalysisLoop() {
        analysisThread?.quitSafely()
        analysisThread  = null
        analysisHandler = null
    }

    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            val now        = SystemClock.elapsedRealtime()
            val wakeActive = now < wakeUntil
            val isActive   = powerActive || wakeActive

            if (!isActive) {
                runHibernateStep()
                analysisHandler?.postDelayed(this, HIBERNATE_MS)
                return
            }

            runActiveStep()
            analysisHandler?.postDelayed(this, FRAME_MS)
        }
    }

    private fun runActiveStep() {
        val frame = acquireFrame() ?: return
        val result = engine.analyseFrame(frame, config).copy(
            turnDetected = true,
            hibernating  = false
        )
        frame.release()
        mainHandler.post { predictionView?.update(result) }
        broadcastResult(result.turnDetected)
    }

    private fun runHibernateStep() {
        drainImageReader()

        if (!autoDetect || !tdConfig.enabled) return

        val frame = acquireFrame() ?: return
        val sf    = config.scaleFactor
        val small = if (sf < 1.0f) {
            val s = Mat()
            org.opencv.imgproc.Imgproc.resize(frame, s, org.opencv.core.Size(),
                sf.toDouble(), sf.toDouble(), Imgproc.INTER_LINEAR)
            s
        } else frame

        val playerSmall = engine.detectByColour(small, config.playerRange, config.playerMinArea)
        if (sf < 1.0f) small.release()

        val player = playerSmall?.scaledUp(sf)
        val turn   = turnDetector.isYourTurn(frame, player, tdConfig)
        frame.release()

        if (turn) {
            wakeUntil   = SystemClock.elapsedRealtime() + WAKE_HOLD_MS
            powerActive = true
            broadcastResult(true)
        }
    }

    private fun acquireFrame(): Mat? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null
        return try {
            imageToBgrMat(image)
        } finally {
            image.close()
        }
    }

    private fun imageToBgrMat(image: Image): Mat {
        val planes     = image.planes
        val buffer     = planes[0].buffer
        val rowStride  = planes[0].rowStride
        val pixStride  = planes[0].pixelStride
        val rowPadding = rowStride - pixStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)

        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()

        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        return if (image.width + rowPadding / pixStride != image.width) {
            bgr.submat(0, image.height, 0, image.width).clone().also { bgr.release() }
        } else bgr
    }

    private fun drainImageReader() {
        try { imageReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Soccer Stars Analyzer",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Overlay active — capturing screen" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Soccer Stars Analyzer")
            .setContentText("Overlay active — capturing screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun broadcastStatus(running: Boolean) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_STATUS).putExtra(EXTRA_IS_RUNNING, running)
        )
    }

    private fun broadcastResult(turnDetected: Boolean) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_RESULT).putExtra(EXTRA_TURN_DETECTED, turnDetected)
        )
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}

class DragToggleTouchListener(
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val onDown: () -> Unit,
    private val onTap:  () -> Unit
) : View.OnTouchListener {

    private var startX = 0f; private var startY = 0f
    private var lastX  = 0f; private var lastY  = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                lastX  = startX;     lastY  = startY
                onDown()
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x += (event.rawX - lastX).toInt()
                lp.y += (event.rawY - lastY).toInt()
                wm.updateViewLayout(v, lp)
                lastX = event.rawX; lastY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val moved = Math.abs(event.rawX - startX) + Math.abs(event.rawY - startY)
                if (moved < 8f) onTap()
            }
        }
        return true
    }
}
