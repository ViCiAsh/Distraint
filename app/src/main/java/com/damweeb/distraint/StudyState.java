package com.damweeb.distraint;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * StudyState: the single source of truth for everything Distraint remembers.
 *
 * Every screen and the accessibility service create their own StudyState(context). They all read
 * and write the same SharedPreferences file ("study_state"), so a change made in one place is seen
 * everywhere. Nothing here runs in the background: time-based changes (the daily reset, unlock
 * windows) are worked out lazily, whenever something calls rollover() or reads a value.
 *
 * HOW THE RULES FIT TOGETHER
 *   1. Study progress = in-app timer (studied_sec) + time spent in study apps (from Android's usage log).
 *   2. When progress reaches goalSec(), canUnlock() turns true and unlock() may be used once per day.
 *   3. unlock() sets unlocked_until. BlockerService asks msLeftFor(pkg) whether a locked app may open.
 *   4. At reset_at, rollover() wipes the day's progress and applies every queued (pending_) change.
 *
 * QUEUED ("PENDING") CHANGES
 *   Changes that could loosen the rules wait for the next reset and are stored as pending_<key>.
 *   Exceptions: the very first choice of a setting applies immediately (the choose...FirstTime()
 *   methods), and changes that only tighten the rules apply at once (locking one more app,
 *   removing a study app).
 *
 * SHARED PREFERENCES KEYS (file "study_state"), handy for grep and for the XML file on the device
 *   Day:      day_start (ms: when today began), reset_at (ms: when the next reset happens)
 *   Settings: required_min, mode (0 until reset / 1 timed), timed_min, combine (0 total / 1 individual),
 *             limit_style (0 shared / 1 per app), rollover_hour, theme
 *   Queued:   pending_<setting> for each setting above, pending_remove (locked apps leaving), pending_study_add
 *   Lists:    blocked, study_apps
 *   Progress: studied_sec, unlocked_until (ms), unlock_at (ms), unlock_used (one unlock per day)
 *   One-time: onboard_step, study_setup_done, combine_chosen, required_chosen, unlock_chosen, limit_chosen
 *
 * DEBUGGING TIPS
 *   - Android Studio > Device File Explorer > data/data/com.damweeb.Distraint/shared_prefs/study_state.xml
 *     shows every key above with its current value.
 *   - Nothing gets locked: is blocked empty? Is isUnlocked() true? In per-app mode, is usage access
 *     missing? (msLeftFor() fails closed and returns 0 without it.)
 *   - Counter looks wrong: check combineMode(), studyApps(), and remember per-app usage is cached for 3 s.
 *   - A setting seems ignored: it is probably queued. Look for pending_<key> in the XML.
 *   - Adding a new queued setting: add it to live(), to the key list in rollover(), and to anyPending().
 */
public class StudyState {
    // Unlock length type: REST_OF_DAY = apps stay open until the next reset; TIMED = open for timed_min minutes.
    public static final int MODE_REST_OF_DAY = 0;
    public static final int MODE_TIMED = 1;

    // How study apps count toward the goal: TOTAL = their time adds up; INDIVIDUAL = the goal is the required
    // minutes times the number of study apps, each app capped at the required minutes (the in-app timer is never capped).
    public static final int COMBINE_TOTAL = 0;
    public static final int COMBINE_INDIVIDUAL = 1;

    // How a timed unlock is counted: SHARED = one wall-clock window for all locked apps;
    // PER_APP = each locked app gets timed_min minutes of its own foreground time.
    public static final int LIMIT_SHARED = 0;
    public static final int LIMIT_PER_APP = 1;

    // Locked-apps list used until one is saved (empty on purpose: nothing is locked by default).
    private static final Set<String> DEFAULT_BLOCKED = new HashSet<>();

    // ctx: application context (safe to hold on to; an Activity context would leak).
    // p: the SharedPreferences file that holds all saved state.
    private final Context ctx;
    private final SharedPreferences p;

    // Short-lived cache of per-app usage so the 200 ms timer loop does not re-read Android's usage log
    // every time. It lives in this instance only: MainActivity keeps one StudyState, so it benefits;
    // BlockerService makes a new one per event, so it always reads fresh numbers.
    private Map<String, Long> cachedByApp = new HashMap<>();
    // elapsedRealtime of the last refresh; -3000 means "never", which forces a recompute on first use.
    private long cachedAt = -3000;

    // Cheap to construct, so create one wherever needed. Only the usage cache is per instance;
    // the saved data is shared by all instances.
    public StudyState(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("study_state", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Daily reset
    // ------------------------------------------------------------------

    // Hour of day (0-23) at which a new study day starts. Default 4, meaning 4 AM.
    public int rolloverHour() { return p.getInt("rollover_hour", 4); }

    // Epoch ms of the next daily reset; 0 until rollover() has run for the first time.
    public long resetAt() { return p.getLong("reset_at", 0); }

    /**
     * First moment strictly after `from` when the clock reads hour:00 (local time).
     * ZonedDateTime is used so daylight-saving changes are handled correctly.
     */
    private static long nextBoundary(long from, int hour) {
        ZonedDateTime t = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault())
                .withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (t.toInstant().toEpochMilli() <= from) t = t.plusDays(1);
        return t.toInstant().toEpochMilli();
    }

    /**
     * Last moment at or before `from` when the clock read hour:00: the start of the study day that
     * contains `from`. Computed as the next boundary minus one day.
     */
    private static long prevBoundary(long from, int hour) {
        return Instant.ofEpochMilli(nextBoundary(from, hour)).atZone(ZoneId.systemDefault())
                .minusDays(1).toInstant().toEpochMilli();
    }

    /**
     * Performs the daily reset if it is due. Call it before reading anything time-dependent (it is called
     * from onResume, refresh() and BlockerService's event handler). No alarm is involved: if the phone was
     * off at the reset time, the reset simply happens the next time anything calls this.
     *
     * Does nothing while now is before reset_at. Otherwise it:
     *   1. promotes every queued setting (pending_<key>) to live,
     *   2. applies queued list changes (locked apps leaving, study apps joining),
     *   3. starts the new day: new day_start/reset_at, progress and unlock cleared.
     *
     * First run: reset_at is 0, so this runs immediately and creates the first day.
     */
    public void rollover() {
        long now = System.currentTimeMillis();
        if (now < p.getLong("reset_at", 0)) return;      // still inside the same study day

        int oldHour = rolloverHour();
        int newHour = oldHour;
        // 1. Promote queued settings. A queued reset hour only decides when the NEXT day ends (see step 3).
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"required_min", "mode", "timed_min", "rollover_hour", "combine", "limit_style"}) {
            if (p.contains("pending_" + k)) {
                int v = p.getInt("pending_" + k, 0);
                e.putInt(k, v).remove("pending_" + k);
                if (k.equals("rollover_hour")) newHour = v;
            }
        }

        // 2. Apply queued list changes: locked apps scheduled for removal leave, queued study apps join.
        Set<String> live = blocked();
        live.removeAll(pendingRemovals());
        Set<String> study = studyApps();
        study.addAll(pendingStudyAdds());

        // 3. Start the new day: record its boundaries and wipe yesterday's progress and unlock.
        e.putStringSet("blocked", live).remove("pending_remove")
                .putStringSet("study_apps", study).remove("pending_study_add")
                // The day that is starting now began at the last boundary of the OLD hour;
                // it ends at the next boundary of the NEW hour (a queued reset-hour change).
                .putLong("day_start", prevBoundary(now, oldHour))
                .putLong("reset_at", nextBoundary(now, newHour))
                .putLong("studied_sec", 0)
                .putLong("unlocked_until", 0)
                .putLong("unlock_at", 0)
                .putBoolean("unlock_used", false)
                .apply();

        cachedAt = -3000;                                // day changed: drop cached usage
    }

    // ------------------------------------------------------------------
    // Settings (changes wait for the next reset)
    // ------------------------------------------------------------------

    // Live (currently active) setting values, with their defaults:
    //   requiredMin  minutes of studying needed per day (30)
    //   mode         unlock length type: MODE_REST_OF_DAY or MODE_TIMED (rest of day)
    //   timedMin     minutes of a timed unlock (60)
    //   combineMode  COMBINE_TOTAL or COMBINE_INDIVIDUAL (total)
    // limitStyle and rolloverHour have their own getters elsewhere in this file.
    public int requiredMin() { return p.getInt("required_min", 30); }
    public int mode() { return p.getInt("mode", MODE_REST_OF_DAY); }
    public int timedMin() { return p.getInt("timed_min", 60); }
    public int combineMode() { return p.getInt("combine", COMBINE_TOTAL); }

    // Returns the LIVE value for a setting key. Add a case for every new queued setting: unknown keys fall
    // into the default branch and silently return the reset hour.
    private int live(String key) {
        switch (key) {
            case "required_min": return requiredMin();
            case "mode":         return mode();
            case "timed_min":    return timedMin();
            case "combine":      return combineMode();
            case "limit_style":  return limitStyle();
            default:             return rolloverHour();   // "rollover_hour"
        }
    }

    /**
     * Queues a setting change for the next reset.
     * key is one of: required_min, mode, timed_min, rollover_hour, combine, limit_style.
     *   - Same value as today: cancels any queued change for that key.
     *   - Different value: stored as pending_<key>; shown() returns it, the live getters do not, until rollover().
     *   - Exceptions: the very first choice of combine, required_min and limit_style applies immediately.
     */
    public void setPending(String key, int value) {
        // The very first real choice of the combine mode applies right away.
        if (key.equals("combine") && !combineChosen() && value != combineMode()) {
            chooseCombineFirstTime(value);
            return;
        }
        // Normal path: queue the change, or cancel a queued one when the user went back to today's value.
        if (value == live(key)) p.edit().remove("pending_" + key).apply();   // same as today: cancel
        else p.edit().putInt("pending_" + key, value).apply();
        // First-time rules for required_min and limit_style. They sit after the write above, which is harmless:
        // choose...FirstTime() removes the pending_ key that was just written. (Odd order, but the result is correct.)
        if (key.equals("required_min") && !requiredChosen() && value != requiredMin()) {
            chooseRequiredFirstTime(value);
            return;
        }
        if (key.equals("limit_style") && !limitChosen() && value != limitStyle()) {
            chooseLimitFirstTime(value);
            return;
        }
    }

    /** What the settings screen shows: the queued value if any, else the live one. */
    public int shown(String key) {
        return p.getInt("pending_" + key, live(key));
    }

    // True if any queued setting is waiting for the next reset. Currently not called by any screen.
    public boolean anyPending() {
        for (String k : new String[]{"required_min", "mode", "timed_min", "rollover_hour", "combine", "limit_style"}) {
            if (p.contains("pending_" + k)) return true;
        }
        return false;
    }

    /**
     * True when each locked app has its own time budget. Needs the per-app style AND a timed unlock;
     * with "until reset" there is no minute limit to split between apps.
     */
    public boolean perAppActive() {
        return limitStyle() == LIMIT_PER_APP && mode() == MODE_TIMED;
    }

    /**
     * Foreground time (ms) this app has used since the user tapped Unlock (unlock_at). Counting from the
     * unlock, not from the start of the day, means the seconds a locked app spends behind the lock
     * screen never eat into its budget.
     */
    private long usedMsSinceUnlock(String pkg) {
        long from = p.getLong("unlock_at", 0);
        if (from == 0) return 0;
        return foregroundMs(Collections.singleton(pkg), from).getOrDefault(pkg, 0L);
    }


    /**
     * Milliseconds this locked app may still be used right now. Zero or less means locked.
     * This is the method BlockerService uses to decide about an app.
     *   - never unlocked, or the unlock has ended: 0
     *   - shared mode: whatever is left of the one overall unlock window
     *   - per-app mode: the app's own budget minus its usage since unlocking, capped by the window
     *     (returns 0 if usage access is missing, because usage cannot be measured: it fails closed)
     */
    public long msLeftFor(String pkg) {
        // Time left in the overall unlock window (zero or negative: it is over).
        long window = unlockedUntil() - System.currentTimeMillis();
        if (window <= 0) return 0;
        // Shared mode: every locked app gets whatever is left of the one window.
        if (!perAppActive()) return window;
        if (!hasUsageAccess()) return 0;              // can't measure, so stay locked
        // Per-app mode: this app's own minutes minus its foreground time since unlocking.
        long left = timedMin() * 60_000L - usedMsSinceUnlock(pkg);
        return Math.min(window, left);
    }

    // Theme applies immediately, so it is not queued. Stored as an AppCompatDelegate.MODE_NIGHT_* constant;
    // App.onCreate() re-applies it every time the app process starts.
    public int themeMode() {
        return p.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setThemeMode(int mode) {
        p.edit().putInt("theme", mode).apply();
    }

    // ------------------------------------------------------------------
    // Locked apps: additions apply now, removals wait for the next reset
    // ------------------------------------------------------------------

    /** Packages that are locked right now, including ones queued for removal (they stay locked until the reset). Returns a copy. */
    public Set<String> blocked() {
        // Copy: the set SharedPreferences returns must never be modified directly.
        return new HashSet<>(p.getStringSet("blocked", DEFAULT_BLOCKED));
    }

    /** Locked apps the user unticked: still locked until the next reset, when rollover() removes them. */
    public Set<String> pendingRemovals() {
        return new HashSet<>(p.getStringSet("pending_remove", new HashSet<>()));
    }

    /**
     * Called by the picker with the full set of ticked packages. Newly ticked apps lock immediately;
     * apps that were locked but are no longer ticked are queued for removal at the next reset.
     */
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

    /** Study apps that count right now. Returns a copy. */
    public Set<String> studyApps() {
        return new HashSet<>(p.getStringSet("study_apps", new HashSet<>()));
    }

    /** Study apps queued to start counting at the next reset. */
    public Set<String> pendingStudyAdds() {
        return new HashSet<>(p.getStringSet("pending_study_add", new HashSet<>()));
    }

    /** True once the first non-empty study list has been saved; until then additions apply immediately. */
    public boolean studySetupDone() {
        return p.getBoolean("study_setup_done", false);
    }

    /**
     * Called by the picker with the full set of ticked study apps.
     *   - First non-empty list ever: applies immediately and sets study_setup_done.
     *   - Afterwards: unticked apps stop counting now; newly ticked apps are queued until the next reset
     *     (otherwise a distracting app could be added as "study" to unlock the games straight away).
     */
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

    /**
     * Number of the next first-run tour question (0 to 8). MainActivity.runOnboarding() advances it BEFORE
     * showing a question, so each one is asked at most once. Cleared only by clearing the app's data.
     */
    public int onboardStep() { return p.getInt("onboard_step", 0); }

    public void setOnboardStep(int step) { p.edit().putInt("onboard_step", step).apply(); }

    // ------------------------------------------------------------------
    // Study progress
    // ------------------------------------------------------------------

    /** Seconds studied with the in-app timer today. */
    public long studiedSec() { return p.getLong("studied_sec", 0); }

    /** Adds seconds to today's in-app timer. MainActivity's tick loop calls it with the whole seconds elapsed. */
    public void addStudied(long sec) {
        p.edit().putLong("studied_sec", studiedSec() + sec).apply();
    }

    /**
     * True if the user granted "Usage access" to this app. Without it every usage-based number is 0,
     * the study apps cannot count, and per-app unlock limits fail closed (msLeftFor() returns 0).
     */
    public boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Milliseconds each of `pkgs` spent in the foreground between `from` and now, read from Android's
     * usage event log (needs usage access). Works like a stopwatch over the event stream: when an app
     * comes to the front its time is noted; when it goes to the background the elapsed time is added.
     * An app still in front right now is counted up to the current moment.
     * Limitations: an app that was already open before `from` is not counted until it is reopened, and
     * Android only keeps events for a limited time (plenty for one day).
     * MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND show as deprecated; they have the same values as
     * ACTIVITY_RESUMED / ACTIVITY_PAUSED and also work on older Android versions.
     */
    private Map<String, Long> foregroundMs(Set<String> pkgs, long from) {
        Map<String, Long> result = new HashMap<>();
        if (pkgs.isEmpty() || !hasUsageAccess()) return result;

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(from, end);
        UsageEvents.Event e = new UsageEvents.Event();
        Map<String, Long> cameToFront = new HashMap<>();

        // Events arrive in time order for all apps; skip the packages we are not tracking.
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            if (!pkgs.contains(pkg)) continue;
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                cameToFront.put(pkg, e.getTimeStamp());
            } else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                Long f = cameToFront.remove(pkg);
                if (f != null) result.merge(pkg, e.getTimeStamp() - f, Long::sum);
            }
        }
        for (Map.Entry<String, Long> en : cameToFront.entrySet()) {
            result.merge(en.getKey(), end - en.getValue(), Long::sum);   // still open right now
        }
        return result;
    }

    /**
     * Seconds per tracked app (study apps and locked apps) since today's reset (day_start). This reads the
     * usage log, so it is slow: other code should call appSecByPackage(), which caches it.
     */
    private Map<String, Long> computeAppSecByPackage() {
        Set<String> tracked = studyApps();
        tracked.addAll(blocked());
        Map<String, Long> result = foregroundMs(tracked, p.getLong("day_start", System.currentTimeMillis()));
        for (Map.Entry<String, Long> en : result.entrySet()) {
            en.setValue(en.getValue() / 1000);                           // milliseconds to seconds
        }
        return result;
    }

    /** Per-app seconds from computeAppSecByPackage(), recomputed at most once every 3 seconds per StudyState. */
    private Map<String, Long> appSecByPackage() {
        long now = SystemClock.elapsedRealtime();
        if (now - cachedAt >= 3000) {
            cachedByApp = computeAppSecByPackage();
            cachedAt = now;
        }
        return cachedByApp;
    }

    /**
     * Total seconds across the STUDY apps only. Locked apps are in the same map but are excluded on purpose;
     * otherwise time spent in games would count as studying.
     */
    public long appStudySec() {
        Map<String, Long> byApp = appSecByPackage();
        long sum = 0;
        for (String pkg : studyApps()) {
            sum += byApp.getOrDefault(pkg, 0L);
        }
        return sum;
    }

    /** Seconds used today per tracked app (study and locked), for the home-screen lists. Returns a copy. */
    public Map<String, Long> appSeconds() {
        return new HashMap<>(appSecByPackage());
    }


    /** True when individual mode applies (it needs at least one study app to mean anything). */
    public boolean individualActive() {
        return combineMode() == COMBINE_INDIVIDUAL && !studyApps().isEmpty();
    }

    /** What the counter has to reach: the required time, times the number of study apps in individual mode. */
    public long goalSec() {
        long perApp = requiredMin() * 60L;
        return individualActive() ? perApp * studyApps().size() : perApp;
    }

    /**
     * Progress toward goalSec(), in seconds.
     *   - Combined mode: in-app timer + time in all study apps.
     *   - Individual mode: in-app timer (never capped) + each study app capped at the required minutes.
     * The name is historical (it started as a plain total). Used by the home ring, the lock screen and requirementMet().
     */
    public long totalStudiedSec() {
        if (individualActive()) {
            long cap = requiredMin() * 60L;
            Map<String, Long> byApp = appSecByPackage();
            long sum = studiedSec();                 // the in-app timer counts fully, no cap
            for (String pkg : studyApps()) {
                sum += Math.min(cap, byApp.getOrDefault(pkg, 0L));   // each study app stops at the cap
            }
            return sum;
        }
        return studiedSec() + appStudySec();
    }

    // True once progress has reached the goal.
    public boolean requirementMet() { return totalStudiedSec() >= goalSec(); }



    // ------------------------------------------------------------------
    // Unlocking
    // ------------------------------------------------------------------

    /** True when the goal is reached and today's single unlock has not been used yet. */
    public boolean canUnlock() {
        return requirementMet() && !p.getBoolean("unlock_used", false);
    }

    /**
     * Starts today's unlock (call only when canUnlock()). Sets unlocked_until: now + timed_min for a timed,
     * shared unlock, otherwise the next reset. unlock_at records the start so per-app budgets count from here.
     */
    public void unlock() {
        long now = System.currentTimeMillis();
        boolean sharedWindow = mode() == MODE_TIMED && limitStyle() == LIMIT_SHARED;
        long until = sharedWindow ? now + timedMin() * 60_000L : p.getLong("reset_at", 0);
        p.edit()
                .putLong("unlocked_until", until)
                .putLong("unlock_at", now)
                .putBoolean("unlock_used", true)
                .apply();
    }

    /**
     * True while the overall unlock window is open. In per-app mode the window lasts until the reset,
     * so use msLeftFor(pkg) to decide about one particular app.
     */
    public boolean isUnlocked() {
        return System.currentTimeMillis() < p.getLong("unlocked_until", 0);
    }

    // ---- First-time choices ----
    // The first value picked for each of these settings (during the first-run tour, or the first real change
    // in Settings) applies immediately and sets a <name>_chosen flag. Every later change is queued.
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

    /** First-time unlock length: 0 = until the next reset, otherwise a timed unlock. Applies immediately. */
    public void chooseUnlockFirstTime(int minutes) {
        SharedPreferences.Editor e = p.edit()
                .putInt("mode", minutes == 0 ? MODE_REST_OF_DAY : MODE_TIMED)
                .remove("pending_mode")
                .remove("pending_timed_min")
                .putBoolean("unlock_chosen", true);
        if (minutes > 0) e.putInt("timed_min", minutes);
        e.apply();
    }

    /**
     * Settings-screen entry point for the unlock length (0 = until the next reset). The first real change
     * applies immediately; later ones are queued as pending mode / timed_min.
     */
    public void setUnlockMinutes(int minutes) {
        if (!unlockChosen() && minutes != unlockMinutes()) {
            chooseUnlockFirstTime(minutes);          // first real choice: applies now
            return;
        }
        setPending("mode", minutes == 0 ? MODE_REST_OF_DAY : MODE_TIMED);
        if (minutes > 0) setPending("timed_min", minutes);
    }

    // Live unlock-counting style: LIMIT_SHARED (one window) or LIMIT_PER_APP (own time per app).
    public int limitStyle() { return p.getInt("limit_style", LIMIT_SHARED); }
    public boolean limitChosen() { return p.getBoolean("limit_chosen", false); }

    /** First-time choice of the unlock-counting style; applies immediately. */
    public void chooseLimitFirstTime(int style) {
        p.edit()
                .putInt("limit_style", style)
                .remove("pending_limit_style")
                .putBoolean("limit_chosen", true)
                .apply();
    }

    // Epoch ms when the current unlock ends (0 = not unlocked today).
    public long unlockedUntil() { return p.getLong("unlocked_until", 0); }
    // Epoch ms when the current unlock started (0 = not unlocked today). Used to warn only once per unlock.
    public long unlockAt() { return p.getLong("unlock_at", 0); }
}