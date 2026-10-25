package com.damweeb.distraint;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to the desktop Distraint on the local network (see SyncProtocol for the rules).
 * Everything runs on one background thread; callbacks are delivered on the main thread.
 *   - pairAsync: sends the 6-digit code shown on the PC, stores the shared secret and the PC's address.
 *   - syncAsync: sends this phone's own study seconds for today, receives the PC's, stores them.
 *   - discoverAsync: listens a few seconds for the PC's broadcast to learn its address.
 *
 * DEBUGGING TIPS
 *   - "Can't reach the PC": same Wi-Fi? PC firewall allows Distraint on Private networks? Sync turned on in the PC's Settings?
 *   - "Rejected": phone and PC clocks differ by more than 5 minutes, or the pairing was forgotten on one side.
 *   - Broadcast discovery finds nothing: some routers block it. Type the PC's address (shown in the PC's pairing window).
 */
public final class SyncClient {
    public static final int PORT = 47653, BEACON_PORT = 47654;

    public interface Callback { void done(boolean ok, String message); }
    public interface FoundCallback { void found(String host, int port, String name); }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SyncClient() { }

    private static void post(Callback cb, boolean ok, String msg) {
        if (cb != null) MAIN.post(() -> cb.done(ok, msg));
    }

    // ---------------- pairing ----------------
    public static void pairAsync(Context c, String host, int port, String code, Callback cb) {
        final Context app = c.getApplicationContext();
        POOL.execute(() -> {
            try {
                byte[] nonce = new byte[16];
                new SecureRandom().nextBytes(nonce);
                StudyState st = new StudyState(app);

                JSONObject m = new JSONObject();
                m.put("type", "pair");
                m.put("code", code);
                m.put("nonce", Base64.getEncoder().encodeToString(nonce));
                m.put("id", st.deviceId());
                m.put("name", st.deviceName());

                JSONObject r = new JSONObject(exchange(host, port, m.toString()));
                if (!r.optBoolean("ok")) { post(cb, false, "Wrong or expired code. Open the pairing window on the PC again."); return; }

                byte[] masked = Base64.getDecoder().decode(r.getString("secret"));
                byte[] secret = SyncProtocol.unmask(code, nonce, masked);
                st.setPairing(host, port, Base64.getEncoder().encodeToString(secret));
                st.mergePeerName(r.optString("id", "pc"), r.optString("name", "PC"));
                post(cb, true, "Paired with " + r.optString("name", "PC"));
            } catch (Exception e) {
                post(cb, false, "Can't reach the PC (" + e.getClass().getSimpleName() + "). Check Wi-Fi and the PC's firewall.");
            }
        });
    }

    // ---------------- sync ----------------
    /** Safe to call often: does nothing unless paired. cb may be null. */
    public static void syncAsync(Context c, Callback cb) {
        final Context app = c.getApplicationContext();
        if (new StudyState(app).syncSecret() == null) { post(cb, false, "Not paired yet."); return; }
        POOL.execute(() -> post(cb, syncNow(app), lastMessage));
    }

    private static volatile String lastMessage = "";

    /** One sync round trip. If the PC isn't where we left it (new IP address), looks for it once and retries. */
    private static boolean syncNow(Context app) {
        StudyState st = new StudyState(app);
        String secretB64 = st.syncSecret();
        if (secretB64 == null) { lastMessage = "Not paired yet."; return false; }
        byte[] key = Base64.getDecoder().decode(secretB64);
        try {
            return syncOnce(st, key, st.syncHost(), st.syncPort());
        } catch (Exception first) {
            String[] found = discoverBlocking(app, 3000);
            if (found != null) {
                try {
                    boolean ok = syncOnce(st, key, found[0], Integer.parseInt(found[1]));
                    if (ok) st.setPairing(found[0], Integer.parseInt(found[1]), secretB64);   // remember the new address
                    return ok;
                } catch (Exception ignored) { }
            }
            lastMessage = "Can't reach the PC.";
            return false;
        }
    }

    private static boolean syncOnce(StudyState st, byte[] key, String host, int port) throws Exception {
        st.rollover();
        String id = st.deviceId(), name = st.deviceName(), day = st.currentDayKey();
        long sec = st.localStudiedSec();
        int reset = st.rolloverHour();
        long ts = System.currentTimeMillis() / 1000;

        JSONObject m = new JSONObject();
        m.put("type", "sync");
        m.put("id", id);
        m.put("name", name);
        m.put("day", day);
        m.put("sec", sec);
        m.put("reset", reset);
        m.put("ts", ts);
        m.put("sig", SyncProtocol.sign(key, id, name, day, sec, reset, ts));

        JSONObject r = new JSONObject(exchange(host, port, m.toString()));
        if (!r.optBoolean("ok")) {
            lastMessage = "The PC rejected the message (pairing forgotten, or the clocks differ by over 5 minutes).";
            return false;
        }
        String rid = r.getString("id"), rname = r.optString("name", "PC"), rday = r.getString("day");
        long rsec = r.getLong("sec"), rts = r.getLong("ts");
        int rreset = r.getInt("reset");
        String expect = SyncProtocol.sign(key, rid, rname, rday, rsec, rreset, rts);
        if (!expect.equals(r.getString("sig")) || Math.abs(System.currentTimeMillis() / 1000 - rts) > 300) {
            lastMessage = "The reply could not be verified.";
            return false;
        }
        st.mergePeer(rid, rname, rday, rsec, rreset);
        lastMessage = "Synced with " + rname;
        return true;
    }

    // ---------------- discovery ----------------
    public static void discoverAsync(Context c, int timeoutMs, FoundCallback cb) {
        final Context app = c.getApplicationContext();
        POOL.execute(() -> {
            String[] r = discoverBlocking(app, timeoutMs);
            MAIN.post(() -> { if (r == null) cb.found(null, 0, null); else cb.found(r[0], Integer.parseInt(r[1]), r[2]); });
        });
    }

    /** Listens for the PC's "distraint|id|name|port" broadcast. Returns {host, port, name} or null. */
    private static String[] discoverBlocking(Context app, int timeoutMs) {
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) { lock = wifi.createMulticastLock("distraint"); lock.setReferenceCounted(false); lock.acquire(); }
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(BEACON_PORT));
                long end = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[512];
                while (System.currentTimeMillis() < end) {
                    s.setSoTimeout((int) Math.max(100, end - System.currentTimeMillis()));
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { s.receive(p); } catch (java.net.SocketTimeoutException e) { break; }
                    String[] parts = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\\|");
                    if (parts.length == 4 && parts[0].equals("distraint"))
                        return new String[]{p.getAddress().getHostAddress(), parts[3], parts[2]};
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
        return null;
    }

    // ---------------- plumbing ----------------
    /** Sends one line, reads one line back. */
    private static String exchange(String host, int port, String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String reply = in.readLine();
            if (reply == null) throw new java.io.IOException("no reply");
            return reply;
        }
    }
}
