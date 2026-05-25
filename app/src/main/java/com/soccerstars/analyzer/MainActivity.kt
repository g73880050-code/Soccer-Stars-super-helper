package com.soccerstars.analyzer

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.soccerstars.analyzer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var overlayRunning = false
    private var autoDetect     = true
    private var mpResultCode   = -1
    private var mpResultData: Intent? = null

    // -------------------------------------------------------------------------
    // Broadcast receiver — service status updates
    // -------------------------------------------------------------------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            overlayRunning = intent.getBooleanExtra(OverlayService.EXTRA_IS_RUNNING, false)
            updateUi()
        }
    }

    // -------------------------------------------------------------------------
    // Activity-result launchers
    // -------------------------------------------------------------------------

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            showMediaProjectionRationaleIfNeeded()
        } else {
            toast(getString(R.string.error_overlay_denied))
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestOverlayPermIfNeeded()
    }

    private val mpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mpResultCode = result.resultCode
            mpResultData = result.data
        } else {
            toast(getString(R.string.error_capture_denied))
        }
        doStartOverlay()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlay.setOnClickListener {
            if (overlayRunning) stopOverlay() else checkPermissionsAndStart()
        }
        binding.btnAutoDetect.setOnClickListener {
            autoDetect = !autoDetect
            updateUi()
            if (overlayRunning) {
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(OverlayService.BROADCAST_STATUS)
                        .putExtra(OverlayService.EXTRA_AUTO_DETECT, autoDetect)
                )
            }
        }
        binding.btnHsvSettings.setOnClickListener {
            startActivity(Intent(this, HsvTunerActivity::class.java))
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(statusReceiver, IntentFilter(OverlayService.BROADCAST_STATUS))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    // -------------------------------------------------------------------------
    // Permission chain:
    //   POST_NOTIFICATIONS → SYSTEM_ALERT_WINDOW → MP rationale → MediaProjection
    // -------------------------------------------------------------------------

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestOverlayPermIfNeeded()
    }

    private fun requestOverlayPermIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            showMediaProjectionRationaleIfNeeded()
        }
    }

    /**
     * Shows a plain-language explanation of why screen capture is needed before
     * the system's MediaProjection consent dialog appears.
     *
     * Shown once per install (persisted in SharedPreferences).
     * The user may choose to "Skip Capture" and run the overlay without
     * trajectory tracking — useful for players who do not want to grant
     * screen-capture access.
     */
    private fun showMediaProjectionRationaleIfNeeded() {
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MP_RATIONALE_SHOWN, false)) {
            launchMediaProjection()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_mp_title)
            .setMessage(R.string.dialog_mp_message)
            .setIcon(android.R.drawable.ic_menu_camera)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_mp_continue) { _, _ ->
                prefs.edit().putBoolean(KEY_MP_RATIONALE_SHOWN, true).apply()
                launchMediaProjection()
            }
            .setNegativeButton(R.string.dialog_mp_skip) { _, _ ->
                prefs.edit().putBoolean(KEY_MP_RATIONALE_SHOWN, true).apply()
                doStartOverlay()
            }
            .show()
    }

    private fun launchMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mpLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // -------------------------------------------------------------------------
    // Overlay service control
    // -------------------------------------------------------------------------

    private fun doStartOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, mpResultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, mpResultData)
            putExtra(OverlayService.EXTRA_AUTO_DETECT, autoDetect)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateUi() {
        if (overlayRunning) {
            binding.btnOverlay.text = getString(R.string.btn_stop_overlay)
            binding.btnOverlay.setBackgroundColor(getColor(R.color.danger))
        } else {
            binding.btnOverlay.text = getString(R.string.btn_start_overlay)
            binding.btnOverlay.setBackgroundColor(getColor(R.color.accent_green))
        }

        if (autoDetect) {
            binding.btnAutoDetect.text = getString(R.string.btn_auto_detect_on)
            binding.btnAutoDetect.setBackgroundColor(getColor(R.color.accent_blue))
        } else {
            binding.btnAutoDetect.text = getString(R.string.btn_auto_detect_off)
            binding.btnAutoDetect.setBackgroundColor(getColor(R.color.grey))
        }

        val ovStatus = if (overlayRunning) "ON" else "OFF"
        val adStatus = if (autoDetect)    "ON" else "OFF"
        binding.tvStatus.text = getString(R.string.status_template, ovStatus, adStatus)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // -------------------------------------------------------------------------

    companion object {
        private const val PREFS_APP              = "app_prefs"
        private const val KEY_MP_RATIONALE_SHOWN = "mp_rationale_shown"
    }
}
