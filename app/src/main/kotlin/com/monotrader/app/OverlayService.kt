package com.monotrader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Overlay service v6 — black overlay + P&L curtain.
 *
 * The P&L curtain is a small opaque black rectangle that covers the bottom
 * portion of the MT5 area (where P&L is displayed). Every N seconds it
 * disappears briefly (peek) so you can read the number, then comes back.
 *
 * Both overlays are FLAG_NOT_TOUCHABLE — touch always passes through to MT5.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "monotrader_overlay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.monotrader.app.STOP_OVERLAY"
        const val ACTION_TOGGLE = "com.monotrader.app.TOGGLE_OVERLAY"
        const val ACTION_UPDATE = "com.monotrader.app.UPDATE_OVERLAY"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun update(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).apply { action = ACTION_UPDATE }
            )
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PrefsManager

    // Main overlay (full area dimmer)
    private var overlayView: View? = null
    private var isVisible = true

    // P&L curtain (small rectangle covering P&L zone)
    private var pnlCurtainView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pnlRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeAll()
                prefs.isOverlayActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                sendBroadcast(Intent("com.monotrader.app.OVERLAY_STATE_CHANGED"))
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                toggleOverlay()
                return START_STICKY
            }
            ACTION_UPDATE -> {
                showOverlay()
                return START_STICKY
            }
            else -> {
                showOverlay()
                prefs.isOverlayActive = true
                sendBroadcast(Intent("com.monotrader.app.OVERLAY_STATE_CHANGED"))
                return START_STICKY
            }
        }
    }

    // ── Main Overlay ──────────────────────────────────────────

    private fun showOverlay() {
        removeAll()

        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()

        val (overlayWidth, overlayX) = when (prefs.position) {
            PrefsManager.POSITION_LEFT -> Pair(screenWidth / 2, 0)
            PrefsManager.POSITION_RIGHT -> Pair(screenWidth / 2, screenWidth / 2)
            PrefsManager.POSITION_FULL -> Pair(screenWidth, 0)
            PrefsManager.POSITION_QUARTER_RIGHT -> Pair(screenWidth / 4, screenWidth * 3 / 4)
            else -> Pair(screenWidth / 4, screenWidth * 3 / 4)
        }

        // Black overlay
        val alpha = (prefs.opacity * 255 / 100).coerceIn(0, 217)
        overlayView = View(this).apply {
            setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        }
        windowManager.addView(overlayView, makeParams(overlayWidth, screenHeight, overlayX, 0))

        // P&L curtain (if enabled)
        if (prefs.pnlCurtainEnabled) {
            showPnlCurtain(overlayWidth, overlayX, screenHeight)
        }

        isVisible = true
        updateNotification()
    }

    // ── P&L Curtain ───────────────────────────────────────────

    private fun showPnlCurtain(areaWidth: Int, areaX: Int, screenHeight: Int) {
        removePnlCurtain()

        // Curtain covers bottom 20% of the MT5 area (where P&L typically shows)
        val curtainHeight = screenHeight / 5
        val curtainY = screenHeight - curtainHeight

        pnlCurtainView = View(this).apply {
            setBackgroundColor(Color.argb(230, 0, 0, 0)) // Almost fully opaque
        }
        windowManager.addView(pnlCurtainView, makeParams(areaWidth, curtainHeight, areaX, curtainY))

        // Start the peek cycle
        startPeekCycle()
    }

    private fun startPeekCycle() {
        pnlRunnable?.let { handler.removeCallbacks(it) }

        val intervalMs = prefs.pnlIntervalSec * 1000L
        val peekMs = prefs.pnlPeekMs.toLong()

        pnlRunnable = object : Runnable {
            override fun run() {
                // Peek: show the P&L briefly
                pnlCurtainView?.visibility = View.INVISIBLE

                // After peek duration, hide P&L again
                handler.postDelayed({
                    pnlCurtainView?.visibility = View.VISIBLE
                }, peekMs)

                // Schedule next peek
                handler.postDelayed(this, intervalMs)
            }
        }

        // First peek after the interval
        handler.postDelayed(pnlRunnable!!, intervalMs)
    }

    private fun removePnlCurtain() {
        pnlRunnable?.let { handler.removeCallbacks(it) }
        pnlRunnable = null
        pnlCurtainView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        pnlCurtainView = null
    }

    // ── Shared helpers ────────────────────────────────────────

    private fun makeParams(width: Int, height: Int, x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun toggleOverlay() {
        val newVis = if (isVisible) View.GONE else View.VISIBLE
        overlayView?.visibility = newVis
        pnlCurtainView?.visibility = newVis

        if (isVisible) {
            pnlRunnable?.let { handler.removeCallbacks(it) }
        } else if (prefs.pnlCurtainEnabled) {
            startPeekCycle()
        }

        isVisible = !isVisible
        updateNotification()
    }

    private fun removeAll() {
        removePnlCurtain()
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun getScreenWidth(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.heightPixels
    }

    // ── Notification ───────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MonoTrader Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Filtre monochrome actif"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pnlText = if (prefs.pnlCurtainEnabled) " | P&L: ${prefs.pnlIntervalSec}s" else ""
        val statusText = if (isVisible) "Filtre ${prefs.opacity}%$pnlText" else "En pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MonoTrader")
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isVisible) "Pause" else "Reprendre",
                toggleIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Arrêter",
                stopIntent
            )
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        removeAll()
        super.onDestroy()
    }
}
