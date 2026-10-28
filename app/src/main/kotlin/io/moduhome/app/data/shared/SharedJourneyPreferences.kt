package io.moduhome.app.data.shared

import android.annotation.SuppressLint
import android.content.Context
import io.moduhome.app.feature.shared.SharedJourneySession

internal class SharedJourneyPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("shared_journey", Context.MODE_PRIVATE)

    fun load(): SharedJourneySession = try {
        preferences.getString("session", null)?.let { SharedJourneyJson.decode(it, System.currentTimeMillis()) }
            ?: SharedJourneySession()
    } catch (_: ClassCastException) {
        SharedJourneySession()
    }

    /** Synchronous acknowledgement lets the caller retain intent before sending a route. */
    @SuppressLint("ApplySharedPref", "UseKtx") // apply/KTX edit cannot return the durable commit result.
    fun save(session: SharedJourneySession): Boolean {
        val encoded = SharedJourneyJson.encode(session) ?: return false
        return try { preferences.edit().putString("session", encoded).commit() } catch (_: Exception) { false }
    }

    fun isSelectedMode(): Boolean = try { preferences.getBoolean("selected_mode", false) }
        catch (_: ClassCastException) { false }

    @SuppressLint("ApplySharedPref", "UseKtx") // The caller switches modes only after the commit succeeds.
    fun selectMode(selected: Boolean): Boolean = try { preferences.edit().putBoolean("selected_mode", selected).commit() }
        catch (_: Exception) { false }
}
