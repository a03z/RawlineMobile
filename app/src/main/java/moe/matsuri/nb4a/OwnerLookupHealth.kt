package moe.matsuri.nb4a

import java.util.concurrent.atomic.AtomicInteger

/**
 * Some firmware (seen on vivo, Android 16) never names the owner of an app's connection. The tun0 guard would then
 * drop every connection, so after [MISSES] TCP lookups with no app owner ever found the device counts as unable to
 * tell owners apart and the guard stands down for the rest of the process.
 */
object OwnerLookupHealth {
    const val MISSES = 8
    private const val FIRST_APPLICATION_UID = 10_000
    private const val PER_USER_RANGE = 100_000

    @Volatile private var appOwnerSeen = false
    private val misses = AtomicInteger()
    @Volatile var broken = false
        private set

    /** Records a TCP lookup ([uid] -1 for none); returns whether lookups are known not to work. */
    fun tcp(uid: Int): Boolean {
        if (uid % PER_USER_RANGE >= FIRST_APPLICATION_UID) appOwnerSeen = true
        if (!appOwnerSeen && uid == -1 && misses.incrementAndGet() >= MISSES) broken = true
        return broken
    }

    internal fun reset() { appOwnerSeen = false; misses.set(0); broken = false }
}
