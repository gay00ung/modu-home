package io.moduhome.app.data.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.moduhome.app.R
import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.core.model.JourneyShortcut
import io.moduhome.app.core.model.JourneyShortcutRequest

/** A quiet return entry for durable plans. It never starts guidance or records arrival. */
internal class JourneyNotifications(
    private val context: Context,
    private val contentIntent: (JourneyShortcutRequest) -> PendingIntent,
) {
    private val vehicle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val published = mutableMapOf<JourneyMode, JourneyShortcut?>()

    init {
        if (vehicle) try {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "진행 계획 바로가기", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "정차 후 모두 집으로의 진행 계획을 다시 열어요"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        } catch (_: RuntimeException) {
            // A missing notification service cannot prevent opening a saved plan.
        }
    }

    val available: Boolean get() = runCatching {
        vehicle && permissionGranted() && manager?.areNotificationsEnabled() == true &&
            manager.getNotificationChannel(CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    }.getOrDefault(false)

    val permissionRequired: Boolean get() = vehicle && !permissionGranted()

    val permissionRequested: Boolean get() = runCatching {
        context.getSharedPreferences("journey_notifications", Context.MODE_PRIVATE).getBoolean("permission_requested", false)
    }.getOrDefault(true)

    fun recordPermissionRequest() {
        runCatching {
            context.getSharedPreferences("journey_notifications", Context.MODE_PRIVATE).edit(commit = true) {
                putBoolean("permission_requested", true)
            }
        }
    }

    private fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Call only after persistence succeeds. Posting is best effort and cannot block a saved journey. */
    @SuppressLint("MissingPermission")
    fun update(mode: JourneyMode, shortcut: JourneyShortcut?) {
        if (!vehicle || shortcut?.request?.mode?.let { it != mode } == true) return
        if (published.containsKey(mode) && published[mode] == shortcut) return
        val manager = manager ?: return
        try {
            if (shortcut == null) {
                manager.cancel(notificationId(mode))
                published[mode] = null
                return
            }
            if (!available) return
            val modeTitle = when (mode) {
                JourneyMode.TOGETHER -> "함께 귀가"
                JourneyMode.SOLO -> "가는김에"
            }
            val status = if (shortcut.remainingStops == 0) "집 도착 확인"
                else "남은 정차 " + shortcut.remainingStops + "곳"
            val title = modeTitle + " · " + status
            val text = "정차한 뒤 눌러 진행 계획을 확인해주세요"
            fun builder() = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_journey_notification)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
            val public = builder().setContentTitle("모두 집으로").setContentText("진행 중인 계획이 있어요").build()
            manager.notify(notificationId(mode), builder()
                .setContentTitle(title).setContentText(text)
                .setContentIntent(contentIntent(shortcut.request))
                .setOngoing(true).setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build())
            published[mode] = shortcut
        } catch (_: RuntimeException) {
            // Notification settings or system availability do not change a durable plan.
        }
    }

    fun refresh(solo: JourneyShortcut?, together: JourneyShortcut?) {
        published.clear()
        update(JourneyMode.SOLO, solo)
        update(JourneyMode.TOGETHER, together)
    }

    fun openSettings(): Boolean {
        if (!vehicle) return false
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(("package:" + context.packageName).toUri()),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Some vehicle settings expose only the app details page.
            } catch (_: SecurityException) {
                return false
            }
        }
        return false
    }

    private fun notificationId(mode: JourneyMode): Int = when (mode) {
        JourneyMode.TOGETHER -> 1_001
        JourneyMode.SOLO -> 1_002
    }

    companion object {
        const val CHANNEL = "journey_shortcut"
    }
}
