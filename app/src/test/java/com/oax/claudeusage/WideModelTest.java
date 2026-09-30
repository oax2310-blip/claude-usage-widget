package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class WideModelTest {
    private static final long HOUR = UsageCalc.HOUR;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 초기화: 2026-10-03(토) 오후 3시 */
    private static final long RESET = UsageCalcTest.at(10, 3, 15, 0);
    /** 수요일 오전 3시: 권장 누적 64.3%, 초기화까지 3일 12시간 */
    private static final long WED = UsageCalcTest.at(9, 30, 3, 0);

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

    private static WideModel model(long now, double used, long usedAt) {
        return WideModel.of(UsageCalc.compute(RESET, 7, now, used, usedAt, SEOUL));
    }

    @Test
    public void unconfiguredShowsHint() {
        WideModel m = WideModel.of(UsageCalc.compute(0, 7, RESET, Double.NaN, 0, SEOUL));
        assertTrue(m.showHint);
        assertFalse(m.showPct);
        assertEquals("--", m.usedNum);
        assertEquals("사용량 입력", m.usedLabel);
        assertEquals("하루 권장 14.3%", m.meta);
        assertEquals(0, m.barProgress);
        assertEquals(0, m.barSecondary);
    }

    @Test
    public void noUsageAsksForUsageAndShowsPace() {
        WideModel m = model(WED, Double.NaN, 0);
        assertFalse(m.showHint);
        assertFalse(m.showPct);
        assertEquals("--", m.usedNum);
        assertEquals("사용량 입력", m.usedLabel);
        assertEquals("권장 ", m.topLabel);
        assertEquals("64.3%", m.topValue);
        assertEquals("오늘 14.3% · 3일 12시간 후 초기화", m.meta);
        assertNull(m.status);
        assertEquals(0, m.barProgress);
        assertEquals(643, m.barSecondary);
        assertFalse(m.barOver);
    }

    @Test
    public void underPaceFillsUsedThenSpare() {
        WideModel m = model(WED, 50, WED);
        assertTrue(m.showPct);
        assertEquals("50.0", m.usedNum);
        assertEquals("현재 사용", m.usedLabel);
        assertEquals("권장 ", m.topLabel);
        assertEquals("64.3%", m.topValue);
        assertEquals("여유 14.3%p", m.status);
        assertFalse(m.statusOver);
        assertEquals("오늘 14.3% · 3일 12시간 후 초기화", m.meta);
        assertEquals(500, m.barProgress);
        assertEquals(643, m.barSecondary);
        assertFalse(m.barOver);
    }

    @Test
    public void overPaceFillsPaceThenRed() {
        WideModel m = model(WED, 70, WED);
        assertEquals("초과 5.7%p", m.status);
        assertTrue(m.statusOver);
        assertEquals(643, m.barProgress);
        assertEquals(700, m.barSecondary);
        assertTrue(m.barOver);
    }

    @Test
    public void statusAndBarSwitchTogetherAt3am() {
        // 새벽 2시에 69.0% 입력: 권장 누적 64.3% → 초과
        long inputAt = UsageCalcTest.at(10, 1, 2, 0);
        WideModel m = model(UsageCalcTest.at(10, 1, 2, 59), 69, inputAt);
        assertEquals("64.3%", m.topValue);
        assertEquals("초과 4.7%p", m.status);
        assertTrue(m.statusOver);
        assertTrue(m.barOver);
        assertEquals(643, m.barProgress);
        assertEquals(690, m.barSecondary);

        // 오전 3시 이후: 권장 누적 78.6% → 글자·점·막대 모두 여유
        m = model(UsageCalcTest.at(10, 1, 4, 59), 69, inputAt);
        assertEquals("69.0", m.usedNum);
        assertEquals("78.6%", m.topValue);
        assertEquals("여유 9.6%p", m.status);
        assertFalse(m.statusOver);
        assertFalse(m.barOver);
        assertEquals(690, m.barProgress);
        assertEquals(786, m.barSecondary);
        assertEquals("오늘 14.3% · 2일 10시간 후 초기화", m.meta);
    }

    @Test
    public void lastDayShowsPartialAllotment() {
        long now = RESET - 6 * HOUR;
        WideModel m = model(now, 70, now);
        assertEquals("70.0", m.usedNum);
        assertEquals("100.0%", m.topValue);
        assertEquals("오늘 7.1% · 6시간 0분 후 초기화", m.meta);
    }
}
