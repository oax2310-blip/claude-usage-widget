package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsageCalcTest {
    private static final long DAY = UsageCalc.DAY;
    private static final long HOUR = UsageCalc.HOUR;
    private static final long RESET = 1_800_000_000_000L; // 임의의 기준 초기화 시각

    @Test
    public void unconfiguredShowsBaseOnly() {
        UsageCalc.Result r = UsageCalc.compute(0, 7, RESET, Double.NaN, 0);
        assertFalse(r.configured);
        assertEquals(100.0 / 7, r.dailyBase, 1e-9);
    }

    @Test
    public void midWindowPace() {
        long now = RESET - (long) (3.5 * DAY);
        UsageCalc.Result r = UsageCalc.compute(RESET, 7, now, Double.NaN, 0);
        assertTrue(r.configured);
        assertEquals(50.0, r.paceUsed, 1e-9);
        assertEquals(50.0, r.paceRemaining, 1e-9);
        assertEquals((long) (3.5 * DAY), r.remainingMs);
        assertFalse(r.hasUsage);
    }

    @Test
    public void rollsForwardAfterReset() {
        long now = RESET + 2 * HOUR;
        UsageCalc.Result r = UsageCalc.compute(RESET, 7, now, Double.NaN, 0);
        assertEquals(RESET + 7 * DAY, r.resetAt);
        assertEquals(RESET, r.windowStart);
        assertEquals(100.0 * 2 / (7 * 24), r.paceUsed, 1e-9);
        // 여러 주가 지나도 다음 초기화로 이동
        long later = RESET + 20 * DAY;
        assertEquals(RESET + 21 * DAY, UsageCalc.rollForward(RESET, 7 * DAY, later));
        // 정확히 초기화 시각이면 다음 주기로
        assertEquals(RESET + 7 * DAY, UsageCalc.rollForward(RESET, 7 * DAY, RESET));
    }

    @Test
    public void usageUnderPace() {
        long now = RESET - (long) (3.5 * DAY);
        UsageCalc.Result r = UsageCalc.compute(RESET, 7, now, 30, now);
        assertTrue(r.hasUsage);
        assertEquals(20.0, r.paceDiffAtInput, 1e-9); // 권장 50 − 사용 30
        assertEquals(70.0, r.leftLimit, 1e-9);
        assertEquals(20.0, r.dailyAdjusted, 1e-9); // 70 / 3.5일
        assertEquals("여유 20.0%p · 남은 기간 하루 20.0%", Fmt.usageLine(r));
    }

    @Test
    public void usageOverPaceUsesInputTime() {
        long inputAt = RESET - 5 * DAY;        // 권장 누적 = 2/7 = 28.57%
        long now = RESET - 4 * DAY;
        UsageCalc.Result r = UsageCalc.compute(RESET, 7, now, 40, inputAt);
        assertTrue(r.hasUsage);
        assertEquals(200.0 / 7 - 40, r.paceDiffAtInput, 1e-9);
        assertEquals(15.0, r.dailyAdjusted, 1e-9); // 60 / 4일
        assertEquals(DAY, r.usageAgeMs);
        assertTrue(Fmt.usageLine(r).startsWith("초과 11.4%p"));
    }

    @Test
    public void staleUsageFromPreviousWindowIgnored() {
        long usedAt = RESET - HOUR;   // 지난 주기에 입력
        long now = RESET + HOUR;      // 초기화 이후
        UsageCalc.Result r = UsageCalc.compute(RESET, 7, now, 80, usedAt);
        assertFalse(r.hasUsage);
    }

    @Test
    public void lessThanOneDayLeft() {
        long now = RESET - 6 * HOUR;
        UsageCalc.Result r = UsageCalc.compute(RESET, 7, now, 70, now);
        assertTrue(r.lessThanDay);
        assertEquals(30.0, r.dailyAdjusted, 1e-9);
        assertEquals("여유 26.4%p · 초기화 전까지 30.0%", Fmt.usageLine(r));
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
