package com.oax.claudeusage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 입력 팝업에 떠 있는 claude.ai 사용량 페이지의 글자에서 주간 사용량(%)을 찾는다.
 * 안드로이드 의존성 없음(단위 테스트 가능).
 * 실제 한국어 화면은 "현재 세션 … 62% 사용됨 / 이번 주 … 77% 사용됨"(Pro).
 * '모든 모델'(요금제에 따라 주간 한도 아래 항목) → '이번 주' → '주간' 등 순서로 기준 글자를 찾은 뒤
 * 그 뒤에 처음 나오는 %를 쓴다.
 * 현재 세션(5시간) %를 잘못 집지 않도록, 기준 글자를 못 찾으면 아무 %나 쓰지 않는다.
 */
final class PageUsage {
    private static final String[] ANCHORS = {"모든 모델", "All models", "이번 주", "This week", "Current week",
            "주간", "주별", "매주", "Weekly", "7일"};
    /** 웹 글자에 섞이는 줄바꿈 없는 공백(&nbsp; 등)과 겹친 공백 → 보통 공백 하나 */
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");
    private static final Pattern PCT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");

    private PageUsage() {}

    /** 주간 사용량(0~100). 못 찾으면 null */
    static Double weeklyPercent(String text) {
        if (text == null) return null;
        text = normalize(text);
        for (String a : ANCHORS) {
            int i = indexOfIgnoreCase(text, a);
            if (i < 0) continue;
            Matcher m = PCT.matcher(text);
            if (!m.find(i + a.length())) continue;
            double v;
            try {
                v = Double.parseDouble(m.group(1).replace(',', '.'));
            } catch (NumberFormatException e) {
                continue;
            }
            if (v >= 0 && v <= 100) return v;
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

    private static String normalize(String text) {
        return SPACES.matcher(text).replaceAll(" ");
    }

    private static int indexOfIgnoreCase(String s, String part) {
        for (int i = 0; i + part.length() <= s.length(); i++) {
            if (s.regionMatches(true, i, part, 0, part.length())) return i;
        }
        return -1;
    }
}
