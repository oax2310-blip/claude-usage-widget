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

/** 홈 화면 위젯. 30분마다 자동 갱신, ↻ 누르면 즉시 갱신, 나머지 부분을 누르면 앱 열림. */
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
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            updateAll(ctx);
        }
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, UsageWidget.class));
        if (ids != null && ids.length > 0) update(ctx, mgr, ids);
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        long now = System.currentTimeMillis();
        UsageCalc.Result r = new Store(ctx).compute(now);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, r, now));
    }

    private static RemoteViews build(Context ctx, UsageCalc.Result r, long now) {
        RemoteViews small = new RemoteViews(ctx.getPackageName(), R.layout.widget_small);
        RemoteViews full = new RemoteViews(ctx.getPackageName(), R.layout.widget_usage);
        fill(small, r, now, false);
        fill(full, r, now, true);
        bindClicks(ctx, small);
        bindClicks(ctx, full);
        Map<SizeF, RemoteViews> m = new ArrayMap<>();
        m.put(new SizeF(100f, 40f), small);
        m.put(new SizeF(220f, 115f), full);
        return new RemoteViews(m);
    }

    private static void fill(RemoteViews v, UsageCalc.Result r, long now, boolean full) {
        v.setTextViewText(R.id.w_daily, Fmt.pct(r.dailyBase));
        v.setTextViewText(R.id.w_refresh, "↻ " + Fmt.clock(now));
        if (!r.configured) {
            v.setTextViewText(R.id.w_remain, "--");
            v.setTextViewText(R.id.w_countdown, "탭해서 초기화 시각을 설정하세요");
            if (full) {
                v.setProgressBar(R.id.w_pace_bar, 1000, 0, false);
                v.setViewVisibility(R.id.w_used_col, View.GONE);
                v.setViewVisibility(R.id.w_usage_ok, View.GONE);
                v.setViewVisibility(R.id.w_usage_over, View.GONE);
            }
            return;
        }
        v.setTextViewText(R.id.w_remain, Fmt.pct(r.paceRemaining));
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
