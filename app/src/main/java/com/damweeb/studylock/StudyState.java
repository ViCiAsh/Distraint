package com.damweeb.studylock;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.appcompat.app.AppCompatDelegate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** All persistent state: today's study time, unlock window, and settings. */
public class StudyState {
    public static final int MODE_REST_OF_DAY = 0;
    public static final int MODE_TIMED = 1;

    public static final int COMBINE_TOTAL = 0;
    public static final int COMBINE_INDIVIDUAL = 1;

    private static final Set<String> DEFAULT_BLOCKED = new HashSet<>();

    private final Context ctx;
    private final SharedPreferences p;

    // Short-lived cache of per-app usage so the timer screen stays smooth.
    private Map<String, Long> cachedByApp = new HashMap<>();
    private long cachedAt = -3000;

    public StudyState(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("study_state", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Daily reset
    // ------------------------------------------------------------------

    public int rolloverHour() { return p.getInt("rollover_hour", 4); }

    public long resetAt() { return p.getLong("reset_at", 0); }

    /** First moment strictly after `from` when the clock reads hour:00. */
    private static long nextBoundary(long from, int hour) {
        ZonedDateTime t = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault())
                .withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (t.toInstant().toEpochMilli() <= from) t = t.plusDays(1);
        return t.toInstant().toEpochMilli();
    }

    /** Last moment at or before `from` when the clock read hour:00. */
    private static long prevBoundary(long from, int hour) {
        return Instant.ofEpochMilli(nextBoundary(from, hour)).atZone(ZoneId.systemDefault())
                .minusDays(1).toInstant().toEpochMilli();
    }

    /** Call before reading anything. Performs the daily reset lazily, no alarm needed. */
    public void rollover() {
        long now = System.currentTimeMillis();
        if (now < p.getLong("reset_at", 0)) return;      // still inside the same study day

        int oldHour = rolloverHour();
        int newHour = oldHour;
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"required_min", "mode", "timed_min", "rollover_hour", "combine"}) {
            if (p.contains("pending_" + k)) {
                int v = p.getInt("pending_" + k, 0);
                e.putInt(k, v).remove("pending_" + k);
                if (k.equals("rollover_hour")) newHour = v;
            }
        }

        Set<String> live = blocked();
        live.removeAll(pendingRemovals());
        Set<String> study = studyApps();
        study.addAll(pendingStudyAdds());

        e.putStringSet("blocked", live).remove("pending_remove")
                .putStringSet("study_apps", study).remove("pending_study_add")
                .putLong("day_start", prevBoundary(now, oldHour))
                .putLong("reset_at", nextBoundary(now, newHour))
                .putLong("studied_sec", 0)
                .putLong("unlocked_until", 0)
                .putBoolean("unlock_used", false)
                .apply();

