package com.oax.claudeusage;

/** 1x1 게이지 위젯에 그릴 값. 안드로이드 의존성 없음(단위 테스트 가능). 각도는 위 = 0°, 시계 방향. */
final class GaugeModel {
    /** 트랙: 왼쪽 아래(-135°)에서 시작해 오른쪽 아래까지 270° — 앱 아이콘과 같은 모양 */
    static final float START = -135f;
    static final float SWEEP = 270f;

    /** 가운데 숫자(현재 사용량, % 없이) — 입력 전이면 "--" */
    String usedNum;
    boolean showPct;

    /** 클레이 호: START부터 이만큼 — 여유면 사용량까지, 초과면 권장 누적까지 */
    float okSweep;
    /** 빨간 호: 클레이 호 끝에서 이만큼 — 권장 누적을 넘긴 만큼(여유면 0) */
    float overSweep;

    /** 권장 누적 눈금: START에서 이만큼 — 초기화 시각 설정 전이면 안 그림 */
    boolean showPace;
    float paceSweep;

    /** 화면 읽기용 설명 */
    String description;

    static GaugeModel of(UsageCalc.Result r) {
        GaugeModel m = new GaugeModel();
        m.usedNum = r.hasUsage ? Fmt.usedNum(r.used) : "--";
        m.showPct = r.hasUsage;
        if (!r.configured) {
            m.description = "Claude 사용량: 초기화 시각을 설정하세요";
            return m;
        }
        m.showPace = true;
        m.paceSweep = sweep(r.paceUsed);
        if (!r.hasUsage) {
            m.description = "Claude 사용량 입력 전, 권장 " + Fmt.pct(r.paceUsed);
            return m;
        }
        // 한 줄 위젯과 같은 기준: 지금 권장 누적보다 많이 썼으면 넘친 부분만 빨강
        boolean over = r.paceDiff < 0;
        m.okSweep = sweep(over ? r.paceUsed : r.used);
        m.overSweep = over ? sweep(r.used) - m.okSweep : 0f;
        m.description = "Claude 사용량 " + Fmt.usedPct(r.used) + ", 권장 " + Fmt.pct(r.paceUsed)
                + " (" + Fmt.paceStatus(r.paceDiff) + ")";
        return m;
    }

    private static float sweep(double pct) {
        return (float) (Math.max(0, Math.min(100, pct)) * SWEEP / 100.0);
    }
}
