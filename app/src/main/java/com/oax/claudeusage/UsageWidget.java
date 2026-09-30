package com.oax.claudeusage;

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

import java.util.Map;

/**
 * 홈 화면 위젯. 30분마다 자동 갱신, ↻ 누르면 즉시 갱신, 나머지 부분을 누르면 앱 열림.
 * 크기에 따라 작은 / 한 줄(5x1) / 큰 레이아웃 중 알맞은 것이 표시됨.
 */
public class UsageWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.oax.claudeusage.REFRESH";

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
        for (Class<?> c : new Class<?>[] {UsageWidget.class, UsageWidgetWide.class}) {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, c));
            if (ids != null && ids.length > 0) update(ctx, mgr, ids);
        }
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        long now = System.currentTimeMillis();
        UsageCalc.Result r = new Store(ctx).compute(now);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, r, now));
    }

    private static RemoteViews build(Context ctx, UsageCalc.Result r, long now) {
        RemoteViews small = new RemoteViews(ctx.getPackageName(), R.layout.widget_small);
        RemoteViews wide = new RemoteViews(ctx.getPackageName(), R.layout.widget_wide);
        RemoteViews full = new RemoteViews(ctx.getPackageName(), R.layout.widget_usage);
        RemoteViews fullWide = new RemoteViews(ctx.getPackageName(), R.layout.widget_usage);
        fill(small, r, now, false);
        fillWide(wide, WideModel.of(r), now);
        fill(full, r, now, true);
        fill(fullWide, r, now, true);
        for (RemoteViews v : new RemoteViews[] {small, wide, full, fullWide}) bindClicks(ctx, v);
        // 시스템이 위젯 크기에 맞는(들어가면서 가장 가까운) 레이아웃을 고름:
        // 폭 280dp 이상 한 줄 높이 → 한 줄, 두 줄 이상 → 큰 레이아웃
        Map<SizeF, RemoteViews> m = new ArrayMap<>();
        m.put(new SizeF(100f, 40f), small);
        m.put(new SizeF(280f, 40f), wide);
        m.put(new SizeF(220f, 115f), full);
        m.put(new SizeF(300f, 115f), fullWide);
        return new RemoteViews(m);
    }

    private static void fill(RemoteViews v, UsageCalc.Result r, long now, boolean full) {
        v.setTextViewText(R.id.w_daily, Fmt.today(r));
        v.setTextViewText(R.id.w_refresh, "↻ " + Fmt.clock(now));
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
            String line = Fmt.usageLine(r) + " (" + Fmt.age(r.usageAgeMs) + " 입력)";
            boolean over = r.paceDiffAtInput < 0;
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
        v.setTextViewText(R.id.w_pace_num, m.paceNum);
        v.setViewVisibility(R.id.w_pace_pct, m.showPct ? View.VISIBLE : View.GONE);
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
        // 여유: 사용 = 초록, 여유 = 밝기가 다른 초록(라이트는 더 밝게, 다크는 더 어둡게)
        // 초과: 권장 누적까지 클레이, 넘친 만큼 빨강 / 사용량 입력 전: 권장 누적 = 반투명 클레이
        boolean spare = m.status != null && !m.barOver;
        v.setColorStateList(R.id.w_bar, "setProgressTintList",
                spare ? R.color.claude_ok_bar : R.color.claude_clay);
        v.setColorStateList(R.id.w_bar, "setSecondaryProgressTintList",
                m.barOver ? R.color.claude_over : spare ? R.color.claude_spare : R.color.claude_pace);
        v.setTextViewText(R.id.w_meta, m.meta);
        v.setTextViewText(R.id.w_refresh_time, Fmt.clock(now));
    }

    private static void bindClicks(Context ctx, RemoteViews v) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_root, piOpen);

        Intent refresh = new Intent(ctx, UsageWidget.class).setAction(ACTION_REFRESH);
        PendingIntent piRefresh = PendingIntent.getBroadcast(ctx, 1, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_refresh, piRefresh);
    }
}
