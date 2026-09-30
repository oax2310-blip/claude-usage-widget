package com.oax.claudeusage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.ArrayMap;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.Map;

/**
 * 홈 화면 위젯. 30분마다 + 매일 오전 3시(오늘 몫이 더해질 때)·초기화 시각에 자동 갱신,
 * ↻ 누르면 즉시 갱신, 나머지 부분을 누르면 앱 열림.
 * claude.ai에 로그인해 뒀으면 ↻ 때는 바로, 자동 갱신 때는 한동안 안 읽었을 때만 사용량도 새로 읽음.
 * 한 줄 위젯은 왼쪽 사용량 숫자를 누르면 앱을 열지 않고 바로 사용량 입력 창이 뜸.
 * 크기에 따라 작은 / 한 줄(5x1) / 큰 레이아웃 중 알맞은 것이 표시됨.
 */
public class UsageWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.oax.claudeusage.REFRESH";
    /** ↻ 누름: 다시 그리기 + (로그인해 뒀으면) 사용량 새로 읽기 */
    static final String ACTION_SYNC = "com.oax.claudeusage.SYNC";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        update(ctx, mgr, ids);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String a = intent.getAction();
        boolean tap = ACTION_SYNC.equals(a);
        if (tap || ACTION_REFRESH.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            updateAll(ctx);
        }
        if (tap || ACTION_REFRESH.equals(a) || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(a)) {
            sync(ctx, tap);
        }
    }

    /** claude.ai에서 사용량 읽기(로그인해 뒀을 때만). 읽는 동안 ↻ 자리에 표시, 끝나면 다시 그림 */
    private void sync(Context ctx, boolean tap) {
        Store s = new Store(ctx);
        if (!s.linked()) return;
        if (!tap && !s.syncDue(System.currentTimeMillis(), UsageSync.AUTO_INTERVAL)) return;
        Context app = ctx.getApplicationContext();
        PendingResult pending = goAsync();
        UsageSync.run(app, (ok, err) -> {
            updateAll(app);
            if (tap && !ok) Toast.makeText(app, "사용량 읽기 실패: " + err, Toast.LENGTH_LONG).show();
            pending.finish();
        });
        updateAll(app);
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        for (Class<?> c : new Class<?>[] {UsageWidget.class, UsageWidgetWide.class}) {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, c));
            if (ids != null && ids.length > 0) update(ctx, mgr, ids);
        }
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        long now = System.currentTimeMillis();
        Store s = new Store(ctx);
        UsageCalc.Result r = s.compute(now);
        boolean auto = s.usedAuto();
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, r, now, auto));
        scheduleNextChange(ctx, r);
    }

    /**
     * 권장 누적이 바뀌는 때(다음 오전 3시, 마지막 날이면 초기화 시각)에 다시 그리도록 예약.
     * 화면이 꺼져 있으면 폰을 깨우지 않고 켜지는 즉시 반영(RTC). 정확한 알람 권한 없이 최대 10분 안에 실행됨.
     */
    private static void scheduleNextChange(Context ctx, UsageCalc.Result r) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent refresh = new Intent(ctx, UsageWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (!r.configured) {
            am.cancel(pi);
            return;
        }
        am.setWindow(AlarmManager.RTC, r.nextChangeAt, 10 * UsageCalc.MINUTE, pi);
    }

    private static RemoteViews build(Context ctx, UsageCalc.Result r, long now, boolean auto) {
        RemoteViews small = new RemoteViews(ctx.getPackageName(), R.layout.widget_small);
        RemoteViews wide = new RemoteViews(ctx.getPackageName(), R.layout.widget_wide);
        RemoteViews full = new RemoteViews(ctx.getPackageName(), R.layout.widget_usage);
        RemoteViews fullWide = new RemoteViews(ctx.getPackageName(), R.layout.widget_usage);
        fill(small, r, now, false, auto);
        fillWide(wide, WideModel.of(r), now);
        fill(full, r, now, true, auto);
        fill(fullWide, r, now, true, auto);
        for (RemoteViews v : new RemoteViews[] {small, wide, full, fullWide}) bindClicks(ctx, v);
        bindUsageInput(ctx, wide);
        // 시스템이 위젯 크기에 맞는(들어가면서 가장 가까운) 레이아웃을 고름:
        // 폭 280dp 이상 한 줄 높이 → 한 줄, 두 줄 이상 → 큰 레이아웃
        Map<SizeF, RemoteViews> m = new ArrayMap<>();
        m.put(new SizeF(100f, 40f), small);
        m.put(new SizeF(280f, 40f), wide);
        m.put(new SizeF(220f, 115f), full);
        m.put(new SizeF(300f, 115f), fullWide);
        return new RemoteViews(m);
    }

    private static void fill(RemoteViews v, UsageCalc.Result r, long now, boolean full, boolean auto) {
        v.setTextViewText(R.id.w_daily, Fmt.today(r));
        v.setTextViewText(R.id.w_refresh, "↻ " + (UsageSync.busy() ? "읽는 중" : Fmt.clock(now)));
        if (!r.configured) {
            v.setTextViewText(R.id.w_pace, "--");
            v.setTextViewText(R.id.w_countdown, "탭해서 초기화 시각을 설정하세요");
            if (full) {
                v.setProgressBar(R.id.w_pace_bar, 1000, 0, false);
                v.setViewVisibility(R.id.w_used_col, View.GONE);
                v.setViewVisibility(R.id.w_usage_ok, View.GONE);
                v.setViewVisibility(R.id.w_usage_over, View.GONE);
            }
            return;
        }
        v.setTextViewText(R.id.w_pace, Fmt.pct(r.paceUsed));
        if (!full) {
            v.setTextViewText(R.id.w_countdown, Fmt.duration(r.remainingMs) + " 후 초기화");
            return;
        }
        v.setTextViewText(R.id.w_countdown,
                "초기화까지 " + Fmt.duration(r.remainingMs) + " · " + Fmt.dateShort(r.resetAt));
        v.setProgressBar(R.id.w_pace_bar, 1000, (int) Math.round(r.paceUsed * 10), false);
        if (r.hasUsage) {
            v.setViewVisibility(R.id.w_used_col, View.VISIBLE);
            v.setTextViewText(R.id.w_used, Fmt.pct(r.used));
            String line = Fmt.usageLine(r) + " (" + Fmt.age(r.usageAgeMs) + (auto ? " 읽음)" : " 입력)");
            boolean over = r.paceDiff < 0;
            v.setTextViewText(over ? R.id.w_usage_over : R.id.w_usage_ok, line);
            v.setViewVisibility(R.id.w_usage_ok, over ? View.GONE : View.VISIBLE);
            v.setViewVisibility(R.id.w_usage_over, over ? View.VISIBLE : View.GONE);
        } else {
            v.setViewVisibility(R.id.w_used_col, View.GONE);
            v.setViewVisibility(R.id.w_usage_ok, View.GONE);
            v.setViewVisibility(R.id.w_usage_over, View.GONE);
        }
    }

    private static void fillWide(RemoteViews v, WideModel m, long now) {
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
        v.setTextViewText(R.id.w_meta, m.meta);
        v.setTextViewText(R.id.w_refresh_time, UsageSync.busy() ? "…" : Fmt.clock(now));
    }

    private static void bindClicks(Context ctx, RemoteViews v) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_root, piOpen);

        Intent refresh = new Intent(ctx, UsageWidget.class).setAction(ACTION_SYNC);
        PendingIntent piRefresh = PendingIntent.getBroadcast(ctx, 1, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_refresh, piRefresh);
    }

    /** 한 줄 위젯: 왼쪽 사용량 숫자를 누르면 사용량 입력 창 */
    private static void bindUsageInput(Context ctx, RemoteViews v) {
        Intent input = new Intent(ctx, UsageInputActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, input,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_used_box, pi);
    }
}
