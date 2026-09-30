package com.oax.claudeusage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 주간 한도 페이스 계산. 안드로이드 의존성 없음(단위 테스트 가능).
 *
 * 권장 누적은 하루 단위로 늘어난다: 매일 오전 3시(DAY_START_HOUR)에 그날 몫이 한 번에 더해진다.
 * 초기화 시각이 오전 3시가 아니면 첫날·마지막날은 조각난 날이 되어 길이에 비례해 몫을 나눠 갖는다.
 * 예) 초기화 토 오후 3시, 주기 7일 → 토 15시~일 3시(12시간, 7.1%) + 하루 14.3% × 6 + 토 3시~15시(12시간, 7.1%) = 8일
 */
public final class UsageCalc {
    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;
    /** 하루가 바뀌는 시각(오전 3시) */
    public static final int DAY_START_HOUR = 3;

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
        /** 지금까지 써도 되는 권장 누적(%) = 오늘까지 지급된 몫의 합 */
        public double paceUsed;
        /** 초기화까지 아직 지급되지 않은 권장량(%) */
        public double paceRemaining;

        /** 이번 주기의 날 수(조각난 첫날·마지막날 포함) */
        public int dayCount;
        /** 오늘이 몇 번째 날인지(1부터, 주기 시작 전이면 0) */
        public int dayIndex;
        /** 오늘 몫(%) — 조각난 날은 길이에 비례해 줄어듦 */
        public double todayAllot;
        /** 다음 몫이 더해지는 시각(마지막 날이면 0) */
        public long nextGrantAt;
        /** 다음에 더해질 몫(%) */
        public double nextAllot;
        /** 권장 누적이 다음으로 바뀌는 시각(다음 오전 3시, 마지막 날이면 초기화 시각) — 이때 화면 다시 그림 */
        public long nextChangeAt;

        /** 이번 주기 안에 입력한 실제 사용량이 있는지 */
        public boolean hasUsage;
        public double used;
        public long usageAgeMs;
        /** 지금 권장 누적 − 현재 사용(+면 여유, −면 초과) — 오전 3시에 오늘 몫이 더해지면 바로 반영 */
        public double paceDiff;
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

    /** t 다음(t 제외)으로 오는 하루 경계(오전 3시) */
    public static long nextDayStart(long t, ZoneId zone) {
        LocalDate d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate();
        long b = d.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli();
        if (b > t) return b;
        return d.plusDays(1).atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /** 시각 t에서의 권장 누적(%) = 주기 시작부터 t가 속한 날의 끝까지의 비율 × 100 */
    public static double paceAt(long windowStart, long resetAt, long t, ZoneId zone) {
        if (t < windowStart) return 0.0;
        if (t >= resetAt) return 100.0;
        long end = Math.min(nextDayStart(t, zone), resetAt);
        return 100.0 * (end - windowStart) / (resetAt - windowStart);
    }

    /**
     * @param used   실제 사용량(%) — 없으면 Double.NaN
     * @param usedAt 사용량을 입력한 시각
     */
    public static Result compute(long resetAt, double periodDays, long now, double used, long usedAt) {
        return compute(resetAt, periodDays, now, used, usedAt, ZoneId.systemDefault());
    }

    public static Result compute(long resetAt, double periodDays, long now, double used, long usedAt,
                                 ZoneId zone) {
        Result r = new Result();
        if (!(periodDays > 0)) periodDays = 7;
        long periodMs = Math.round(periodDays * DAY);
        r.dailyBase = 100.0 / periodDays;
        r.configured = resetAt > 0;
        if (!r.configured) return r;

        r.resetAt = rollForward(resetAt, periodMs, now);
        r.windowStart = r.resetAt - periodMs;
        r.remainingMs = Math.max(0, r.resetAt - now);
        r.paceUsed = paceAt(r.windowStart, r.resetAt, now, zone);
        r.paceRemaining = 100.0 - r.paceUsed;
        days(r, now, zone);
        r.nextChangeAt = r.nextGrantAt > 0 ? r.nextGrantAt : r.resetAt;

        boolean inWindow = usedAt >= r.windowStart || r.windowStart > now;
        r.hasUsage = !Double.isNaN(used) && used >= 0 && usedAt > 0 && inWindow && usedAt <= now + MINUTE;
        if (r.hasUsage) {
            r.used = Math.min(100.0, used);
            r.usageAgeMs = Math.max(0, now - usedAt);
            r.paceDiff = r.paceUsed - r.used;
            r.leftLimit = Math.max(0.0, 100.0 - r.used);
            double days = (double) r.remainingMs / (double) DAY;
            r.lessThanDay = days < 1.0;
            r.dailyAdjusted = r.lessThanDay ? r.leftLimit : r.leftLimit / days;
        }
        return r;
    }

    /** 주기를 오전 3시 경계로 나눈 날 수, 오늘이 몇째 날인지, 오늘·다음 몫 */
    private static void days(Result r, long now, ZoneId zone) {
        double period = r.resetAt - r.windowStart;
        long start = r.windowStart;
        r.dayCount = 0;
        r.dayIndex = now < start ? 0 : -1;
        while (start < r.resetAt) {
            long end = Math.min(nextDayStart(start, zone), r.resetAt);
            r.dayCount++;
            double allot = 100.0 * (end - start) / period;
            if (r.dayIndex < 0 && now < end) {
                r.dayIndex = r.dayCount;
                r.todayAllot = allot;
            } else if (r.dayIndex >= 0 && r.nextGrantAt == 0 && start > now) {
                r.nextGrantAt = start;
                r.nextAllot = allot;
            }
            start = end;
        }
    }
}
