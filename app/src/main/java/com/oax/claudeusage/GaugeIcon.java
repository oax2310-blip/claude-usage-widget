package com.oax.claudeusage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;

/**
 * 1x1 게이지 위젯 그림: 앱 아이콘과 같은 게이지에 실제 사용량(호)·권장 누적(눈금)·사용량 숫자를 그림.
 * 여유면 사용량 호가 초록, 권장 누적을 넘기면 권장 누적까지 클레이 + 넘친 만큼 빨강.
 * 좌표는 아이콘 벡터(ic_launcher_foreground)와 같은 108 격자이고, 아이콘처럼 가운데 72(18~90)만 보임.
 */
final class GaugeIcon {
    private static final float CX = 54f, CY = 56f, RADIUS = 23f, STROKE = 9f;
    /** 가운데 숫자가 트랙 안쪽에 들어가도록 이 폭(격자 단위)을 넘으면 줄임 */
    private static final float TEXT_MAX_W = 30f;

    private GaugeIcon() {}

    static Bitmap draw(Context ctx, GaugeModel m, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // 배경은 이 폰의 아이콘 모양(원·둥근 사각 등)대로 — 홈 화면의 다른 앱 아이콘과 같은 모양
        AdaptiveIconDrawable shape = new AdaptiveIconDrawable(
                new ColorDrawable(ctx.getColor(R.color.icon_bg)), null);
        shape.setBounds(0, 0, size, size);
        shape.draw(c);

        c.scale(size / 72f, size / 72f);
        c.translate(-18f, -18f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        RectF oval = new RectF(CX - RADIUS, CY - RADIUS, CX + RADIUS, CY + RADIUS);
        arc(c, oval, p, ctx.getColor(R.color.gauge_track), 0f, GaugeModel.SWEEP);
        // 넘친 부분을 먼저 그리고 클레이를 위에: 경계가 클레이의 둥근 끝으로 이어짐
        arc(c, oval, p, ctx.getColor(R.color.gauge_over), m.okSweep, m.overSweep);
        arc(c, oval, p, ctx.getColor(m.over ? R.color.claude_clay : R.color.gauge_ok), 0f, m.okSweep);

        if (m.showPace) {
            double a = Math.toRadians(GaugeModel.START + m.paceSweep);
            float sin = (float) Math.sin(a), cos = (float) Math.cos(a);
            p.setStrokeWidth(3.5f);
            p.setColor(ctx.getColor(R.color.gauge_text));
            // 안쪽 끝은 아이콘(15)보다 조금 바깥: 가운데 숫자와 겹치지 않게
            c.drawLine(CX + 17f * sin, CY - 17f * cos, CX + 31f * sin, CY - 31f * cos, p);
        }

        drawNumber(c, ctx, m);
        return b;
    }

    /** from·sweep은 GaugeModel 각도(위 = 0°) — Canvas는 3시 방향이 0°라서 90° 돌림 */
    private static void arc(Canvas c, RectF oval, Paint p, int color, float from, float sweep) {
        if (sweep < 0.5f) return;
        p.setColor(color);
        c.drawArc(oval, GaugeModel.START - 90f + from, sweep, false, p);
    }

    /** 가운데: 사용량 숫자 + 작은 % (한 줄 위젯처럼) */
    private static void drawNumber(Canvas c, Context ctx, GaugeModel m) {
        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
        num.setColor(ctx.getColor(m.showPct ? R.color.gauge_text : R.color.gauge_sub));
        Paint pct = new Paint(num);
        pct.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        pct.setColor(ctx.getColor(R.color.gauge_sub));

        float numSize = 17f, pctSize = 8f, gap = 0.6f;
        num.setTextSize(numSize);
        pct.setTextSize(pctSize);
        float numW = num.measureText(m.usedNum);
        float pctW = m.showPct ? gap + pct.measureText("%") : 0f;
        float k = Math.min(1f, TEXT_MAX_W / (numW + pctW));
        if (k < 1f) {
            num.setTextSize(numSize * k);
            pct.setTextSize(pctSize * k);
            numW *= k;
            pctW *= k;
        }
        // 숫자 높이는 큰 글자 크기로 재서 비율로 줄임(작은 크기에서는 정수 반올림 오차가 커서)
        Rect digit = new Rect();
        Paint probe = new Paint(num);
        probe.setTextSize(100f);
        probe.getTextBounds("0", 0, 1, digit);
        float baseline = CY + digit.height() / 100f * num.getTextSize() / 2f;
        float x = CX - (numW + pctW) / 2f;
        c.drawText(m.usedNum, x, baseline, num);
        if (m.showPct) c.drawText("%", x + numW + gap * k, baseline, pct);
    }
}
