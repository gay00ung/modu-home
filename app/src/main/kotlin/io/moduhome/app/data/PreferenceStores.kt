package io.moduhome.app.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** These fixed app preference keys share their last acknowledged value for this process. */
internal object PreferenceStores {
    private val texts = mutableMapOf<Pair<SharedPreferences, String>, AcknowledgedValueStore<String?>>()
    private val flags = mutableMapOf<Pair<SharedPreferences, String>, AcknowledgedValueStore<Boolean>>()

    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx") // Acknowledgement requires the commit result.
    fun text(preferences: SharedPreferences, key: String): AcknowledgedValueStore<String?> =
        texts.getOrPut(preferences to key) {
            val initial = try { preferences.getString(key, null) } catch (_: ClassCastException) { null }
            AcknowledgedValueStore(initial) { value -> preferences.edit().putString(key, value).commit() }
        }

    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx") // A failed mode change must not appear after recreation.
    fun flag(preferences: SharedPreferences, key: String): AcknowledgedValueStore<Boolean> =
        flags.getOrPut(preferences to key) {
            val initial = try { preferences.getBoolean(key, false) } catch (_: ClassCastException) { false }
            AcknowledgedValueStore(initial) { value -> preferences.edit().putBoolean(key, value).commit() }
        }
}
