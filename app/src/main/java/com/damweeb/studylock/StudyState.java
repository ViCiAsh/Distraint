package com.damweeb.studylock;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** All persistent state: today's study time, unlock window, and settings. */
public class StudyState {
    public static final int MODE_REST_OF_DAY = 0;
    public static final int MODE_TIMED = 1;

    private static final int ROLLOVER_HOUR = 4;
    // Edit these package names for now; a picker screen comes later.
    private static final Set<String> DEFAULT_BLOCKED = new HashSet<>();

    private final SharedPreferences p;

    private final Context ctx;

    public StudyState(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("study_state", Context.MODE_PRIVATE);
    }

    public Set<String> studyApps() {
        return new HashSet<>(p.getStringSet("study_apps", new HashSet<>()));
    }

    public Set<String> pendingStudyAdds() {
        return new HashSet<>(p.getStringSet("pending_study_add", new HashSet<>()));
    }

    public void saveStudyApps(Set<String> chosen) {
        Set<String> live = studyApps();
        live.retainAll(chosen);                    // unticked apps stop counting right now
        Set<String> toAdd = new HashSet<>(chosen);
        toAdd.removeAll(live);                     // newly ticked apps start counting at 4 AM
        p.edit()
                .putStringSet("study_apps", live)
                .putStringSet("pending_study_add", toAdd)
                .apply();
    }

    /** The "study day" starts at 4 AM: shift the clock back 4h and take the date. */
    public int rolloverHour() { return p.getInt("rollover_hour", 4); }

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


    public boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Seconds spent in study apps since today's 4 AM, read from Android's usage log. */
    public long appStudySec() {
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(p.getLong("day_start", end), end);
        UsageEvents.Event e = new UsageEvents.Event();
        Set<String> study = studyApps();
        Map<String, Long> cameToFront = new HashMap<>();
        long totalMs = 0;

        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            if (!study.contains(pkg)) continue;
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                cameToFront.put(pkg, e.getTimeStamp());
            } else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                Long from = cameToFront.remove(pkg);
                if (from != null) totalMs += e.getTimeStamp() - from;
            }
        }
        for (Long from : cameToFront.values()) totalMs += end - from;  // still open right now
        return totalMs / 1000;
    }

    public long totalStudiedSec() {
        return studiedSec() + appStudySec();
    }



    /** Call before reading anything. Performs the 4 AM reset lazily, no alarm needed. */
    public void rollover() {
        long now = System.currentTimeMillis();
        if (now < p.getLong("reset_at", 0)) return;      // still inside the same study day

        int oldHour = rolloverHour();
        int newHour = oldHour;
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"required_min", "mode", "timed_min", "rollover_hour"}) {
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
    }

    // ---- settings (changes wait for the next 4 AM) ----
    public int requiredMin() { return p.getInt("required_min", 30); }
    public int mode() { return p.getInt("mode", MODE_REST_OF_DAY); }
    public int timedMin() { return p.getInt("timed_min", 60); }

    /** key is one of: required_min, mode, timed_min. */
    private int live(String key) {
        switch (key) {
            case "required_min": return requiredMin();
            case "mode":         return mode();
            case "timed_min":    return timedMin();
            default:             return rolloverHour();   // "rollover_hour"
        }
    }

    public void setPending(String key, int value) {
        if (value == live(key)) p.edit().remove("pending_" + key).apply();  // same as today: cancel
        else p.edit().putInt("pending_" + key, value).apply();
    }

    /** What the settings screen shows: the queued value if any, else the live one. */
    public int shown(String key) {
        return p.getInt("pending_" + key, live(key));
    }

    public int themeMode() {
        return p.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setThemeMode(int mode) {
        p.edit().putInt("theme", mode).apply();
    }

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
        toRemove.removeAll(chosen);                 // unticked games leave at the next 4 AM
        p.edit()
                .putStringSet("blocked", live)
                .putStringSet("pending_remove", toRemove)
                .apply();
    }

    // ---- study progress ----
    public long studiedSec() { return p.getLong("studied_sec", 0); }

    public void addStudied(long sec) {
        p.edit().putLong("studied_sec", studiedSec() + sec).apply();
    }

    public boolean requirementMet() { return totalStudiedSec() >= requiredMin() * 60L; }

    // ---- unlocking ----
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
}
