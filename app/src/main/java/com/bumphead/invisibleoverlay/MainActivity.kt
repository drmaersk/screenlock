package com.bumphead.invisibleoverlay
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {
    private lateinit var cbAllowUnlock: CompoundButton
    private lateinit var cbLockRotation: CompoundButton
    private lateinit var btnStartLock: Button
    private lateinit var btnExit: Button
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted! Now try to start the lock again
            checkOverlayAndStart()
        } else {
            Toast.makeText(this, "Notifications are needed to control the lock!", Toast.LENGTH_LONG).show()
            checkOverlayAndStart()
        }

    }
    // Modern way to handle Activity Results (replaces deprecated startActivityForResult)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check if the user granted the permission after returning from settings
        if (Settings.canDrawOverlays(this)) {
            startChildLockService()
        } else {
            Toast.makeText(this, "Permission is required to enable Child Lock", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout)
        cbAllowUnlock = findViewById(R.id.cbAllowUnlock)
        btnStartLock = findViewById(R.id.btnStartLock)
        cbLockRotation = findViewById(R.id.cbLockRotation)
        btnExit = findViewById(R.id.btnExit)

        btnExit.setOnClickListener {
            stopService(Intent(this, ChildLockService::class.java))
            finishAffinity()
        }

        // 1. load preferences
        val sharedPrefs = getSharedPreferences("ChildLockPrefs", Context.MODE_PRIVATE)
        cbAllowUnlock.isChecked = sharedPrefs.getBoolean("ALLOW_UNLOCK", true)
        cbLockRotation.isChecked = sharedPrefs.getBoolean("LOCK_ROTATION", true)

        btnStartLock.setOnClickListener {
              // 2. SAVE PREFERENCES IMMEDIATELY
                sharedPrefs.edit {
                    putBoolean("ALLOW_UNLOCK", cbAllowUnlock.isChecked)
                    putBoolean("LOCK_ROTATION", cbLockRotation.isChecked)
                }
                checkNotificationAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ChildLockService.isRunning) {
            btnStartLock.text = "Reload"
            startService(Intent(this, ChildLockService::class.java).apply {
                action = ChildLockService.ACTION_RESET
            })
        } else {
            btnStartLock.text = "Start Child Lock"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }

    private fun checkNotificationAndStart() {
        // CHECK 1: Notification Permission (Only needed for Android 13 / API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // We don't have permission, so ask for it
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return // Stop here until the user says Yes
            }
        }

        // CHECK 2: Overlay Permission
        checkOverlayAndStart()
    }


    private fun checkOverlayAndStart() {
        if (Settings.canDrawOverlays(this)) {
            startChildLockService()
        } else {
            requestOverlayPermission()
        }
    }


    private fun requestOverlayPermission() {
        // Create an intent to open the "Display over other apps" settings for THIS specific app
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        Toast.makeText(this, "Please allow 'Display over other apps'", Toast.LENGTH_SHORT).show()
        overlayPermissionLauncher.launch(intent)
    }





    private fun startChildLockService() {
        val serviceIntent = Intent(this, ChildLockService::class.java)

        // Android 8.0 (API 26)+ requires using startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        btnStartLock.text = "Reload"
        Toast.makeText(this, "Child Lock Started!! Long press the lock icon to exit.", Toast.LENGTH_LONG).show()

        // Optional: Close the Activity so you are dropped back to your home screen
        finish()
    }

    private var timer: CountDownTimer? = null
    private fun startChildLockWithCountdown() {
        timer?.cancel()
        timer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                val ultraShortToast = Toast.makeText(this@MainActivity, "Lockingdown $secondsLeft...", Toast.LENGTH_SHORT)
                ultraShortToast.show()

                // 3. Set a timer to aggressively cancel it after 500 milliseconds (half a second)
                Handler(Looper.getMainLooper()).postDelayed({
                    ultraShortToast.cancel()
                }, 900)

            }

            override fun onFinish() {
                startChildLockService()
                // Optional: Close the config app automatically so you don't have to back out of it later
                // finish()
            }
        }.start()
    }
}
