package ug.audiocity.appvault;

/**
 * In-memory unlock state shared by the vault screen, the lock screen and App Guard.
 * Nothing is written to disk, so a restarted process always starts locked.
 */
final class Session {
    interface Clock { long now(); }

    static final Session SHARED = new Session(System::currentTimeMillis);

    private final Clock clock;
    private boolean unlocked;
    private long leftAt; // when the user left the vault and its apps; 0 while they are still inside

    Session(Clock clock) { this.clock = clock; }

    synchronized void unlock() { unlocked = true; leftAt = 0; }

    synchronized void lock() { unlocked = false; leftAt = 0; }

    /** The user moved away from the vault and its apps; the auto-lock countdown starts now. */
    synchronized void left() { if (unlocked && leftAt == 0) leftAt = clock.now(); }

    /** True while unlocked and the user has been away no longer than graceMs. Locks once that time has passed. */
    synchronized boolean isUnlocked(long graceMs) {
        if (!unlocked) return false;
        if (leftAt != 0 && clock.now() - leftAt > graceMs) { lock(); return false; }
        return true;
    }

    /** The user is back in the vault or one of its apps: stays unlocked if still within the grace period. */
    synchronized boolean returned(long graceMs) {
        if (!isUnlocked(graceMs)) return false;
        leftAt = 0;
        return true;
    }
}
