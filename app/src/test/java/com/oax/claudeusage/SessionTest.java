package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionTest {
    private static final long HOUR = UsageCalc.HOUR;
    private static final long MINUTE = UsageCalc.MINUTE;
    private static final long AT = UsageCalcTest.at(10, 3, 14, 0);

    @Test
    public void nothingReadYet() {
        Session s = Session.of(Double.NaN, 0, 0, AT);
        assertFalse(s.has);
        assertFalse(s.expired);
    }

    @Test
    public void knownResetCountsDown() {
        Session s = Session.of(62, AT, AT + 2 * HOUR + 14 * MINUTE, AT + 30 * MINUTE);
        assertTrue(s.has);
        assertTrue(s.resetKnown);
        assertEquals(62, s.used, 1e-9);
        assertEquals(HOUR + 44 * MINUTE, s.remainingMs);
    }

    @Test
    public void unknownResetEndsFiveHoursAfterReading() {
        Session s = Session.of(40, AT, 0, AT + HOUR);
        assertTrue(s.has);
        assertFalse(s.resetKnown);
        assertEquals(AT + 5 * HOUR, s.resetAt);
        assertTrue(Session.of(40, AT, 0, AT + 5 * HOUR).expired);
    }

    @Test
    public void resetFurtherThanFiveHoursIsNotThisSession() {
        Session s = Session.of(40, AT, AT + 8 * HOUR, AT);
        assertFalse(s.resetKnown);
        assertEquals(AT + 5 * HOUR, s.resetAt);
    }

    @Test
    public void endsAtResetTime() {
        long reset = AT + 2 * HOUR;
        assertTrue(Session.of(80, AT, reset, reset - MINUTE).has);
        Session s = Session.of(80, AT, reset, reset);
        assertFalse(s.has);
        assertTrue(s.expired);
    }

    @Test
    public void clampsAndRejectsOddInput() {
        assertEquals(100, Session.of(120, AT, 0, AT).used, 1e-9);
        assertFalse(Session.of(-1, AT, 0, AT).has);
        // 미래에 가져온 값(시계가 바뀜 등)은 쓰지 않음
        assertFalse(Session.of(50, AT + HOUR, 0, AT).has);
    }
}
