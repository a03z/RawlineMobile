package moe.matsuri.nb4a

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the owner of a UDP flow for a few seconds. Android finds only live sockets, so a datagram an app resends
 * (QUIC retries) or sends just before closing its socket came with no owner, and the tun0 guard dropped an allowed app.
 * Keyed by the full 5-tuple: a bare source port could be taken over by another app.
 */
object UdpOwnerCache {
    const val TTL_MS = 10_000L
    private const val LIMIT = 4096
    const val NOT_FOUND = -1

    private class Entry(val uid: Int, val at: Long)

    private val owners = ConcurrentHashMap<String, Entry>()

    /** [found] is what Android returned ([NOT_FOUND] for none); returns the owner to use. A hit does not extend the TTL. */
    fun resolve(key: String, found: Int, now: Long): Int {
        if (found != NOT_FOUND) {
            if (owners.size >= LIMIT) {
                owners.entries.removeIf { now - it.value.at > TTL_MS }
                if (owners.size >= LIMIT) owners.clear()
            }
            owners[key] = Entry(found, now)
            return found
        }
        val entry = owners[key] ?: return NOT_FOUND
        return if (now - entry.at <= TTL_MS) entry.uid else NOT_FOUND
    }

    fun clear() = owners.clear()
}
