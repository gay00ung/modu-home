package io.moduhome.app.data

import android.content.Context
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyDraft

internal class JourneyPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("errand_journey", Context.MODE_PRIVATE)
    private val session = PreferenceStores.text(preferences, "session")

    fun hasSavedSession(): Boolean = session.load() != null

    fun load(fallback: JourneyDraft): HomeUiState = try {
        session.load()?.let { JourneyJson.decode(it, fallback, System.currentTimeMillis()) }
            ?: HomeUiState(fallback)
    } catch (_: ClassCastException) { HomeUiState(fallback) }

    /** Only an acknowledged write may publish changed plans or proposal approvals. */
    fun save(state: HomeUiState): Boolean {
        val encoded = JourneyJson.encode(state) ?: return false
        return session.save(encoded)
    }
}
