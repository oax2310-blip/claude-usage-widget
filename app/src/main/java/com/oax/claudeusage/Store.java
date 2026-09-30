package com.oax.claudeusage;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정·입력값 저장(폰 안에만 저장됨). */
final class Store {
    private static final String K_RESET = "reset_at";
    private static final String K_PERIOD = "period_days";
    private static final String K_USED = "used";
    private static final String K_USED_AT = "used_at";
    /** 사용량을 claude.ai에서 자동으로 읽었는지(아니면 직접 입력) */
    private static final String K_USED_AUTO = "used_auto";
    /** claude.ai 로그인 연결(자동 읽기) 켜짐 */
    private static final String K_LINKED = "linked";
    private static final String K_SYNC_TRY = "sync_try";
    private static final String K_SYNC_OK = "sync_ok";
    private static final String K_SYNC_ERR = "sync_err";
    private static final String K_SYNC_RELOGIN = "sync_relogin";

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("usage", Context.MODE_PRIVATE);
    }

    long resetAt() { return sp.getLong(K_RESET, 0L); }

    float periodDays() { return sp.getFloat(K_PERIOD, 7f); }

    boolean hasUsed() { return sp.contains(K_USED); }

    float used() { return sp.getFloat(K_USED, -1f); }

    long usedAt() { return sp.getLong(K_USED_AT, 0L); }

    boolean usedAuto() { return sp.getBoolean(K_USED_AUTO, false); }

    boolean linked() { return sp.getBoolean(K_LINKED, false); }

    /** 마지막으로 읽기를 시도한 시각 */
    long syncTry() { return sp.getLong(K_SYNC_TRY, 0L); }

    /** 마지막으로 읽기에 성공한 시각 */
    long syncOk() { return sp.getLong(K_SYNC_OK, 0L); }

    /** 마지막 읽기 실패 이유(성공했으면 null) */
    String syncError() { return sp.getString(K_SYNC_ERR, null); }

    /** 로그인이 풀려서 다시 로그인해야 하는지 */
    boolean syncRelogin() { return sp.getBoolean(K_SYNC_RELOGIN, false); }

    void setResetAt(long t) { sp.edit().putLong(K_RESET, t).apply(); }

    void setPeriodDays(float d) { sp.edit().putFloat(K_PERIOD, d).apply(); }

    void setUsed(float v, long at) {
        sp.edit().putFloat(K_USED, v).putLong(K_USED_AT, at).putBoolean(K_USED_AUTO, false).apply();
    }

    void clearUsed() { sp.edit().remove(K_USED).remove(K_USED_AT).remove(K_USED_AUTO).apply(); }

    void setLinked(boolean b) {
        SharedPreferences.Editor e = sp.edit().putBoolean(K_LINKED, b);
        if (!b) e.remove(K_SYNC_OK).remove(K_SYNC_ERR).remove(K_SYNC_RELOGIN);
        e.apply();
    }

    void setSyncTry(long t) { sp.edit().putLong(K_SYNC_TRY, t).apply(); }

    void setSyncError(String err, boolean relogin) {
        sp.edit().putString(K_SYNC_ERR, err).putBoolean(K_SYNC_RELOGIN, relogin).apply();
    }

    /** claude.ai에서 읽은 값 저장: 사용량 + 초기화 시각(알면) → 연결 켜짐 */
    void applySync(double used, long resetAt, long now) {
        SharedPreferences.Editor e = sp.edit()
                .putFloat(K_USED, (float) used).putLong(K_USED_AT, now).putBoolean(K_USED_AUTO, true)
                .putBoolean(K_LINKED, true).putLong(K_SYNC_OK, now)
                .remove(K_SYNC_ERR).remove(K_SYNC_RELOGIN);
        if (resetAt > 0) e.putLong(K_RESET, resetAt);
        e.apply();
    }

    /** 자동 갱신 때 읽을 차례인지 */
    boolean syncDue(long now, long interval) {
        long t = syncTry();
        return linked() && (now - t >= interval || t > now);
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
            e.remove(K_USED).remove(K_USED_AT).remove(K_USED_AUTO);
        }
        if (e != null) e.apply();
        return r;
    }
}
