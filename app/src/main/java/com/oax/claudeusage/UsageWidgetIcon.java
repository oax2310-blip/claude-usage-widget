package com.oax.claudeusage;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * 1x1 게이지 위젯: 앱 아이콘과 같은 모양에 현재 사용량(호: 여유면 초록, 초과면 빨강)·권장 누적(눈금)·사용량 숫자를 그림.
 * 앱 아이콘 자체는 바꿀 수 없어서(안드로이드가 막음) 홈 화면에 아이콘 대신 두는 용도. 누르면 입력 팝업.
 * 다른 위젯처럼 사용량을 저장할 때·오전 3시·초기화 시각에 다시 그림.
 */
public class UsageWidgetIcon extends UsageWidget {
    /** 그림 크기(px): 1x1 칸에 늘려도 흐리지 않을 만큼 */
    private static final int SIZE = 288;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        update(ctx, mgr, ids, true);
    }

    static RemoteViews build(Context ctx, UsageCalc.Result r) {
        GaugeModel m = GaugeModel.of(r);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_icon);
        v.setImageViewBitmap(R.id.w_gauge, GaugeIcon.draw(ctx, m, SIZE));
        v.setContentDescription(R.id.w_gauge, m.description);
        bindUsageInput(ctx, v, R.id.w_root);
        return v;
    }
}
