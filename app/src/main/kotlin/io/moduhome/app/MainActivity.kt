package io.moduhome.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.moduhome.app.ui.theme.ModuHomeTheme

class MainActivity : ComponentActivity() {
    private var incomingProposal by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingProposal = savedInstanceState?.getString("pending_proposal") ?: proposalFrom(intent)
        enableEdgeToEdge()
        setContent {
            ModuHomeTheme {
                ModuHomeApp(incomingProposal = incomingProposal, onProposalConsumed = {
                    incomingProposal = null
                    intent?.data = null
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingProposal = proposalFrom(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        incomingProposal?.let { outState.putString("pending_proposal", it) }
        super.onSaveInstanceState(outState)
    }

    private fun proposalFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
}
