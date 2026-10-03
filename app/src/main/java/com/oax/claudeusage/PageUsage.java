package com.oax.claudeusage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 입력 팝업에 떠 있는 claude.ai 사용량 페이지의 글자에서 주간 사용량(%)과 5시간(현재 세션) 사용량을 찾는다.
 * 안드로이드 의존성 없음(단위 테스트 가능).
 * 실제 한국어 화면은 "현재 세션 … 62% 사용됨 / 이번 주 … 77% 사용됨"(Pro).
 * '모든 모델'(요금제에 따라 주간 한도 아래 항목) → '이번 주' → '주간' 등 순서로 기준 글자를 찾은 뒤
 * 그 뒤에 처음 나오는 %를 쓴다.
 * 현재 세션(5시간) %를 잘못 집지 않도록, 기준 글자를 못 찾으면 아무 %나 쓰지 않는다.
 */
final class PageUsage {
    private static final String[] ANCHORS = {"모든 모델", "All models", "이번 주", "This week", "Current week",
            "주간", "주별", "매주", "Weekly", "7일"};
    /** 5시간 한도 칸의 기준 글자 */
    private static final String[] SESSION_ANCHORS = {"현재 세션", "Current session"};
    /** 웹 글자에 섞이는 줄바꿈 없는 공백(&nbsp; 등)과 겹친 공백 → 보통 공백 하나 */
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");
    private static final Pattern PCT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");
    /** 세션 초기화 시각: "오전 10:00에 재설정" / "Resets 10:00 AM" / "10:00" */
    private static final Pattern CLOCK_KO = Pattern.compile("(오전|오후)\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern CLOCK_EN = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*([AaPp])\\.?\\s*[Mm]");
    private static final Pattern CLOCK_24 = Pattern.compile("(\\d{1,2}):(\\d{2})");
    /** 세션 초기화까지 남은 시간: "2시간 10분 후 재설정" / "Resets in 2 hr 10 min" */
    private static final Pattern IN_KO = Pattern.compile("(?:(\\d{1,2})\\s*시간)?\\s*(?:(\\d{1,2})\\s*분)?\\s*후");
    private static final Pattern IN_EN = Pattern.compile(
            "\\bin\\s+(?:(\\d{1,2})\\s*(?:hours?|hrs?|h)\\b)?\\s*(?:(\\d{1,2})\\s*(?:minutes?|mins?|m)\\b)?",
            Pattern.CASE_INSENSITIVE);

    private PageUsage() {}

    /** 페이지에서 읽은 5시간(현재 세션) 사용량 */
    static final class SessionRead {
        /** 사용량(0~100) */
        final double percent;
        /** 초기화 시각(못 읽었으면 0) */
        final long resetAt;
        /** 읽은 시각 */
        final long at;

        SessionRead(double percent, long resetAt, long at) {
            this.percent = percent;
            this.resetAt = resetAt;
            this.at = at;
        }
    }

    /** 주간 사용량(0~100). 못 찾으면 null */
    static Double weeklyPercent(String text) {
        if (text == null) return null;
        text = normalize(text);
        for (String a : ANCHORS) {
            int i = indexOfIgnoreCase(text, a);
            if (i < 0) continue;
            Matcher m = PCT.matcher(text);
            if (!m.find(i + a.length())) continue;
            Double v = percent(m);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 5시간(현재 세션) 사용량과 초기화 시각. '현재 세션' 뒤, 주간 칸이 시작되기 전에 나오는 %만 쓴다
     * (세션 막대가 아직 안 그려졌을 때 주간 %를 집지 않게). 못 찾으면 null
     */
    static SessionRead session(String text, long now, ZoneId zone) {
        if (text == null) return null;
        text = normalize(text);
        for (String a : SESSION_ANCHORS) {
            int i = indexOfIgnoreCase(text, a);
            if (i < 0) continue;
            int from = i + a.length();
            int end = text.length();
            for (String w : ANCHORS) {
                int j = indexOfIgnoreCase(text, w, from);
                if (j >= 0 && j < end) end = j;
            }
            Matcher m = PCT.matcher(text);
            m.region(from, end);
            if (!m.find()) continue;
            Double v = percent(m);
            if (v == null) continue;
            return new SessionRead(v, resetAt(text.substring(from, m.start()), now, zone), now);
        }
        return null;
    }

    /** 글자에서 처음 찾은 기준 글자(못 찾으면 null) — '이유 보기'용 */
    static String anchor(String text) {
        if (text == null) return null;
        text = normalize(text);
        for (String a : ANCHORS) {
            if (indexOfIgnoreCase(text, a) >= 0) return a;
        }
        return null;
    }

    /** 글자 속 % 숫자 개수 — '이유 보기'용 */
    static int percentCount(String text) {
        if (text == null) return 0;
        Matcher m = PCT.matcher(normalize(text));
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static Double percent(Matcher m) {
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        return v >= 0 && v <= 100 ? v : null;
    }

    /** 세션 칸 글자(기준 글자와 % 사이)에서 초기화 시각. 못 읽으면 0 */
    private static long resetAt(String s, long now, ZoneId zone) {
        Matcher m = CLOCK_KO.matcher(s);
        if (m.find()) return clock(now, zone, hour12(m.group(2), m.group(1).equals("오후")), m.group(3));
        m = CLOCK_EN.matcher(s);
        if (m.find()) return clock(now, zone, hour12(m.group(1), m.group(3).equalsIgnoreCase("p")), m.group(2));
        for (Pattern p : new Pattern[] {IN_KO, IN_EN}) {
            m = p.matcher(s);
            while (m.find()) {
                if (m.group(1) == null && m.group(2) == null) continue;
                long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
                long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
                return now + h * UsageCalc.HOUR + min * UsageCalc.MINUTE;
            }
        }
        m = CLOCK_24.matcher(s);
        if (m.find()) return clock(now, zone, Integer.parseInt(m.group(1)), m.group(2));
        return 0;
    }

    private static int hour12(String h, boolean pm) {
        int v = Integer.parseInt(h) % 12;
        return pm ? v + 12 : v;
    }

    /** 오늘 그 시각 — 이미 (10분 넘게) 지났으면 내일 그 시각. 시각이 이상하면 0 */
    private static long clock(long now, ZoneId zone, int hour, String minute) {
        int min = Integer.parseInt(minute);
        if (hour > 23 || min > 59) return 0;
        LocalDate d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long t = d.atTime(hour, min).atZone(zone).toInstant().toEpochMilli();
        if (t < now - 10 * UsageCalc.MINUTE) {
            t = d.plusDays(1).atTime(hour, min).atZone(zone).toInstant().toEpochMilli();
        }
        return t;
    }

    private static String normalize(String text) {
        return SPACES.matcher(text).replaceAll(" ");
    }

    private static int indexOfIgnoreCase(String s, String part) {
        return indexOfIgnoreCase(s, part, 0);
    }

    private static int indexOfIgnoreCase(String s, String part, int from) {
        for (int i = Math.max(0, from); i + part.length() <= s.length(); i++) {
            if (s.regionMatches(true, i, part, 0, part.length())) return i;
        }
        return -1;
    }
}
