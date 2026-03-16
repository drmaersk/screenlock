package com.bumphead.invisibleoverlay

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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.app.NotificationCompat

class ChildLockService : Service() {

    private lateinit var windowManager: WindowManager

    // Two separate windows: one for touch-blocking, one for the draggable FAB
    private lateinit var overlayView: View
    private lateinit var fabView: ImageView
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var fabParams: WindowManager.LayoutParams

    // Dismiss target shown at bottom-center while dragging (unlocked only)
    private lateinit var dismissView: ImageView
    private lateinit var dismissParams: WindowManager.LayoutParams
    private var isDismissViewAdded = false


    private var isTouchBlocked = false
    private var allowOnScreenUnlock = true
    private var lockRotation = true

    // Back gesture interception
    private val backCallback = OnBackInvokedCallback { /* consume — do nothing */ }
    private var isBackCallbackRegistered = false

    private var isFabAdded = false

    // Drag state
    private var fabInitialX = 0
    private var fabInitialY = 0
    private var touchInitialX = 0f
    private var touchInitialY = 0f
    private var isDragging = false

    companion object {
        const val ACTION_FORCE_LOCK = "ACTION_FORCE_LOCK"
        const val ACTION_TOGGLE_LOCK = "ACTION_TOGGLE_LOCK"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"
        const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
        const val ACTION_RESET = "ACTION_RESET"
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sharedPrefs = getSharedPreferences("ChildLockPrefs", Context.MODE_PRIVATE)
        allowOnScreenUnlock = sharedPrefs.getBoolean("ALLOW_UNLOCK", true)
        lockRotation = sharedPrefs.getBoolean("LOCK_ROTATION", true)

        createOverlayView()
        createDismissView()
        createFabView()
        startForegroundServiceWithNotification()
        setBlockingState(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sharedPrefs = getSharedPreferences("ChildLockPrefs", Context.MODE_PRIVATE)
        allowOnScreenUnlock = sharedPrefs.getBoolean("ALLOW_UNLOCK", true)
        lockRotation = sharedPrefs.getBoolean("LOCK_ROTATION", true)

        when (intent?.action) {
            ACTION_FORCE_LOCK -> setBlockingState(true)
            ACTION_TOGGLE_LOCK -> setBlockingState(!isTouchBlocked)
            ACTION_EXIT_APP, ACTION_NOTIFICATION_DISMISSED -> cleanupAndExit()
            ACTION_RESET -> setBlockingState(false)
            else -> setBlockingState(false)
        }
        return START_STICKY
    }

    private fun createOverlayView() {
        overlayView = View(this)
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true
        overlayView.setOnTouchListener { _, event ->
            if (isTouchBlocked && event.action == MotionEvent.ACTION_DOWN) {
                // Block touches
            }
            isTouchBlocked
        }
        // Fallback: consume the back key directly when focused
        overlayView.setOnKeyListener { _, keyCode, _ ->
            isTouchBlocked && keyCode == KeyEvent.KEYCODE_BACK
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Add the overlay now so the FAB (added next) is always on top in z-order
        windowManager.addView(overlayView, overlayParams)
    }

    private fun createDismissView() {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val padding = (14 * density).toInt()
        val bottomMargin = (80 * density).toInt()

        dismissView = ImageView(this)
        dismissView.setImageResource(R.drawable.ic_close)
        dismissView.setPadding(padding, padding, padding, padding)
        dismissView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(180, 100, 100, 100))
        }

        dismissParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottomMargin
        }
    }

    private fun showDismissTarget() {
        if (!isDismissViewAdded) {
            windowManager.addView(dismissView, dismissParams)
            isDismissViewAdded = true
        }
    }

    private fun hideDismissTarget() {
        if (isDismissViewAdded) {
            // Reset scale
            dismissView.scaleX = 1.0f
            dismissView.scaleY = 1.0f
            windowManager.removeView(dismissView)
            isDismissViewAdded = false
        }
    }

    private fun isFabOverDismissTarget(): Boolean {
        val dm = resources.displayMetrics
        val density = dm.density
        val dismissSize = (56 * density).toInt()
        val dismissMarginBottom = (80 * density).toInt()

        val dismissCenterX = dm.widthPixels / 2
        val dismissCenterY = dm.heightPixels - dismissMarginBottom - dismissSize / 2

        val fabCenterX = fabParams.x + fabParams.width / 2
        val fabCenterY = fabParams.y + fabParams.height / 2

        val dx = (fabCenterX - dismissCenterX).toFloat()
        val dy = (fabCenterY - dismissCenterY).toFloat()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        return distance < dismissSize
    }

    private fun updateDismissHighlight() {
        if (isFabOverDismissTarget()) {
            dismissView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 198, 40, 40))
            }
            dismissView.scaleX = 1.2f
            dismissView.scaleY = 1.2f
        } else {
            dismissView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 100, 100, 100))
            }
            dismissView.scaleX = 1.0f
            dismissView.scaleY = 1.0f
        }
    }

    private fun createFabView() {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val padding = (14 * density).toInt()
        val margin = (24 * density).toInt()

        fabView = ImageView(this)
        fabView.setPadding(padding, padding, padding, padding)
        fabView.elevation = 8 * density
        fabView.isLongClickable = true

        // Initial position: top-right corner
        val initialX = resources.displayMetrics.widthPixels - size - margin
        val initialY = margin + (60 * density).toInt()

        fabParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        fabView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    fabInitialX = fabParams.x
                    fabInitialY = fabParams.y
                    touchInitialX = event.rawX
                    touchInitialY = event.rawY
                    isDragging = false
                    false // Don't consume — allow long-click detection
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchInitialX).toInt()
                    val dy = (event.rawY - touchInitialY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        isDragging = true
                        showDismissTarget()
                    }
                    if (isDragging) {
                        fabParams.x = fabInitialX + dx
                        fabParams.y = fabInitialY + dy
                        windowManager.updateViewLayout(fabView, fabParams)
                        updateDismissHighlight()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging && isFabOverDismissTarget()) {
                        hideDismissTarget()
                        if (isTouchBlocked) {
                            // Locked: just hide the FAB, overlay stays active
                            windowManager.removeView(fabView)
                            isFabAdded = false
                        } else {
                            // Unlocked: stop the service entirely
                            cleanupAndExit()
                        }
                    } else {
                        hideDismissTarget()
                    }
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideDismissTarget()
                    isDragging = false
                    false
                }
                else -> false
            }
        }

        fabView.setOnLongClickListener {
            if (!isDragging) setBlockingState(!isTouchBlocked)
            true
        }

        windowManager.addView(fabView, fabParams)
        isFabAdded = true
    }

    private fun setBlockingState(blocked: Boolean) {
        isTouchBlocked = blocked

        if (isTouchBlocked) {
            // === LOCKED STATE ===
            // Remove FLAG_NOT_TOUCHABLE so the overlay blocks touches.
            // Remove FLAG_NOT_FOCUSABLE so the overlay receives focus and can intercept
            // the predictive back gesture (systemGestureExclusionRects alone is not enough
            // on Android 13+ where back gestures bypass exclusion rects).
            overlayParams.flags = overlayParams.flags and
                    (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()
            overlayView.setBackgroundColor(Color.argb(10, 0, 0, 0))

            if (allowOnScreenUnlock) {
                fabView.visibility = View.VISIBLE
                fabView.setImageResource(R.drawable.ic_lock)
                fabView.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(230, 198, 40, 40)) // Material Red 800
                }
            } else {
                fabView.visibility = View.GONE // Hidden trap mode
            }

            if (lockRotation) {
                val orientation = resources.configuration.orientation
                overlayParams.screenOrientation =
                    if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            // Set exclusion rects BEFORE updateViewLayout so they are included in the same pass.
            // Using displayMetrics avoids relying on view dimensions that may not be set yet.
            // Both windows need exclusion rects: the gesture recognizer checks the topmost window
            // at each position, so the fabView (on top of overlayView at the FAB corner) must also
            // exclude gestures or back swipes starting near the FAB will slip through.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val dm = resources.displayMetrics
                val fullScreen = android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
                overlayView.systemGestureExclusionRects = listOf(fullScreen)
                fabView.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, fabParams.width, fabParams.height)
                )
            }
            windowManager.updateViewLayout(overlayView, overlayParams)
            overlayView.requestFocus()

            // Register OnBackInvokedCallback to consume the predictive back gesture.
            // Must be done after the overlay is focusable and laid out.
            overlayView.post {
                if (!isBackCallbackRegistered) {
                    overlayView.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback
                    )
                    isBackCallbackRegistered = true
                }
            }
            Toast.makeText(this, "Shield ON", Toast.LENGTH_SHORT).show()

        } else {
            // === UNLOCKED STATE ===
            // Unregister back callback before losing focus
            if (isBackCallbackRegistered) {
                overlayView.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(backCallback)
                isBackCallbackRegistered = false
            }
            // Restore FLAG_NOT_TOUCHABLE and FLAG_NOT_FOCUSABLE so touches pass through
            overlayParams.flags = overlayParams.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            overlayParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            overlayView.setBackgroundColor(Color.TRANSPARENT)
            windowManager.updateViewLayout(overlayView, overlayParams)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                overlayView.systemGestureExclusionRects = emptyList()
                fabView.systemGestureExclusionRects = emptyList()
            }

            if (!isFabAdded) {
                windowManager.addView(fabView, fabParams)
                isFabAdded = true
            }
            fabView.visibility = View.VISIBLE
            fabView.setImageResource(R.drawable.ic_lock_open)
            fabView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 46, 125, 50)) // Material Green 800
            }

            Toast.makeText(this, "Shield OFF", Toast.LENGTH_SHORT).show()
        }

        updateNotification()
    }

    private fun cleanupAndExit() {
        if (isBackCallbackRegistered) {
            overlayView.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(backCallback)
            isBackCallbackRegistered = false
        }
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        hideDismissTarget()
        if (::fabView.isInitialized) windowManager.removeView(fabView)
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
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun buildNotification(): Notification {
        val channelId = "ChildLockChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Child Lock Controller", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingMainIntent = PendingIntent.getActivity(this, 3, mainActivityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val toggleIntent = Intent(this, ChildLockService::class.java).apply { action = ACTION_TOGGLE_LOCK }
        val pendingToggleIntent = PendingIntent.getService(this, 0, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val exitIntent = Intent(this, ChildLockService::class.java).apply { action = ACTION_EXIT_APP }
        val pendingExitIntent = PendingIntent.getService(this, 1, exitIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val dismissIntent = Intent(this, ChildLockService::class.java).apply { action = ACTION_NOTIFICATION_DISMISSED }
        val pendingDismissIntent = PendingIntent.getService(this, 2, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentIntent(pendingMainIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(pendingDismissIntent)

        if (isTouchBlocked) {
            builder.setContentTitle("🔒 Screen is Locked")
                .setContentText("Touches are blocked.")
                .addAction(R.drawable.ic_lock_open, "UNLOCK", pendingToggleIntent)
        } else {
            builder.setContentTitle("🔓 Lock is Ready")
                .setContentText("Touches passing through.")
                .addAction(R.drawable.ic_lock, "LOCK", pendingToggleIntent)
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "EXIT APP", pendingExitIntent)

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cleanupAndExit()
    }
}