        cachedAt = -3000;                                // day changed: drop cached usage
    }

    // ------------------------------------------------------------------
    // Settings (changes wait for the next reset)
    // ------------------------------------------------------------------

    public int requiredMin() { return p.getInt("required_min", 30); }
    public int mode() { return p.getInt("mode", MODE_REST_OF_DAY); }
    public int timedMin() { return p.getInt("timed_min", 60); }
    public int combineMode() { return p.getInt("combine", COMBINE_TOTAL); }

    private int live(String key) {
        switch (key) {
            case "required_min": return requiredMin();
            case "mode":         return mode();
            case "timed_min":    return timedMin();
            case "combine":      return combineMode();
            default:             return rolloverHour();   // "rollover_hour"
        }
    }

    /** key is one of: required_min, mode, timed_min, rollover_hour, combine. */
    public void setPending(String key, int value) {
        // The very first real choice of the combine mode applies right away.
        if (key.equals("combine") && !combineChosen() && value != combineMode()) {
            chooseCombineFirstTime(value);
            return;
        }
        if (value == live(key)) p.edit().remove("pending_" + key).apply();   // same as today: cancel
        else p.edit().putInt("pending_" + key, value).apply();
        if (key.equals("required_min") && !requiredChosen() && value != requiredMin()) {
            chooseRequiredFirstTime(value);
            return;
        }
    }

    /** What the settings screen shows: the queued value if any, else the live one. */
    public int shown(String key) {
        return p.getInt("pending_" + key, live(key));
    }

    public boolean anyPending() {
        for (String k : new String[]{"required_min", "mode", "timed_min", "rollover_hour", "combine"}) {
            if (p.contains("pending_" + k)) return true;
        }
        return false;
    }

    // Theme applies immediately, so it is not queued.
    public int themeMode() {
        return p.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setThemeMode(int mode) {
        p.edit().putInt("theme", mode).apply();
    }

    // ------------------------------------------------------------------
    // Locked apps: additions apply now, removals wait for the next reset
    // ------------------------------------------------------------------

    public Set<String> blocked() {
        // Copy: the set SharedPreferences returns must never be modified directly.
        return new HashSet<>(p.getStringSet("blocked", DEFAULT_BLOCKED));
    }

    public Set<String> pendingRemovals() {
        return new HashSet<>(p.getStringSet("pending_remove", new HashSet<>()));
    }

    public void saveBlocked(Set<String> chosen) {
        Set<String> live = blocked();
        live.addAll(chosen);                        // newly ticked games lock right now
        Set<String> toRemove = new HashSet<>(live);
        toRemove.removeAll(chosen);                 // unticked games leave at the next reset
        p.edit()
                .putStringSet("blocked", live)
                .putStringSet("pending_remove", toRemove)
                .apply();
    }

    // ------------------------------------------------------------------
    // Study apps: removals apply now, additions wait for the next reset
    // (except the very first study list, which applies immediately)
    // ------------------------------------------------------------------

    public Set<String> studyApps() {
        return new HashSet<>(p.getStringSet("study_apps", new HashSet<>()));
    }

    public Set<String> pendingStudyAdds() {
        return new HashSet<>(p.getStringSet("pending_study_add", new HashSet<>()));
    }

    public boolean studySetupDone() {
        return p.getBoolean("study_setup_done", false);
    }

    public void saveStudyApps(Set<String> chosen) {
        SharedPreferences.Editor e = p.edit();

        if (!studySetupDone() && !chosen.isEmpty()) {
            // First study list ever: everything ticked counts right away.
            e.putStringSet("study_apps", new HashSet<>(chosen))
                    .remove("pending_study_add")
                    .putBoolean("study_setup_done", true)
                    .apply();
            return;
        }

        Set<String> live = studyApps();
        live.retainAll(chosen);                    // unticked apps stop counting right now
        Set<String> toAdd = new HashSet<>(chosen);
        toAdd.removeAll(live);                     // later additions wait for the next reset
        e.putStringSet("study_apps", live)
                .putStringSet("pending_study_add", toAdd)
                .apply();
    }

    // ------------------------------------------------------------------
    // First-run permission tour
    // ------------------------------------------------------------------

    public int onboardStep() { return p.getInt("onboard_step", 0); }

    public void setOnboardStep(int step) { p.edit().putInt("onboard_step", step).apply(); }

    // ------------------------------------------------------------------
    // Study progress
    // ------------------------------------------------------------------

    /** Seconds studied with the in-app timer today. */
    public long studiedSec() { return p.getLong("studied_sec", 0); }

    public void addStudied(long sec) {
        p.edit().putLong("studied_sec", studiedSec() + sec).apply();
    }

    public boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Seconds spent in each study app since today's reset, read from Android's usage log. */
    private Map<String, Long> computeAppSecByPackage() {
        Map<String, Long> result = new HashMap<>();
        Set<String> study = studyApps();
        if (study.isEmpty() || !hasUsageAccess()) return result;

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(p.getLong("day_start", end), end);
        UsageEvents.Event e = new UsageEvents.Event();
        Map<String, Long> cameToFront = new HashMap<>();

        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            if (!study.contains(pkg)) continue;
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                cameToFront.put(pkg, e.getTimeStamp());
            } else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                Long from = cameToFront.remove(pkg);
                if (from != null) result.merge(pkg, e.getTimeStamp() - from, Long::sum);
            }
        }
        for (Map.Entry<String, Long> en : cameToFront.entrySet()) {
            result.merge(en.getKey(), end - en.getValue(), Long::sum);   // still open right now
        }
        for (Map.Entry<String, Long> en : result.entrySet()) {
            en.setValue(en.getValue() / 1000);                           // milliseconds to seconds
        }
        return result;
    }

    /** Per-app seconds, recomputed at most once every 3 seconds. */
    private Map<String, Long> appSecByPackage() {
        long now = SystemClock.elapsedRealtime();
        if (now - cachedAt >= 3000) {
            cachedByApp = computeAppSecByPackage();
            cachedAt = now;
        }
        return cachedByApp;
    }

    /** Total seconds across all study apps. */
    public long appStudySec() {
        long sum = 0;
        for (long s : appSecByPackage().values()) sum += s;
        return sum;
    }

    /** Seconds spent in each live study app today (refreshed at most every 3 seconds). */
    public Map<String, Long> appSeconds() {
        return new HashMap<>(appSecByPackage());
    }


    /**
     * Progress toward the requirement: the combined total in combined mode, or the study app
     * that is furthest behind in individual mode. The in-app timer counts toward every app.
     * (Name kept so existing callers keep working.)
     */
    /** True when individual mode applies (it needs at least one study app to mean anything). */
    public boolean individualActive() {
        return combineMode() == COMBINE_INDIVIDUAL && !studyApps().isEmpty();
    }

    /** What the counter has to reach: the required time, times the number of study apps in individual mode. */
    public long goalSec() {
        long perApp = requiredMin() * 60L;
        return individualActive() ? perApp * studyApps().size() : perApp;
    }

    /** Progress toward the goal. */
    public long totalStudiedSec() {
        if (individualActive()) {
            long cap = requiredMin() * 60L;
            Map<String, Long> byApp = appSecByPackage();
            long sum = studiedSec();                 // new: the Start studying timer counts fully, no cap
            for (String pkg : studyApps()) {
                sum += Math.min(cap, byApp.getOrDefault(pkg, 0L));   // each study app stops at the cap
            }
            return sum;
        }
        return studiedSec() + appStudySec();
    }

    public boolean requirementMet() { return totalStudiedSec() >= goalSec(); }



    // ------------------------------------------------------------------
    // Unlocking
    // ------------------------------------------------------------------

    public boolean canUnlock() {
        return requirementMet() && !p.getBoolean("unlock_used", false);
    }

    public void unlock() {
        long until = (mode() == MODE_REST_OF_DAY)
                ? p.getLong("reset_at", 0)
                : System.currentTimeMillis() + timedMin() * 60_000L;
        p.edit().putLong("unlocked_until", until).putBoolean("unlock_used", true).apply();
    }

    public boolean isUnlocked() {
        return System.currentTimeMillis() < p.getLong("unlocked_until", 0);
    }

    public boolean combineChosen() {
        return p.getBoolean("combine_chosen", false);
    }

    /** The one-time first choice: applies immediately, no queue. */
    public void chooseCombineFirstTime(int mode) {
        p.edit()
                .putInt("combine", mode)
                .remove("pending_combine")
                .putBoolean("combine_chosen", true)
                .apply();
    }
    public boolean requiredChosen() { return p.getBoolean("required_chosen", false); }

    /** The one-time first choice of required study minutes: applies immediately. */
    public void chooseRequiredFirstTime(int minutes) {
        p.edit()
                .putInt("required_min", minutes)
                .remove("pending_required_min")
                .putBoolean("required_chosen", true)
                .apply();
    }

    /** Unlock length in minutes as it is live right now; 0 means until the next reset. */
    public int unlockMinutes() {
        return mode() == MODE_REST_OF_DAY ? 0 : timedMin();
    }

    public boolean unlockChosen() { return p.getBoolean("unlock_chosen", false); }

    public void chooseUnlockFirstTime(int minutes) {
        SharedPreferences.Editor e = p.edit()
                .putInt("mode", minutes == 0 ? MODE_REST_OF_DAY : MODE_TIMED)
                .remove("pending_mode")
                .remove("pending_timed_min")
                .putBoolean("unlock_chosen", true);
        if (minutes > 0) e.putInt("timed_min", minutes);
        e.apply();
    }

    /** Used by the settings screen. */
    public void setUnlockMinutes(int minutes) {
        if (!unlockChosen() && minutes != unlockMinutes()) {
            chooseUnlockFirstTime(minutes);          // first real choice: applies now
            return;
        }
        setPending("mode", minutes == 0 ? MODE_REST_OF_DAY : MODE_TIMED);
        if (minutes > 0) setPending("timed_min", minutes);
    }
}