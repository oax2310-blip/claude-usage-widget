package com.oax.claudeusage;

/** 한 줄(5x1) 위젯에 표시할 값. 안드로이드 의존성 없음(단위 테스트 가능). */
final class WideModel {
    static final int BAR_MAX = 1000;

    /** 왼쪽 큰 숫자(현재 사용량, % 없이) — 입력 전이면 "--" */
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
    /** 입력 시점 기준 권장 누적보다 많이 썼는지(점·글자 빨강) */
    boolean statusOver;

    String meta;

    /** 막대(0..BAR_MAX): 여유면 사용(초록) + 여유(연한 초록), 초과면 권장 누적(클레이) + 초과(빨강) */
    int barProgress;
    int barSecondary;
    boolean barOver;

    static WideModel of(UsageCalc.Result r) {
        WideModel m = new WideModel();
        m.usedNum = r.hasUsage ? Fmt.num(r.used) : "--";
        m.showPct = r.hasUsage;
        m.usedLabel = r.hasUsage ? "현재 사용" : "사용량 입력";
        if (!r.configured) {
            m.showHint = true;
            m.meta = "하루 권장 " + Fmt.pct(r.dailyBase);
            return m;
        }
        m.topLabel = "권장 ";
        m.topValue = Fmt.pct(r.paceUsed);
        m.meta = "오늘 " + Fmt.today(r) + " · " + Fmt.duration(r.remainingMs) + " 후 초기화";
        if (!r.hasUsage) {
            m.barSecondary = bar(r.paceUsed);
            return m;
        }
        m.statusOver = r.paceDiffAtInput < 0;
        m.status = Fmt.paceStatus(r.paceDiffAtInput);
        // 막대는 지금 시점 기준: 권장 누적까지는 클레이, 넘친 만큼은 빨강
        if (r.used <= r.paceUsed) {
            m.barProgress = bar(r.used);
            m.barSecondary = bar(r.paceUsed);
        } else {
            m.barProgress = bar(r.paceUsed);
            m.barSecondary = bar(r.used);
            m.barOver = true;
        }
        return m;
    }

    private static int bar(double pct) {
        return (int) Math.round(Math.max(0, Math.min(100, pct)) * BAR_MAX / 100.0);
    }
}
