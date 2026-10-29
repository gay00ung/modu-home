package io.moduhome.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.core.model.JourneyShortcutRequest

internal object JourneyShortcutIntents {
    private const val ACTION = "io.moduhome.app.OPEN_JOURNEY"
    private const val MODE = "journey_mode"
    private const val STARTED = "journey_started"

    fun pendingIntent(context: Context, request: JourneyShortcutRequest): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
            .putExtra(MODE, request.mode.name).putExtra(STARTED, request.startedAtMillis)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val code = when (request.mode) { JourneyMode.TOGETHER -> 201; JourneyMode.SOLO -> 202 }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun request(intent: Intent?): JourneyShortcutRequest? {
        if (intent?.action != ACTION) return null
        return runCatching {
            val mode = intent.getStringExtra(MODE)?.let(JourneyMode::valueOf) ?: return null
            val started = intent.getLongExtra(STARTED, -1L).takeIf { it >= 0 } ?: return null
            JourneyShortcutRequest(mode, started)
        }.getOrNull()
    }

    fun consume(intent: Intent?) {
        if (intent?.action != ACTION) return
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(MODE)
        intent.removeExtra(STARTED)
    }
}
