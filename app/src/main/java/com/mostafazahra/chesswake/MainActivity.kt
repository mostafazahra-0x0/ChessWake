package com.mostafazahra.chesswake

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mostafazahra.chesswake.ui.ChessWakeApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's only launcher entry point.
 *
 * The ringing alarm lives in [com.mostafazahra.chesswake.alarm.AlarmActivity], a
 * separate task that shows over the lock screen, so this activity never has to
 * deal with wake flags or keyguard behaviour.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Notifications on Android 13+.
     *
     * The result is deliberately ignored: if the user declines, the alarms screen
     * keeps showing a banner explaining what is lost, which is a better place to
     * make that argument than a second system dialog.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the splash replaces the theme's
        // starting window instead of flashing after it.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            ChessWakeApp()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
