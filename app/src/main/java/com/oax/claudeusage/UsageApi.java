package com.oax.claudeusage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * claude.ai 설정 → 사용량 화면이 쓰는 주소(공식 API 아님)의 응답 해석. 안드로이드 의존성 없음(단위 테스트 가능).
 * 1) /api/organizations → 내 조직(uuid) 고르기  2) /api/organizations/{uuid}/usage → seven_day(주간 한도)
 */
final class UsageApi {
    static final String ORIGIN = "https://claude.ai";

    private UsageApi() {}

    static String orgsUrl() { return ORIGIN + "/api/organizations"; }

    static String usageUrl(String org) { return orgsUrl() + "/" + org + "/usage"; }

    /** 읽어 온 주간 사용량(%)과 초기화 시각(모르면 0) */
    static final class Usage {
        final double used;
        final long resetAt;

        Usage(double used, long resetAt) {
            this.used = used;
            this.resetAt = resetAt;
        }
    }

    static final class ApiException extends Exception {
        /** 로그인이 풀려서 다시 로그인해야 하는지 */
        final boolean needLogin;

        ApiException(String msg, boolean needLogin) {
            super(msg);
            this.needLogin = needLogin;
        }
    }

    /** WebView evaluateJavascript 결과(JSON 문자열 리터럴)를 풀어 냄. 문자열이 아니면 null */
    static String decodeJs(String value) {
        if (value == null) return null;
        try {
            Object o = new JSONTokener(value).nextValue();
            return o instanceof String ? (String) o : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 페이지 본문에서 JSON 부분만 해석. JSON이 아니면 null(Cloudflare 확인 화면 등 → 다음 페이지를 기다림) */
    static Object json(String text) {
        if (text == null) return null;
        int i = -1;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '{' || c == '[') { i = k; break; }
            if (!Character.isWhitespace(c)) return null;
        }
        if (i < 0) return null;
        try {
            Object v = new JSONTokener(text.substring(i)).nextValue();
            return v instanceof JSONObject || v instanceof JSONArray ? v : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 오류 응답({"type":"error","error":{...}})이면 예외 */
    static void checkError(Object v) throws ApiException {
        if (!(v instanceof JSONObject)) return;
        Object err = ((JSONObject) v).opt("error");
        if (err == null || err == JSONObject.NULL) return;
        String type = "", msg = "", code = "";
        if (err instanceof JSONObject) {
            JSONObject e = (JSONObject) err;
            type = str(e, "type");
            msg = str(e, "message");
            Object d = e.opt("details");
            if (d instanceof JSONObject) code = str((JSONObject) d, "error_code");
        } else if (err instanceof String) {
            msg = (String) err;
        }
        boolean login = type.contains("authentication") || type.contains("permission")
                || code.contains("session");
        if (login) throw new ApiException("로그인이 풀렸어요 — 다시 로그인해 주세요", true);
        throw new ApiException("claude.ai 오류: " + (msg.isEmpty() ? type : msg), false);
    }

    /** 조직 목록에서 사용량을 읽을 조직: 마지막으로 쓴 조직(lastActiveOrg 쿠키) → 채팅 조직 → 아무 조직 */
    static String pickOrg(Object v, String preferred) throws ApiException {
        checkError(v);
        if (!(v instanceof JSONArray)) throw new ApiException("조직 목록을 읽지 못했어요", false);
        JSONArray a = (JSONArray) v;
        String first = null, firstChat = null;
        boolean prefFound = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = str(o, "uuid");
            if (id.isEmpty()) continue;
            boolean chat = hasCapability(o, "chat");
            if (id.equals(preferred)) {
                if (chat) return id;
                prefFound = true;
            }
            if (first == null) first = id;
            if (chat && firstChat == null) firstChat = id;
        }
        if (firstChat != null) return firstChat;
        if (prefFound) return preferred;
        if (first != null) return first;
        throw new ApiException("Claude 계정(조직)을 찾지 못했어요", false);
    }

    /** 사용량 응답의 seven_day(주간 한도): utilization(%) + resets_at */
    static Usage parseUsage(Object v) throws ApiException {
        checkError(v);
        if (!(v instanceof JSONObject)) throw new ApiException("사용량을 읽지 못했어요", false);
        Object w = ((JSONObject) v).opt("seven_day");
        if (!(w instanceof JSONObject)) throw new ApiException("주간 한도 정보가 없어요(요금제에 주간 한도가 없을 수 있어요)", false);
        Object u = ((JSONObject) w).opt("utilization");
        if (!(u instanceof Number)) throw new ApiException("주간 사용량 숫자가 없어요", false);
        double used = Math.max(0.0, Math.min(100.0, ((Number) u).doubleValue()));
        Object r = ((JSONObject) w).opt("resets_at");
        long reset = r instanceof String ? parseTime((String) r) : 0L;
        return new Usage(used, reset);
    }

    /** "2025-11-06T03:59:59.943679+00:00" → 가장 가까운 분(04:00)으로 맞춘 시각. 해석 못 하면 0 */
    static long parseTime(String s) {
        long t;
        try {
            t = OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                t = Instant.parse(s.trim()).toEpochMilli();
            } catch (DateTimeParseException e2) {
                return 0L;
            }
        }
        return Math.floorDiv(t + UsageCalc.MINUTE / 2, UsageCalc.MINUTE) * UsageCalc.MINUTE;
    }

    /** "a=1; lastActiveOrg=xyz" 같은 쿠키 문자열에서 값 찾기. 없으면 null */
    static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equals(name)) {
                String val = p.substring(eq + 1).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /** 쿼리·# 부분을 빼고 같은 주소인지(Cloudflare 확인 뒤 붙는 ?__cf_chl_… 무시) */
    static boolean samePage(String url, String expected) {
        return url != null && stripQuery(url).equals(expected);
    }

    /** claude.ai 로그인 화면인지 */
    static boolean isLoginPage(String url) {
        String p = path(url);
        return p != null && (p.startsWith("/login") || p.startsWith("/magic-link"));
    }

    /** 로그인을 마친 뒤의 claude.ai 화면인지(로그인 화면·API 주소 제외) */
    static boolean isSignedInPage(String url) {
        String p = path(url);
        return p != null && !isLoginPage(url) && !p.startsWith("/api/") && !p.startsWith("/logout");
    }

    /** claude.ai 주소의 경로("/new" 등). claude.ai가 아니면 null */
    private static String path(String url) {
        if (url == null || !url.startsWith(ORIGIN)) return null;
        String rest = stripQuery(url).substring(ORIGIN.length());
        if (rest.isEmpty()) return "/";
        return rest.charAt(0) == '/' ? rest : null;
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int end = url.length();
        if (q >= 0) end = q;
        if (h >= 0 && h < end) end = h;
        return url.substring(0, end);
    }

    private static boolean hasCapability(JSONObject o, String cap) {
        JSONArray caps = o.optJSONArray("capabilities");
        if (caps == null) return false;
        for (int i = 0; i < caps.length(); i++) {
            if (cap.equals(caps.opt(i))) return true;
        }
        return false;
    }

    /** 문자열 값만(없거나 null이면 "") — 안드로이드와 테스트용 org.json 동작 차이를 피함 */
    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : "";
    }
}
