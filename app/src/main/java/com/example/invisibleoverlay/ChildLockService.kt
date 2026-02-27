package com.example.invisibleoverlay

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class ChildLockService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var unlockButton: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var isTouchBlocked = false

    companion object {
        const val ACTION_FORCE_LOCK = "ACTION_FORCE_LOCK"
        const val ACTION_TOGGLE_LOCK = "ACTION_TOGGLE_LOCK"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"
        const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1. Build the overlay views ONCE when the service starts
        createOverlayView()

        // 2. Start the foreground notification
        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FORCE_LOCK -> setBlockingState(true)
            ACTION_TOGGLE_LOCK -> setBlockingState(!isTouchBlocked)
            ACTION_EXIT_APP, ACTION_NOTIFICATION_DISMISSED -> cleanupAndExit()
        }
        return START_STICKY
    }

    private fun createOverlayView() {
        overlayView = FrameLayout(this)

        // Intercept touches (only matters when window is full screen)
        overlayView.setOnTouchListener { _, event ->
            if (isTouchBlocked && event.action == MotionEvent.ACTION_DOWN) {
                // Touch absorbed!
            }
            isTouchBlocked
        }

        // Setup the physical Padlock Button
        unlockButton = ImageView(this)
        unlockButton.setImageResource(R.drawable.ic_secure)

        val density = resources.displayMetrics.density
        val padding = (12 * density).toInt()
        unlockButton.setPadding(padding, padding, padding, padding)

        val size = (40 * density).toInt()
        val margin = (32 * density).toInt()

        val buttonParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = margin + 60
            rightMargin = margin
        }

        // LONG PRESS toggles the lock state!
        unlockButton.setOnLongClickListener {
            setBlockingState(!isTouchBlocked)
            true
        }

        overlayView.addView(unlockButton, buttonParams)

        // Setup WindowManager Params
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END // Keeps window anchored to top right when shrinking
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        windowManager.addView(overlayView, layoutParams)
    }

        private fun setBlockingState(blocked: Boolean) {
        isTouchBlocked = blocked

        if (isTouchBlocked) {
            // 1. Expand to full screen
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            overlayView.setBackgroundColor(Color.argb(10, 0, 0, 0)) // Slight tint
            unlockButton.setBackgroundColor(Color.argb(200, 255, 0, 0)) // RED Background

            // Apply the size changes to the screen FIRST
            windowManager.updateViewLayout(overlayView, layoutParams)

            // 2. ADD THIS BACK: Re-apply the gesture exclusion to the new full-screen size
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                overlayView.post {
                    overlayView.systemGestureExclusionRects = listOf(
                        android.graphics.Rect(0, 0, overlayView.width, overlayView.height)
                    )
                }
            }

            Toast.makeText(this, "Shield ON", Toast.LENGTH_SHORT).show()

        } else {
            // 1. Shrink window to just the button so touches pass through to the app
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            overlayView.setBackgroundColor(Color.TRANSPARENT)
            unlockButton.setBackgroundColor(Color.argb(200, 0, 200, 0)) // GREEN Background

            // Apply the size changes to the screen FIRST
            windowManager.updateViewLayout(overlayView, layoutParams)

            // 2. ADD THIS BACK: Remove the gesture exclusion so you can navigate normally
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                overlayView.systemGestureExclusionRects = emptyList()
            }

            Toast.makeText(this, "Shield OFF", Toast.LENGTH_SHORT).show()
        }

        // Sync the notification
        updateNotification()
    }

    private fun cleanupAndExit() {
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        stopSelf()
    }

    private fun startForegroundServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification())
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, buildNotification())
    }

    private fun buildNotification(): Notification {
        val channelId = "ChildLockChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Child Lock Controller", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // 1. Toggle Intent
        val toggleIntent = Intent(this, ChildLockService::class.java).apply { action = ACTION_TOGGLE_LOCK }
        val pendingToggleIntent = PendingIntent.getService(this, 0, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 2. Exit Intent
        val exitIntent = Intent(this, ChildLockService::class.java).apply { action = ACTION_EXIT_APP }
        val pendingExitIntent = PendingIntent.getService(this, 1, exitIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 3. Dismiss Intent (When swiped away)
        val dismissIntent = Intent(this, ChildLockService::class.java).apply { action = ACTION_NOTIFICATION_DISMISSED }
        val pendingDismissIntent = PendingIntent.getService(this, 2, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_secure)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(pendingDismissIntent)

        if (isTouchBlocked) {
            builder.setContentTitle("🔒 Screen is Locked")
                .setContentText("Touches are blocked.")
                .addAction(R.drawable.ic_media_pause, "UNLOCK", pendingToggleIntent)
        } else {
            builder.setContentTitle("🔓 Lock is Ready")
                .setContentText("Touches passing through.")
                .addAction(R.drawable.ic_media_play, "LOCK", pendingToggleIntent)
        }

        builder.addAction(R.drawable.ic_menu_close_clear_cancel, "EXIT APP", pendingExitIntent)

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndExit()
    }
}
