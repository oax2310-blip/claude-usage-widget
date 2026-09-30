package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UsageCalcTest {
    private static final long DAY = UsageCalc.DAY;
    private static final long HOUR = UsageCalc.HOUR;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 초기화: 2026-10-03(토) 오후 3시 → 이번 주기는 9/26(토) 오후 3시부터 */
    private static final long RESET = at(10, 3, 15, 0);
    private static final double FULL = 100.0 / 7;     // 하루 몫 14.29%
    private static final double HALF = 100.0 / 14;    // 12시간짜리 첫날·마지막날 7.14%

    private static TimeZone savedTz;

    @BeforeClass
    public static void useSeoulTime() {
        savedTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(SEOUL));
    }

    @AfterClass
    public static void restoreTime() {
        TimeZone.setDefault(savedTz);
    }

    static long at(int month, int day, int hour, int min) {
        return ZonedDateTime.of(2026, month, day, hour, min, 0, 0, SEOUL).toInstant().toEpochMilli();
    }

    private static UsageCalc.Result calc(long now, double used, long usedAt) {
        return UsageCalc.compute(RESET, 7, now, used, usedAt, SEOUL);
    }

    private static UsageCalc.Result calc(long now) {
        return calc(now, Double.NaN, 0);
    }

    @Test
    public void unconfiguredShowsBaseOnly() {
        UsageCalc.Result r = UsageCalc.compute(0, 7, RESET, Double.NaN, 0, SEOUL);
        assertFalse(r.configured);
        assertEquals(FULL, r.dailyBase, 1e-9);
        assertEquals("14.3%", Fmt.today(r));
    }

    @Test
    public void saturday3pmResetSplitsIntoEightDays() {
        // 첫날: 토 15시~일 3시(12시간) → 하루 몫의 절반
        UsageCalc.Result r = calc(at(9, 26, 15, 0));
        assertEquals(8, r.dayCount);
        assertEquals(1, r.dayIndex);
        assertEquals(HALF, r.todayAllot, 1e-9);
        assertEquals(HALF, r.paceUsed, 1e-9);
        assertEquals(at(9, 27, 3, 0), r.nextGrantAt);
        assertEquals(FULL, r.nextAllot, 1e-9);
        assertEquals(HALF, calc(at(9, 27, 2, 59)).paceUsed, 1e-9);

        // 오전 3시가 되면 그날 몫이 한 번에 더해짐
        r = calc(at(9, 27, 3, 0));
        assertEquals(2, r.dayIndex);
        assertEquals(FULL, r.todayAllot, 1e-9);
        assertEquals(HALF + FULL, r.paceUsed, 1e-9);

        r = calc(at(9, 30, 12, 0)); // 수요일
        assertEquals(5, r.dayIndex);
        assertEquals(HALF + 4 * FULL, r.paceUsed, 1e-9);
        assertEquals("8일 중 5일째 · 다음 +14.3% 10/1(목) 오전 3:00", Fmt.dayLine(r));

        r = calc(at(10, 2, 3, 0)); // 금요일(마지막 온전한 날)
        assertEquals(7, r.dayIndex);
        assertEquals(HALF + 6 * FULL, r.paceUsed, 1e-9);
        assertEquals(at(10, 3, 3, 0), r.nextGrantAt);
        assertEquals(HALF, r.nextAllot, 1e-9);

        // 마지막 날: 토 3시~15시(12시간) → 나머지 절반, 누적 100%
        r = calc(at(10, 3, 3, 0));
        assertEquals(8, r.dayIndex);
        assertEquals(HALF, r.todayAllot, 1e-9);
        assertEquals(100.0, r.paceUsed, 1e-9);
        assertEquals(0.0, r.paceRemaining, 1e-9);
        assertEquals(0, r.nextGrantAt);
        assertEquals("8일 중 8일째 · 마지막 날", Fmt.dayLine(r));
        assertEquals("7.1%", Fmt.today(r));
    }

    @Test
    public void resetAt3amGivesSevenFullDays() {
        long reset = at(10, 3, 3, 0);
        UsageCalc.Result r = UsageCalc.compute(reset, 7, at(9, 26, 3, 0), Double.NaN, 0, SEOUL);
        assertEquals(7, r.dayCount);
        assertEquals(1, r.dayIndex);
        assertEquals(FULL, r.todayAllot, 1e-9);
        assertEquals(FULL, r.paceUsed, 1e-9);
    }

    @Test
    public void rollsForwardAfterReset() {
        long now = RESET + 2 * HOUR;
        UsageCalc.Result r = calc(now);
        assertEquals(RESET + 7 * DAY, r.resetAt);
        assertEquals(RESET, r.windowStart);
        assertEquals(1, r.dayIndex);
        assertEquals(HALF, r.paceUsed, 1e-9);
        // 여러 주가 지나도 다음 초기화로 이동
        long later = RESET + 20 * DAY;
        assertEquals(RESET + 21 * DAY, UsageCalc.rollForward(RESET, 7 * DAY, later));
        // 정확히 초기화 시각이면 다음 주기로
        assertEquals(RESET + 7 * DAY, UsageCalc.rollForward(RESET, 7 * DAY, RESET));
    }

    @Test
    public void beforeWindowStartsNothingGranted() {
        // 초기화 시각을 한 주기보다 더 먼 미래로 넣은 경우
        UsageCalc.Result r = calc(at(9, 23, 12, 0));
        assertEquals(0, r.dayIndex);
        assertEquals(0.0, r.paceUsed, 1e-9);
        assertEquals(at(9, 26, 15, 0), r.nextGrantAt);
        assertEquals(HALF, r.nextAllot, 1e-9);
        assertEquals("주기 시작 전 · 첫 몫 +7.1% 9/26(토) 오후 3:00", Fmt.dayLine(r));
    }

    @Test
    public void usageUnderPace() {
        long now = at(9, 30, 12, 0); // 권장 누적 64.29%, 초기화까지 3일 3시간
        UsageCalc.Result r = calc(now, 50, now);
        assertTrue(r.hasUsage);
        assertEquals(HALF + 4 * FULL - 50, r.paceDiffAtInput, 1e-9);
        assertEquals(50.0, r.leftLimit, 1e-9);
        assertEquals(16.0, r.dailyAdjusted, 1e-9); // 50 / 3.125일
        assertEquals("여유 14.3%p · 남은 기간 하루 16.0%", Fmt.usageLine(r));
    }

    @Test
    public void usageOverPaceUsesInputTime() {
        long inputAt = at(9, 28, 12, 0);   // 월요일: 권장 누적 35.71%
        long now = at(9, 29, 12, 0);
        UsageCalc.Result r = calc(now, 40, inputAt);
        assertTrue(r.hasUsage);
        assertEquals(HALF + 2 * FULL - 40, r.paceDiffAtInput, 1e-9);
        assertEquals(60 / 4.125, r.dailyAdjusted, 1e-9); // 60 / 4일 3시간
        assertEquals(DAY, r.usageAgeMs);
        assertTrue(Fmt.usageLine(r).startsWith("초과 4.3%p"));
    }

    @Test
    public void staleUsageFromPreviousWindowIgnored() {
        long usedAt = RESET - HOUR;   // 지난 주기에 입력
        long now = RESET + HOUR;      // 초기화 이후
        UsageCalc.Result r = calc(now, 80, usedAt);
        assertFalse(r.hasUsage);
    }

    @Test
    public void lessThanOneDayLeft() {
        long now = RESET - 6 * HOUR;  // 마지막 날 오전 9시: 이미 100% 지급
        UsageCalc.Result r = calc(now, 70, now);
        assertTrue(r.lessThanDay);
        assertEquals(30.0, r.dailyAdjusted, 1e-9);
        assertEquals("여유 30.0%p · 초기화 전까지 30.0%", Fmt.usageLine(r));
    }

    @Test
    public void nextDayStartIs3am() {
        assertEquals(at(9, 30, 3, 0), UsageCalc.nextDayStart(at(9, 30, 2, 59), SEOUL));
        assertEquals(at(10, 1, 3, 0), UsageCalc.nextDayStart(at(9, 30, 3, 0), SEOUL));
        assertEquals(at(10, 1, 3, 0), UsageCalc.nextDayStart(at(9, 30, 23, 0), SEOUL));
    }

    @Test
    public void formatting() {
        assertEquals("14.3%", Fmt.pct(100.0 / 7));
        assertEquals("2일 5시간", Fmt.duration(2 * DAY + 5 * HOUR + 59 * UsageCalc.MINUTE));
        assertEquals("5시간 12분", Fmt.duration(5 * HOUR + 12 * UsageCalc.MINUTE));
        assertEquals("12분", Fmt.duration(12 * UsageCalc.MINUTE));
        assertEquals("방금", Fmt.age(30_000));
        assertEquals("3시간 전", Fmt.age(3 * HOUR + 10 * UsageCalc.MINUTE));
    }
}
