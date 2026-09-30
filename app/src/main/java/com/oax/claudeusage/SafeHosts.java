package com.oax.claudeusage;

import java.util.Locale;

/**
 * 입력 팝업(WebView) 안에서 열어도 되는 곳: claude.ai·Anthropic과 로그인 제공자(https만).
 * 나머지는 폰 브라우저로 보낸다 — 팝업엔 주소창이 없어서 다른 사이트가 claude.ai 로그인 화면을
 * 흉내 내도 알아보기 어렵기 때문. 안드로이드 의존성 없음(단위 테스트 가능).
 */
final class SafeHosts {
    private SafeHosts() {}

    static boolean allowed(String scheme, String host) {
        if (!"https".equalsIgnoreCase(scheme) || host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return is(h, "claude.ai") || is(h, "claude.com") || is(h, "anthropic.com")
                || h.equals("accounts.google.com") || h.equals("accounts.google.co.kr")
                || h.equals("appleid.apple.com");
    }

    /** 그 도메인 자신이거나 하위 도메인(evilclaude.ai·claude.ai.evil.com은 아님) */
    private static boolean is(String h, String domain) {
        return h.equals(domain) || h.endsWith("." + domain);
    }
}
