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
    private lateinit var overlayView: View
    private var isOverlayActive = false

    companion object {
        const val ACTION_START_LOCK = "ACTION_START_LOCK"
        const val ACTION_STOP_LOCK = "ACTION_STOP_LOCK"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"
        const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LOCK -> showOverlay()
            ACTION_STOP_LOCK -> hideOverlay()
            ACTION_EXIT_APP, ACTION_NOTIFICATION_DISMISSED -> {
                hideOverlay() // Drop the shield if it's up
                stopSelf()    // Kill the service and remove the notification completely
            }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (isOverlayActive) return

        val frameLayout = FrameLayout(this)
        frameLayout.setBackgroundColor(Color.argb(10, 0, 0, 0))

        frameLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Consume touch
            }
            true
        }

        // --- THE VISIBLE PADLOCK ICON IS BACK ---
        val unlockButton = ImageView(this)
        unlockButton.setImageResource(R.drawable.ic_secure) // System padlock icon
        unlockButton.setBackgroundColor(Color.argb(150, 0, 0, 0)) // Semi-transparent black background

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

        unlockButton.setOnLongClickListener {
            hideOverlay()
            true
        }

        frameLayout.addView(unlockButton, buttonParams)
        overlayView = frameLayout

        // WindowManager settings
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
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
        isOverlayActive = true

        updateNotification()
        Toast.makeText(this, "Shield ON", Toast.LENGTH_SHORT).show()
    }

    private fun hideOverlay() {
        if (!isOverlayActive) return

        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        isOverlayActive = false

        updateNotification()
        Toast.makeText(this, "Shield OFF", Toast.LENGTH_SHORT).show()
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

        // 1. The Toggle Intent (Starts or Stops the Lock) - Request Code 0
        val toggleIntent = Intent(this, ChildLockService::class.java).apply {
            action = if (isOverlayActive) ACTION_STOP_LOCK else ACTION_START_LOCK
        }
        val pendingToggleIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 2. The Exit Intent (Kills the App entirely) - Request Code 1
        val exitIntent = Intent(this, ChildLockService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        val pendingExitIntent = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissIntent = Intent(this, ChildLockService::class.java).apply {
            action = ACTION_NOTIFICATION_DISMISSED
        }
        val pendingDismissIntent = PendingIntent.getService(
            this, 2, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. Build the persistent notification
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_secure)
            .setOngoing(true) // This makes it persistent (un-swipeable)
            .setAutoCancel(false) // Ensures tapping it doesn't accidentally dismiss it
            .setDeleteIntent(pendingDismissIntent)

        if (isOverlayActive) {
            builder.setContentTitle("🔒 Screen is Locked")
                .setContentText("Touches are blocked.")
                .addAction(R.drawable.ic_media_pause, "UNLOCK", pendingToggleIntent)
        } else {
            builder.setContentTitle("🔓 Lock is Ready")
                .setContentText("Tap button to block touches.")
                .addAction(R.drawable.ic_media_play, "LOCK", pendingToggleIntent)
        }

        // Add the EXIT button to both states
        builder.addAction(R.drawable.ic_menu_close_clear_cancel, "EXIT APP", pendingExitIntent)

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay() // Always clean up the shield if the service is destroyed
    }
}