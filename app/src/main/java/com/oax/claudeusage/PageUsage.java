package com.oax.claudeusage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 입력 팝업에 떠 있는 claude.ai 사용량 페이지의 글자에서 주간 사용량(%)을 찾는다.
 * 안드로이드 의존성 없음(단위 테스트 가능).
 * '모든 모델'(주간 한도의 전체 항목) → '주간' 순서로 찾은 뒤 그 뒤에 처음 나오는 %를 쓴다.
 * 현재 세션(5시간) %를 잘못 집지 않도록, 기준 글자를 못 찾으면 아무 %나 쓰지 않는다.
 */
final class PageUsage {
    private static final String[] ANCHORS = {"모든 모델", "All models", "주간", "Weekly"};
    private static final Pattern PCT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");

    private PageUsage() {}

    /** 주간 사용량(0~100). 못 찾으면 null */
    static Double weeklyPercent(String text) {
        if (text == null) return null;
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

    private static int indexOfIgnoreCase(String s, String part) {
        for (int i = 0; i + part.length() <= s.length(); i++) {
            if (s.regionMatches(true, i, part, 0, part.length())) return i;
        }
        return -1;
    }
}
