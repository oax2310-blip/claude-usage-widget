package com.oax.claudeusage;

/**
 * 5시간 한도(claude.ai '현재 세션') 사용량. 안드로이드 의존성 없음(단위 테스트 가능).
 * 입력 팝업이 사용량 페이지에서 주간 사용량과 함께 읽어 저장한 값이고, 그 세션이 끝나면(초기화 시각이 지나면)
 * 다시 가져올 때까지 값을 보여 주지 않는다(새 세션에서 얼마나 썼는지는 모르니까).
 */
final class Session {
    /** 세션 길이: 시작하고 5시간 뒤 초기화 */
    static final long WINDOW = 5 * UsageCalc.HOUR;
    /** 이만큼(%) 넘게 쓰면 위젯에서 빨강 */
    static final double HOT = 90.0;

    /** 가져온 세션이 아직 안 끝나서 값을 보여 줄 수 있는지 */
    boolean has;
    /** 가져온 세션이 끝남(다시 가져올 때까지 "초기화됨") */
    boolean expired;
    double used;
    /** 페이지에서 초기화 시각을 읽었는지(못 읽었으면 남은 시간을 보여 주지 않음) */
    boolean resetKnown;
    /** 세션 초기화 시각 — 못 읽었으면 가져온 때 + 5시간(늦어도 그때는 끝남) */
    long resetAt;
    long remainingMs;

    /**
     * @param used    가져온 5시간 사용량(%) — 없으면 Double.NaN
     * @param at      가져온 시각
     * @param resetAt 페이지에서 읽은 초기화 시각(못 읽었으면 0)
     */
    static Session of(double used, long at, long resetAt, long now) {
        Session s = new Session();
        if (Double.isNaN(used) || used < 0 || at <= 0 || at > now + UsageCalc.MINUTE) return s;
        long latest = at + WINDOW;
        // 가져온 때부터 5시간 안이어야 이 세션의 초기화 시각(잘못 읽었으면 모르는 것으로)
        s.resetKnown = resetAt >= at - UsageCalc.MINUTE && resetAt <= latest + UsageCalc.MINUTE;
        s.resetAt = s.resetKnown ? resetAt : latest;
        if (now >= s.resetAt) {
            s.expired = true;
            return s;
        }
        s.has = true;
        s.used = Math.min(100.0, used);
        s.remainingMs = s.resetAt - now;
        return s;
    }
}
