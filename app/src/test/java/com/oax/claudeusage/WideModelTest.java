package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WideModelTest {
    private static final long DAY = UsageCalc.DAY;
    private static final long HOUR = UsageCalc.HOUR;
    private static final long RESET = 1_800_000_000_000L;

    @Test
    public void unconfiguredShowsHint() {
        WideModel m = WideModel.of(UsageCalc.compute(0, 7, RESET, Double.NaN, 0));
        assertTrue(m.showHint);
        assertFalse(m.showPct);
        assertEquals("--", m.paceNum);
        assertEquals("하루 권장 14.3%", m.meta);
        assertEquals(0, m.barProgress);
        assertEquals(0, m.barSecondary);
    }

    @Test
    public void noUsageShowsCountdownAndPace() {
        long now = RESET - (long) (3.5 * DAY);
        WideModel m = WideModel.of(UsageCalc.compute(RESET, 7, now, Double.NaN, 0));
        assertFalse(m.showHint);
        assertTrue(m.showPct);
        assertEquals("50.0", m.paceNum);
        assertEquals("초기화까지 ", m.topLabel);
        assertEquals("3일 12시간", m.topValue);
        assertNull(m.status);
        assertEquals(0, m.barProgress);
        assertEquals(500, m.barSecondary);
        assertFalse(m.barOver);
    }

    @Test
    public void underPaceFillsUsedThenSpare() {
        long now = RESET - (long) (3.5 * DAY);
        WideModel m = WideModel.of(UsageCalc.compute(RESET, 7, now, 30, now));
        assertEquals("사용 ", m.topLabel);
        assertEquals("30.0%", m.topValue);
        assertEquals("여유 20.0%p", m.status);
        assertFalse(m.statusOver);
        assertEquals("하루 14.3% · 3일 12시간 후 초기화", m.meta);
        assertEquals(300, m.barProgress);
        assertEquals(500, m.barSecondary);
        assertFalse(m.barOver);
    }

    @Test
    public void overPaceFillsPaceThenRed() {
        long now = RESET - (long) (3.5 * DAY);
        WideModel m = WideModel.of(UsageCalc.compute(RESET, 7, now, 62.5, now));
        assertEquals("초과 12.5%p", m.status);
        assertTrue(m.statusOver);
        assertEquals(500, m.barProgress);
        assertEquals(625, m.barSecondary);
        assertTrue(m.barOver);
    }

    @Test
    public void barFollowsNowWhileStatusFollowsInputTime() {
        // 입력할 땐 초과였지만(권장 28.6 < 사용 40) 지금은 권장 누적(57.1)이 사용량을 넘음
        long inputAt = RESET - 5 * DAY;
        long now = RESET - 3 * DAY;
        WideModel m = WideModel.of(UsageCalc.compute(RESET, 7, now, 40, inputAt));
        assertTrue(m.statusOver);
        assertTrue(m.status.startsWith("초과 "));
        assertFalse(m.barOver);
        assertEquals(400, m.barProgress);
        assertEquals(571, m.barSecondary);
    }

    @Test
    public void lessThanOneDayUsesHoursInMeta() {
        long now = RESET - 6 * HOUR;
        WideModel m = WideModel.of(UsageCalc.compute(RESET, 7, now, 70, now));
        assertEquals("하루 14.3% · 6시간 0분 후 초기화", m.meta);
    }
}
