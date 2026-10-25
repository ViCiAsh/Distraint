package com.damweeb.distraint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The maths of the sync protocol, with no Android dependencies so it can be tested on a plain JVM.
 * It must match the desktop's SyncService exactly: same signed text, same mask, same day key.
 */
final class SyncProtocol {
    private SyncProtocol() { }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signature of a sync message: HMAC-SHA256 over "id|name|day|sec|reset|ts", base64. */
    static String sign(byte[] key, String id, String name, String day, long sec, int reset, long ts) {
        String text = id + "|" + name + "|" + day + "|" + sec + "|" + reset + "|" + ts;
        return java.util.Base64.getEncoder().encodeToString(hmac(key, text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The shared secret arrives XOR-ed with HMAC(code, nonce); this undoes that. */
    static byte[] unmask(String code, byte[] nonce, byte[] masked) {
        byte[] mask = hmac(code.getBytes(StandardCharsets.UTF_8), nonce);
        byte[] secret = new byte[masked.length];
        for (int i = 0; i < masked.length; i++) secret[i] = (byte) (masked[i] ^ mask[i]);
        return secret;
    }

    /** Study-day key such as "2026-10-25": the local date on which the study day started. */
    static String dayKey(long dayStartMs) {
        LocalDate d = Instant.ofEpochMilli(dayStartMs).atZone(ZoneId.systemDefault()).toLocalDate();
        return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
