package com.example.invisibleoverlay
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Modern way to handle Activity Results (replaces deprecated startActivityForResult)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check if the user granted the permission after returning from settings
        if (Settings.canDrawOverlays(this)) {
            startChildLockService();
        } else {
            Toast.makeText(this, "Permission is required to enable Child Lock", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout)
        val cbAllowUnlock = findViewById<CheckBox>(R.id.cbAllowUnlock)
        val btnStartLock = findViewById<Button>(R.id.btnStartLock)

        // 1. LOAD PREFERENCE: Retrieve the saved state (Default to TRUE)
        val sharedPrefs = getSharedPreferences("ChildLockPrefs", Context.MODE_PRIVATE)
        val savedState = sharedPrefs.getBoolean("ALLOW_UNLOCK", true)
        cbAllowUnlock.isChecked = savedState

        btnStartLock.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                sharedPrefs.edit().putBoolean("ALLOW_UNLOCK", cbAllowUnlock.isChecked).apply()
                // We already have permission, start the service
                startChildLockService();
            } else {
                // We don't have permission, request it
                requestOverlayPermission()
            }
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

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }

    private fun startChildLockService() {
        val serviceIntent = Intent(this, ChildLockService::class.java).apply {
            action = ChildLockService.ACTION_FORCE_LOCK
        }

        // Android 8.0 (API 26)+ requires using startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Child Lock Started!! Long press the lock icon to exit.", Toast.LENGTH_LONG).show()

        // Optional: Close the Activity so you are dropped back to your home screen
        // finish()
    }
}
