package ug.audiocity.appvault;

/** Host tests for the auto-lock rules. Run with ./run-tests.sh (plain Java, no Android SDK needed). */
public final class SessionTests {
    private static long now = 1_000;
    private static int failures;

    public static void main(String[] args) {
        startsLocked();
        staysUnlockedWithinGrace();
        locksAfterGrace();
        immediateLockOnLeaving();
        returningResetsCountdown();
        leavingTwiceKeepsFirstTime();
        lockNow();
        if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
        System.out.println("All Session tests passed.");
    }

    private static Session fresh() { now = 1_000; return new Session(() -> now); }

    static void startsLocked() {
        Session s = fresh();
        check("starts locked", !s.isUnlocked(30_000));
        check("returning while locked stays locked", !s.returned(30_000));
    }

    static void staysUnlockedWithinGrace() {
        Session s = fresh(); s.unlock(); s.left(); now += 30_000;
        check("unlocked at exactly the grace period", s.returned(30_000));
    }

    static void locksAfterGrace() {
        Session s = fresh(); s.unlock(); s.left(); now += 30_001;
        check("locked once grace has passed", !s.returned(30_000));
        now += 1; check("stays locked", !s.isUnlocked(Long.MAX_VALUE));
    }

    static void immediateLockOnLeaving() {
        Session s = fresh(); s.unlock();
        check("unlocked while inside", s.isUnlocked(0));
        s.left(); now += 1;
        check("grace 0 locks right after leaving", !s.isUnlocked(0));
    }

    static void returningResetsCountdown() {
        Session s = fresh(); s.unlock(); s.left(); now += 20_000;
        check("back within grace", s.returned(30_000));
        now += 20_000;
        check("countdown not running while inside", s.isUnlocked(30_000));
        s.left(); now += 20_000;
        check("new countdown started on second leave", s.isUnlocked(30_000));
    }

    static void leavingTwiceKeepsFirstTime() {
        Session s = fresh(); s.unlock(); s.left(); now += 20_000; s.left(); now += 15_000;
        check("second left() does not restart the countdown", !s.isUnlocked(30_000));
    }

    static void lockNow() {
        Session s = fresh(); s.unlock(); s.lock();
        check("lock() locks at once", !s.isUnlocked(Long.MAX_VALUE));
    }

    private static void check(String name, boolean ok) {
        if (!ok) { failures++; System.out.println("FAIL: " + name); }
    }
}
