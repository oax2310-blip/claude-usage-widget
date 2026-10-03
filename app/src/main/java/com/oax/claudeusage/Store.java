package com.oax.claudeusage;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정·입력값 저장(폰 안에만 저장됨). */
final class Store {
    private static final String K_RESET = "reset_at";
    private static final String K_PERIOD = "period_days";
    private static final String K_USED = "used";
    private static final String K_USED_AT = "used_at";
    private static final String K_AUTO_SAVE = "auto_save_sec";
    /** 5시간(현재 세션) 사용량·가져온 시각·그 세션의 초기화 시각 */
    private static final String K_SESSION = "session";
    private static final String K_SESSION_AT = "session_at";
    private static final String K_SESSION_RESET = "session_reset_at";
    /** 입력 팝업이 가져온 값을 자동 저장하기까지 기다리는 시간(초) 기본값·최대값 */
    static final float AUTO_SAVE_DEFAULT = 1.2f;
    static final float AUTO_SAVE_MAX = 10f;

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("usage", Context.MODE_PRIVATE);
    }

    long resetAt() { return sp.getLong(K_RESET, 0L); }

    float periodDays() { return sp.getFloat(K_PERIOD, 7f); }

    boolean hasUsed() { return sp.contains(K_USED); }

    float used() { return sp.getFloat(K_USED, -1f); }

    long usedAt() { return sp.getLong(K_USED_AT, 0L); }

    float autoSaveSec() { return sp.getFloat(K_AUTO_SAVE, AUTO_SAVE_DEFAULT); }

    void setResetAt(long t) { sp.edit().putLong(K_RESET, t).apply(); }

    void setPeriodDays(float d) { sp.edit().putFloat(K_PERIOD, d).apply(); }

    void setUsed(float v, long at) { sp.edit().putFloat(K_USED, v).putLong(K_USED_AT, at).apply(); }

    void setAutoSaveSec(float s) { sp.edit().putFloat(K_AUTO_SAVE, s).apply(); }

    /** @param resetAt 페이지에서 읽은 세션 초기화 시각(못 읽었으면 0) */
    void setSession(float v, long resetAt, long at) {
        sp.edit().putFloat(K_SESSION, v).putLong(K_SESSION_RESET, resetAt).putLong(K_SESSION_AT, at).apply();
    }

    /** 입력값(주간·5시간) 지우기 */
    void clearUsed() {
        sp.edit().remove(K_USED).remove(K_USED_AT)
                .remove(K_SESSION).remove(K_SESSION_AT).remove(K_SESSION_RESET).apply();
    }

    /** 5시간 사용량 — 가져온 적이 없거나 그 세션이 끝났으면 has = false */
    Session session(long now) {
        double v = sp.contains(K_SESSION) ? sp.getFloat(K_SESSION, -1f) : Double.NaN;
        return Session.of(v, sp.getLong(K_SESSION_AT, 0L), sp.getLong(K_SESSION_RESET, 0L), now);
    }

    /** 계산 + 지난 초기화 시각 자동 이월 + 지난 주기의 사용량 자동 삭제 */
    UsageCalc.Result compute(long now) {
        double used = hasUsed() ? used() : Double.NaN;
        UsageCalc.Result r = UsageCalc.compute(resetAt(), periodDays(), now, used, usedAt());
        SharedPreferences.Editor e = null;
        if (r.configured && r.resetAt != resetAt()) {
            e = sp.edit();
            e.putLong(K_RESET, r.resetAt);
        }
        if (hasUsed() && !r.hasUsage && r.configured) {
            if (e == null) e = sp.edit();
            e.remove(K_USED).remove(K_USED_AT);
        }
        if (e != null) e.apply();
        return r;
    }
}
