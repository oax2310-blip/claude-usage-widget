package com.oax.claudeusage;

/** 한 줄(5x1) 위젯에 표시할 값. 안드로이드 의존성 없음(단위 테스트 가능). */
final class WideModel {
    static final int BAR_MAX = 1000;

    /** 큰 숫자(권장 누적, % 없이) — 미설정이면 "--" */
    String paceNum;
    boolean showPct;

    /** 미설정: 윗줄 대신 안내 문구 */
    boolean showHint;

    /** 윗줄: 라벨 + 굵은 값 (+ 여유/초과) */
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
        if (!r.configured) {
            m.paceNum = "--";
            m.showHint = true;
            m.meta = "하루 권장 " + Fmt.pct(r.dailyBase);
            return m;
        }
        String today = "오늘 " + Fmt.today(r);
        m.paceNum = Fmt.num(r.paceUsed);
        m.showPct = true;
        if (!r.hasUsage) {
            m.topLabel = "초기화까지 ";
            m.topValue = Fmt.duration(r.remainingMs);
            m.meta = today + " · " + Fmt.dateShort(r.resetAt) + " 초기화";
            m.barSecondary = bar(r.paceUsed);
            return m;
        }
        m.topLabel = "사용 ";
        m.topValue = Fmt.pct(r.used);
        m.statusOver = r.paceDiffAtInput < 0;
        m.status = Fmt.paceStatus(r.paceDiffAtInput);
        m.meta = today + " · " + Fmt.duration(r.remainingMs) + " 후 초기화";
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
