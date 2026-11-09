package io.moduhome.app.data.shared

import android.content.Context
import io.moduhome.app.data.PreferenceStores
import io.moduhome.app.feature.shared.SharedJourneySession

internal class SharedJourneyPreferences(context: Context, defaultMode: Boolean = false) {
    private val preferences = context.applicationContext.getSharedPreferences("shared_journey", Context.MODE_PRIVATE)
    private val session = PreferenceStores.text(preferences, "session")
    private val selectedMode = PreferenceStores.flag(preferences, "selected_mode", defaultMode)

    fun load(): SharedJourneySession = try {
        session.load()?.let { SharedJourneyJson.decode(it, System.currentTimeMillis()) }
            ?: SharedJourneySession()
    } catch (_: ClassCastException) {
        SharedJourneySession()
    }

    /** Synchronous acknowledgement lets the caller retain intent before sending a route. */
    fun save(session: SharedJourneySession): Boolean {
        val encoded = SharedJourneyJson.encode(session) ?: return false
        return this.session.save(encoded)
    }

    fun isSelectedMode(): Boolean = selectedMode.load()

    fun selectMode(selected: Boolean): Boolean = selectedMode.save(selected)
}
