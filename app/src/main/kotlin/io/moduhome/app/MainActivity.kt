package io.moduhome.app

import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.moduhome.app.core.model.JourneyShortcutRequest
import io.moduhome.app.ui.theme.ModuHomeTheme

class MainActivity : ComponentActivity() {
    private var incomingProposal by mutableStateOf<String?>(null)
    private var incomingJoin by mutableStateOf<String?>(null)
    private var incomingJourney by mutableStateOf<JourneyShortcutRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingProposal = savedInstanceState?.getString("pending_proposal") ?: proposalFrom(intent)
        incomingJoin = joinFrom(intent)
        incomingJourney = JourneyShortcutIntents.request(intent)
        enableEdgeToEdge()
        setContent {
            ModuHomeTheme(vehicle = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                ModuHomeApp(incomingProposal = incomingProposal, onProposalConsumed = {
                    incomingProposal = null
                    if (intent?.data?.host == "proposal") intent?.data = null
                }, incomingJoin = incomingJoin, onJoinConsumed = {
                    incomingJoin = null
                    if (intent?.data?.host == "join") intent?.data = null
                }, incomingJourney = incomingJourney, onJourneyConsumed = {
                    incomingJourney = null
                    JourneyShortcutIntents.consume(intent)
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingProposal = proposalFrom(intent)
        incomingJoin = joinFrom(intent)
        incomingJourney = JourneyShortcutIntents.request(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        incomingProposal?.let { outState.putString("pending_proposal", it) }
        super.onSaveInstanceState(outState)
    }

    private fun proposalFrom(intent: Intent?): String? =
        appLinkFrom(intent, "proposal")

    private fun joinFrom(intent: Intent?): String? = appLinkFrom(intent, "join")

    private fun appLinkFrom(intent: Intent?, host: String): String? = intent?.takeIf {
        it.action == Intent.ACTION_VIEW && it.data?.scheme == "modu-home" && it.data?.host == host
    }?.dataString
}
