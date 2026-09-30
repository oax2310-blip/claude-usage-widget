package com.oax.claudeusage;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정·입력값 저장(폰 안에만 저장됨). */
final class Store {
    private static final String K_RESET = "reset_at";
    private static final String K_PERIOD = "period_days";
    private static final String K_USED = "used";
    private static final String K_USED_AT = "used_at";

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("usage", Context.MODE_PRIVATE);
    }

    long resetAt() { return sp.getLong(K_RESET, 0L); }

    float periodDays() { return sp.getFloat(K_PERIOD, 7f); }

    boolean hasUsed() { return sp.contains(K_USED); }

    float used() { return sp.getFloat(K_USED, -1f); }

    long usedAt() { return sp.getLong(K_USED_AT, 0L); }

    void setResetAt(long t) { sp.edit().putLong(K_RESET, t).apply(); }

    void setPeriodDays(float d) { sp.edit().putFloat(K_PERIOD, d).apply(); }

    void setUsed(float v, long at) { sp.edit().putFloat(K_USED, v).putLong(K_USED_AT, at).apply(); }

    void clearUsed() { sp.edit().remove(K_USED).remove(K_USED_AT).apply(); }

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
