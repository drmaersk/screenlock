package com.bumphead.invisibleoverlay

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
    private var allowOnScreenUnlock = true
    private var lockRotation = true



    companion object {
        const val ACTION_FORCE_LOCK = "ACTION_FORCE_LOCK"
        const val ACTION_TOGGLE_LOCK = "ACTION_TOGGLE_LOCK"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"
        const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null


    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1. READ PREFERENCE: Always load the latest setting from storage
        val sharedPrefs = getSharedPreferences("ChildLockPrefs", Context.MODE_PRIVATE)
        allowOnScreenUnlock = sharedPrefs.getBoolean("ALLOW_UNLOCK", true)
        lockRotation = sharedPrefs.getBoolean("LOCK_ROTATION", true)
        // 1. Build the overlay views ONCE when the service starts
        createOverlayView()

        // 2. Start the foreground notification
        startForegroundServiceWithNotification()

        // Start in Green Mode (Unlocked)
        setBlockingState(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. READ PREFERENCE: Always load the latest setting from storage
        val sharedPrefs = getSharedPreferences("ChildLockPrefs", Context.MODE_PRIVATE)
        allowOnScreenUnlock = sharedPrefs.getBoolean("ALLOW_UNLOCK", true)
        lockRotation = sharedPrefs.getBoolean("LOCK_ROTATION", true)

        when (intent?.action) {
            ACTION_FORCE_LOCK -> setBlockingState(true)
            ACTION_TOGGLE_LOCK -> setBlockingState(!isTouchBlocked)
            ACTION_EXIT_APP, ACTION_NOTIFICATION_DISMISSED -> cleanupAndExit()

            // If the user just opened the app and hit "Start" again to update settings,
            // we should refresh the UI if the lock is currently active.
            else -> {
                if (isTouchBlocked) {
                    setBlockingState(true) // Re-apply the lock with new settings
                }
            }
        }
        return START_STICKY
    }



        private fun createOverlayView() {
            overlayView = FrameLayout(this)

            overlayView.setOnTouchListener { _, event ->
                if (isTouchBlocked && event.action == MotionEvent.ACTION_DOWN) {
                    // Block touches
                }
                isTouchBlocked
            }

            // Setup the Padlock Button
            unlockButton = ImageView(this)
            unlockButton.setImageResource(android.R.drawable.ic_secure)
            val density = resources.displayMetrics.density
            val padding = (12 * density).toInt()
            val size = (40 * density).toInt()
            val margin = (32 * density).toInt()

            unlockButton.setPadding(padding, padding, padding, padding)

            val buttonParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = margin + 60
                rightMargin = margin
            }

            unlockButton.setOnLongClickListener {
                setBlockingState(!isTouchBlocked)
                true
            }
            overlayView.addView(unlockButton, buttonParams)

            // Setup Window Params
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            windowManager.addView(overlayView, layoutParams)
        }

        private fun setBlockingState(blocked: Boolean) {
            isTouchBlocked = blocked

            if (isTouchBlocked) {
                // === LOCKED STATE (RED) ===
                layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
                layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
                overlayView.setBackgroundColor(Color.argb(10, 0, 0, 0))

                // THE LOGIC: Only show Red Button if allowed!
                if (allowOnScreenUnlock) {
                    unlockButton.visibility = View.VISIBLE
                    unlockButton.setBackgroundColor(Color.argb(200, 255, 0, 0)) // RED
                } else {
                    unlockButton.visibility = View.GONE // HIDDEN TRAP MODE
                }

                // Lock Rotation
                if (lockRotation) {
                    val currentRotation = resources.configuration.orientation
                    if (currentRotation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }

                windowManager.updateViewLayout(overlayView, layoutParams)

                // Block Gestures
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    overlayView.post {
                        overlayView.systemGestureExclusionRects = listOf(
                            android.graphics.Rect(0, 0, overlayView.width, overlayView.height)
                        )
                    }
                }
                Toast.makeText(this, "Shield ON", Toast.LENGTH_SHORT).show()

            } else {
                // === UNLOCKED STATE (GREEN) ===
                layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                overlayView.setBackgroundColor(Color.TRANSPARENT)

                // Always show Green button (so you can click it to lock!)
                unlockButton.visibility = View.VISIBLE
                unlockButton.setBackgroundColor(Color.argb(200, 0, 200, 0)) // GREEN

                // Unlock Rotation
                layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                windowManager.updateViewLayout(overlayView, layoutParams)

                // Unblock Gestures
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    overlayView.systemGestureExclusionRects = emptyList()
                }
                Toast.makeText(this, "Shield OFF", Toast.LENGTH_SHORT).show()
            }

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
        isRunning = false
        cleanupAndExit()
    }
}
