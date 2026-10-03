package com.oax.claudeusage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

/**
 * 한 줄(5x1) 홈 화면 위젯. 왼쪽 주간 사용량(누르면 앱을 열지 않고 입력 팝업, 위쪽에 claude.ai 사용량 페이지) ·
 * 가운데 권장 누적·여유/초과·주간 초기화까지 남은 시간 + 주간 막대 + 5시간 막대·남은 시간 · 오른쪽 새로고침.
 * 30분마다 + 매일 오전 3시(오늘 몫이 더해질 때)·초기화 시각에 자동 갱신, 5시간 값이 있는 동안은 10분쯤마다
 * 남은 시간을 다시 그리고 그 세션이 끝나면 "초기화됨"으로 바꿈.
 * ↻ 누르면 즉시 갱신하고 입력 팝업이 떠서 claude.ai 사용량을 가져오는 즉시 저장, 나머지 부분을 누르면 앱 열림.
 */
public class UsageWidgetWide extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.oax.claudeusage.REFRESH";
    /** 5시간 남은 시간을 다시 그리는 간격(알람이 최대 10분 늦게 올 수 있음) */
    private static final long SESSION_TICK = 5 * UsageCalc.MINUTE;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        update(ctx, mgr, ids);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String a = intent.getAction();
        if (ACTION_REFRESH.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            updateAll(ctx);
        }
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, UsageWidgetWide.class));
        if (ids != null && ids.length > 0) update(ctx, mgr, ids);
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        long now = System.currentTimeMillis();
        Store store = new Store(ctx);
        UsageCalc.Result r = store.compute(now);
        Session s = store.session(now);
        WideModel m = WideModel.of(r, s);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, m, now));
        scheduleNextChange(ctx, r, s, now);
    }

    /**
     * 다시 그릴 때를 예약: 권장 누적이 바뀌는 때(다음 오전 3시, 마지막 날이면 초기화 시각),
     * 5시간 값이 있으면 남은 시간이 바뀌도록 조금 뒤·그 세션이 끝나는 때 중 가장 이른 때.
     * 화면이 꺼져 있으면 폰을 깨우지 않고 켜지는 즉시 반영(RTC). 정확한 알람 권한 없이 최대 10분 안에 실행됨.
     */
    private static void scheduleNextChange(Context ctx, UsageCalc.Result r, Session s, long now) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent refresh = new Intent(ctx, UsageWidgetWide.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long next = r.configured ? r.nextChangeAt : Long.MAX_VALUE;
        if (s.has) next = Math.min(next, Math.min(s.resetAt, now + SESSION_TICK));
        if (next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        am.setWindow(AlarmManager.RTC, next, 10 * UsageCalc.MINUTE, pi);
    }

    private static RemoteViews build(Context ctx, WideModel m, long now) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_wide);
        fill(v, m, now);
        bindClicks(ctx, v);
        return v;
    }

    private static void fill(RemoteViews v, WideModel m, long now) {
        v.setTextViewText(R.id.w_used_num, m.usedNum);
        v.setViewVisibility(R.id.w_used_pct, m.showPct ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_used_label, m.usedLabel);
        v.setViewVisibility(R.id.w_hint, m.showHint ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.w_top_row, m.showHint ? View.GONE : View.VISIBLE);
        if (!m.showHint) {
            boolean hasStatus = m.status != null;
            v.setViewVisibility(R.id.w_dot, hasStatus ? View.VISIBLE : View.GONE);
            v.setImageViewResource(R.id.w_dot, m.statusOver ? R.drawable.dot_over : R.drawable.dot_ok);
            v.setTextViewText(R.id.w_top_label, m.topLabel);
            v.setTextViewText(R.id.w_top_value, m.topValue);
            v.setViewVisibility(R.id.w_status, hasStatus ? View.VISIBLE : View.GONE);
            v.setTextViewText(R.id.w_status, hasStatus ? m.status : "");
            v.setColor(R.id.w_status, "setTextColor",
                    m.statusOver ? R.color.claude_warn_text : R.color.claude_ok_text);
            v.setTextViewText(R.id.w_reset_in, m.resetIn);
        }
        v.setProgressBar(R.id.w_bar, WideModel.BAR_MAX, m.barProgress, false);
        v.setInt(R.id.w_bar, "setSecondaryProgress", m.barSecondary);
        // 여유: 사용 = 초록, 여유 = 연한 초록 / 초과: 권장 누적까지 클레이, 넘친 만큼 빨강
        // 사용량 입력 전: 권장 누적 = 반투명 클레이
        boolean spare = m.status != null && !m.barOver;
        v.setColorStateList(R.id.w_bar, "setProgressTintList",
                spare ? R.color.claude_ok_bar : R.color.claude_clay);
        v.setColorStateList(R.id.w_bar, "setSecondaryProgressTintList",
                m.barOver ? R.color.claude_over : spare ? R.color.claude_spare : R.color.claude_pace);

        // 5시간: 평소 = 무채색 막대 + 진한 숫자, 90% 넘으면 막대·글자 빨강, 값이 없으면 "--"를 흐리게
        v.setProgressBar(R.id.w_session_bar, WideModel.BAR_MAX, m.sessionBar, false);
        v.setColorStateList(R.id.w_session_bar, "setProgressTintList",
                m.sessionHot ? R.color.claude_warn_text : R.color.claude_session);
        v.setTextViewText(R.id.w_session_value, m.sessionValue);
        v.setColor(R.id.w_session_value, "setTextColor", m.sessionHot ? R.color.claude_warn_text
                : m.sessionHas ? R.color.claude_fg : R.color.claude_sub);
        boolean rest = m.sessionRest != null;
        v.setViewVisibility(R.id.w_session_rest, rest ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_session_rest, rest ? m.sessionRest : "");
        v.setColor(R.id.w_session_rest, "setTextColor",
                m.sessionHot ? R.color.claude_warn_text : R.color.claude_sub);

        v.setTextViewText(R.id.w_refresh_time, Fmt.clock(now));
    }

    private static void bindClicks(Context ctx, RemoteViews v) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_root, piOpen);

        // 새로고침: 입력 팝업(위젯도 바로 다시 그림)에서 사용량을 찾으면 기다리지 않고 바로 저장
        Intent refresh = new Intent(ctx, UsageInputActivity.class)
                .putExtra(UsageInputActivity.EXTRA_QUICK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent piRefresh = PendingIntent.getActivity(ctx, 1, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_refresh, piRefresh);

        // 왼쪽 사용량 숫자를 누르면 사용량 입력 창
        Intent input = new Intent(ctx, UsageInputActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent piInput = PendingIntent.getActivity(ctx, 2, input,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_used_box, piInput);
    }
}
