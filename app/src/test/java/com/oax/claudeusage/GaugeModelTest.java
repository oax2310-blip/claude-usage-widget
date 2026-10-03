package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class GaugeModelTest {
    private static final float EPS = 0.01f;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 초기화: 2026-10-03(토) 오후 3시 */
    private static final long RESET = UsageCalcTest.at(10, 3, 15, 0);
    /** 수요일 오전 3시: 권장 누적 64.3% */
    private static final long WED = UsageCalcTest.at(9, 30, 3, 0);
    /** 권장 누적 64.3%의 각도 = 450/7 × 2.7 */
    private static final float PACE_SWEEP = 450f / 7f * 2.7f;

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

    private static GaugeModel model(long now, double used, long usedAt) {
        return GaugeModel.of(UsageCalc.compute(RESET, 7, now, used, usedAt, SEOUL));
    }

    @Test
    public void unconfiguredShowsEmptyTrack() {
        GaugeModel m = GaugeModel.of(UsageCalc.compute(0, 7, RESET, Double.NaN, 0, SEOUL));
        assertEquals("--", m.usedNum);
        assertFalse(m.showPct);
        assertFalse(m.showPace);
        assertEquals(0f, m.okSweep, EPS);
        assertEquals(0f, m.overSweep, EPS);
        assertEquals("Claude 사용량: 초기화 시각을 설정하세요", m.description);
    }

    @Test
    public void noUsageShowsOnlyPaceTick() {
        GaugeModel m = model(WED, Double.NaN, 0);
        assertEquals("--", m.usedNum);
        assertFalse(m.showPct);
        assertTrue(m.showPace);
        assertEquals(PACE_SWEEP, m.paceSweep, EPS);
        assertEquals(0f, m.okSweep, EPS);
        assertEquals(0f, m.overSweep, EPS);
        assertEquals("Claude 사용량 입력 전, 권장 64.3%", m.description);
    }

    @Test
    public void underPaceFillsUsedInClay() {
        GaugeModel m = model(WED, 50, WED);
        assertEquals("50", m.usedNum);
        assertTrue(m.showPct);
        assertEquals(135f, m.okSweep, EPS);
        assertEquals(0f, m.overSweep, EPS);
        assertEquals(PACE_SWEEP, m.paceSweep, EPS);
        assertEquals("Claude 사용량 50%, 권장 64.3% (여유 14.3%p)", m.description);
    }

    @Test
    public void overPaceFillsClayToPaceThenRed() {
        GaugeModel m = model(WED, 70, WED);
        assertEquals("70", m.usedNum);
        assertEquals(PACE_SWEEP, m.okSweep, EPS);
        assertEquals(189f - PACE_SWEEP, m.overSweep, EPS);
        assertEquals(189f, m.okSweep + m.overSweep, EPS);
        assertEquals("Claude 사용량 70%, 권장 64.3% (초과 5.7%p)", m.description);
    }

    @Test
    public void fullUsageFillsWholeTrack() {
        GaugeModel m = model(WED, 100, WED);
        assertEquals("100", m.usedNum);
        assertEquals(GaugeModel.SWEEP, m.okSweep + m.overSweep, EPS);
    }

    @Test
    public void paceTickMovesAt3am() {
        long inputAt = UsageCalcTest.at(10, 1, 2, 0);
        GaugeModel before = model(UsageCalcTest.at(10, 1, 2, 59), 69, inputAt);
        assertTrue(before.overSweep > 0);
        GaugeModel after = model(UsageCalcTest.at(10, 1, 4, 59), 69, inputAt);
        assertEquals(0f, after.overSweep, EPS);
        assertEquals(69 * 2.7f, after.okSweep, EPS);
        assertEquals(550f / 7f * 2.7f, after.paceSweep, EPS);
    }
}
