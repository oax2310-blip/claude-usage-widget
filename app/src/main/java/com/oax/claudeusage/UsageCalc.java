package com.oax.claudeusage;

/** 주간 한도 페이스 계산. 안드로이드 의존성 없음(단위 테스트 가능). */
public final class UsageCalc {
    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private UsageCalc() {}

    public static final class Result {
        /** 초기화 시각이 설정되어 있는지 */
        public boolean configured;
        /** 기본 하루 권장량(%) = 100 / 주기(일) */
        public double dailyBase;
        /** 다음 초기화 시각(지난 경우 주기만큼 자동으로 넘김) */
        public long resetAt;
        public long windowStart;
        public long remainingMs;
        /** 지금까지 써도 되는 권장 누적(%) = 경과 시간 비율 × 100 */
        public double paceUsed;
        /** 지금부터 초기화까지 남은 권장량(%) = 남은 시간 비율 × 100 */
        public double paceRemaining;

        /** 이번 주기 안에 입력한 실제 사용량이 있는지 */
        public boolean hasUsage;
        public double used;
        public long usageAgeMs;
        /** 입력 시점의 권장 누적 − 실제 사용(+면 여유, −면 초과) */
        public double paceDiffAtInput;
        /** 남은 한도(%) = 100 − 사용 */
        public double leftLimit;
        /** 초기화까지 하루 미만 남았는지 */
        public boolean lessThanDay;
        /** 남은 한도를 남은 기간에 고르게 나눈 하루 권장량(%) — 하루 미만이면 남은 한도 그대로 */
        public double dailyAdjusted;
    }

    /** 초기화 시각이 이미 지났으면 주기 단위로 다음 초기화 시각까지 넘긴다. */
    public static long rollForward(long resetAt, long periodMs, long now) {
        if (resetAt <= 0 || periodMs <= 0 || resetAt > now) return resetAt;
        long n = (now - resetAt) / periodMs + 1;
        return resetAt + n * periodMs;
    }

    /** 시각 t에서의 권장 누적(%) */
    public static double paceAt(long resetAt, long periodMs, long t) {
        long start = resetAt - periodMs;
        double f = (double) (t - start) / (double) periodMs;
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        return f * 100.0;
    }

    /**
     * @param used   실제 사용량(%) — 없으면 Double.NaN
     * @param usedAt 사용량을 입력한 시각
     */
    public static Result compute(long resetAt, double periodDays, long now, double used, long usedAt) {
        Result r = new Result();
        if (!(periodDays > 0)) periodDays = 7;
        long periodMs = Math.round(periodDays * DAY);
        r.dailyBase = 100.0 / periodDays;
        r.configured = resetAt > 0;
        if (!r.configured) return r;

        r.resetAt = rollForward(resetAt, periodMs, now);
        r.windowStart = r.resetAt - periodMs;
        r.remainingMs = Math.max(0, r.resetAt - now);
        r.paceUsed = paceAt(r.resetAt, periodMs, now);
        r.paceRemaining = 100.0 - r.paceUsed;

        boolean inWindow = usedAt >= r.windowStart || r.windowStart > now;
        r.hasUsage = !Double.isNaN(used) && used >= 0 && usedAt > 0 && inWindow && usedAt <= now + MINUTE;
        if (r.hasUsage) {
            r.used = Math.min(100.0, used);
            r.usageAgeMs = Math.max(0, now - usedAt);
            r.paceDiffAtInput = paceAt(r.resetAt, periodMs, usedAt) - r.used;
            r.leftLimit = Math.max(0.0, 100.0 - r.used);
            double days = (double) r.remainingMs / (double) DAY;
            r.lessThanDay = days < 1.0;
            r.dailyAdjusted = r.lessThanDay ? r.leftLimit : r.leftLimit / days;
        }
        return r;
    }
}
