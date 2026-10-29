package io.moduhome.app.data

/** Keeps immutable values acknowledged by storage, including across screen recreation. */
internal class AcknowledgedValueStore<T>(initial: T, private val write: (T) -> Boolean) {
    private var acknowledged = initial

    @Synchronized
    fun load(): T = acknowledged

    @Synchronized
    fun save(value: T): Boolean {
        val written = try { write(value) } catch (_: Exception) { false }
        if (written) {
            acknowledged = value
            return true
        }
        // SharedPreferences changes its process cache even when the disk commit fails.
        // Restore that cache for other keys/writers too; never adopt a failed rollback.
        try { write(acknowledged) } catch (_: Exception) { }
        return false
    }
}
