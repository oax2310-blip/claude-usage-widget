package com.oax.claudeusage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 화면 표시용 문자열 포맷(한국어). */
public final class Fmt {
    private Fmt() {}

    public static String pct(double v) {
        return String.format(Locale.KOREA, "%.1f%%", v);
    }

    /** % 없는 숫자(작은 % 기호를 따로 붙일 때) */
    public static String num(double v) {
        return String.format(Locale.KOREA, "%.1f", v);
    }

    /** 퍼센트포인트(부호 없이) */
    public static String pp(double v) {
        return String.format(Locale.KOREA, "%.1f%%p", Math.abs(v));
    }

    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / UsageCalc.MINUTE;
        long d = totalMin / (60 * 24);
        long h = (totalMin / 60) % 24;
        long m = totalMin % 60;
        if (d > 0) return d + "일 " + h + "시간";
        if (h > 0) return h + "시간 " + m + "분";
        return m + "분";
    }

    public static String age(long ms) {
        long min = Math.max(0, ms) / UsageCalc.MINUTE;
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        long h = min / 60;
        if (h < 24) return h + "시간 전";
        return (h / 24) + "일 전";
    }

    public static String dateLong(long t) {
        return new SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREAN).format(new Date(t));
    }

    public static String dateShort(long t) {
        return new SimpleDateFormat("M/d(E) a h:mm", Locale.KOREAN).format(new Date(t));
    }

    public static String clock(long t) {
        return new SimpleDateFormat("HH:mm", Locale.KOREAN).format(new Date(t));
    }

    /** 사용량 요약 한 줄: "여유 14.0%p · 남은 기간 하루 20.4%" */
    public static String usageLine(UsageCalc.Result r) {
        String pace = paceStatus(r.paceDiffAtInput);
        String plan = r.lessThanDay
                ? "초기화 전까지 " + pct(r.leftLimit)
                : "남은 기간 하루 " + pct(r.dailyAdjusted);
        return pace + " · " + plan;
    }

    /** 권장 누적 대비: "여유 7.8%p" / "초과 8.7%p" */
    public static String paceStatus(double diff) {
        return (diff >= 0 ? "여유 " : "초과 ") + pp(diff);
    }
}
