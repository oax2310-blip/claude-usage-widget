package com.oax.claudeusage;

/** 한 줄(5x1) 위젯에 표시할 값. 안드로이드 의존성 없음(단위 테스트 가능). */
final class WideModel {
    static final int BAR_MAX = 1000;

    /** 왼쪽 큰 숫자(주간 사용량, % 없이) — 입력 전이면 "--" */
    String usedNum;
    boolean showPct;
    /** 큰 숫자 아래 설명 — 입력 전이면 누르면 입력할 수 있다는 안내 */
    String usedLabel;

    /** 미설정: 윗줄 대신 안내 문구 */
    boolean showHint;

    /** 윗줄: "권장 " + 권장 누적 (+ 여유/초과) */
    String topLabel;
    String topValue;
    /** 여유/초과 — 사용량이 없으면 null */
    String status;
    /** 지금 권장 누적보다 많이 썼는지(점·글자 빨강) */
    boolean statusOver;
    /** 윗줄 오른쪽 끝: 주간 초기화까지 남은 시간 "3일 4시간 후" */
    String resetIn;

    /** 주간 막대(0..BAR_MAX): 여유면 사용(초록) + 여유(연한 초록), 초과면 권장 누적(클레이) + 초과(빨강) */
    int barProgress;
    int barSecondary;
    boolean barOver;

    /** 아랫줄 5시간: 값 "62%" — 가져온 값이 없거나 그 세션이 끝났으면 "--" */
    String sessionValue;
    /** 값 뒤: "· 2시간 14분 후" / "· 초기화됨" — 보여 줄 게 없으면 null */
    String sessionRest;
    /** 5시간 막대(0..BAR_MAX) */
    int sessionBar;
    /** 값이 있는지(없으면 "--"를 흐리게) */
    boolean sessionHas;
    /** 90% 넘게 씀: 막대·글자 빨강 */
    boolean sessionHot;

    static WideModel of(UsageCalc.Result r, Session s) {
        WideModel m = new WideModel();
        session(m, s);
        m.usedNum = r.hasUsage ? Fmt.usedNum(r.used) : "--";
        m.showPct = r.hasUsage;
        m.usedLabel = r.hasUsage ? "주간" : "사용량 입력";
        if (!r.configured) {
            m.showHint = true;
            return m;
        }
        m.topLabel = "권장 ";
        m.topValue = Fmt.pct(r.paceUsed);
        m.resetIn = Fmt.duration(r.remainingMs) + " 후";
        if (!r.hasUsage) {
            m.barSecondary = bar(r.paceUsed);
            return m;
        }
        // 글자·점·막대 모두 지금 권장 누적 기준(오전 3시에 오늘 몫이 더해지면 함께 바뀜)
        m.statusOver = r.paceDiff < 0;
        m.status = Fmt.paceStatus(r.paceDiff);
        // 여유: 사용까지 초록 + 권장 누적까지 연한 초록 / 초과: 권장 누적까지 클레이 + 넘친 만큼 빨강
        if (!m.statusOver) {
            m.barProgress = bar(r.used);
            m.barSecondary = bar(r.paceUsed);
        } else {
            m.barProgress = bar(r.paceUsed);
            m.barSecondary = bar(r.used);
            m.barOver = true;
        }
        return m;
    }

    /** 5시간 줄: 주간 초기화 시각 설정과 상관없이 가져온 값이 있으면 보여 줌 */
    private static void session(WideModel m, Session s) {
        m.sessionHas = s.has;
        if (!s.has) {
            m.sessionValue = "--";
            m.sessionRest = s.expired ? "· 초기화됨" : null;
            return;
        }
        m.sessionValue = Fmt.usedPct(s.used);
        m.sessionBar = bar(s.used);
        m.sessionHot = s.used >= Session.HOT;
        m.sessionRest = s.resetKnown ? "· " + Fmt.duration(s.remainingMs) + " 후" : null;
    }

    private static int bar(double pct) {
        return (int) Math.round(Math.max(0, Math.min(100, pct)) * BAR_MAX / 100.0);
    }
}
