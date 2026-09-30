package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.time.OffsetDateTime;

public class UsageApiTest {
    private static final String ORG_API = "11111111-aaaa-bbbb-cccc-000000000001";
    private static final String ORG_CHAT = "22222222-aaaa-bbbb-cccc-000000000002";
    private static final String ORG_TEAM = "33333333-aaaa-bbbb-cccc-000000000003";
    private static final String ORGS = "[{\"uuid\":\"" + ORG_API + "\",\"capabilities\":[\"api\"]},"
            + "{\"uuid\":\"" + ORG_CHAT + "\",\"capabilities\":[\"chat\",\"claude_max\"]},"
            + "{\"uuid\":\"" + ORG_TEAM + "\",\"capabilities\":[\"chat\",\"raven\"]}]";

    @Test
    public void usageWeeklyUtilizationAndReset() throws Exception {
        String body = "{\"five_hour\":{\"utilization\":6.0,\"resets_at\":\"2025-11-04T04:59:59.943648+00:00\"},"
                + "\"seven_day\":{\"utilization\":35.0,\"resets_at\":\"2025-11-06T03:59:59.943679+00:00\"},"
                + "\"seven_day_oauth_apps\":null,\"seven_day_opus\":{\"utilization\":0,\"resets_at\":null}}";
        UsageApi.Usage u = UsageApi.parseUsage(UsageApi.json(body));
        assertEquals(35.0, u.used, 1e-9);
        // 03:59:59.94 → 04:00 정각
        assertEquals(OffsetDateTime.parse("2025-11-06T04:00:00Z").toInstant().toEpochMilli(), u.resetAt);
    }

    @Test
    public void usageWithoutResetKeepsZeroAndClampsPercent() throws Exception {
        UsageApi.Usage u = UsageApi.parseUsage(UsageApi.json(
                "{\"seven_day\":{\"utilization\":104,\"resets_at\":null}}"));
        assertEquals(100.0, u.used, 1e-9);
        assertEquals(0L, u.resetAt);
    }

    @Test
    public void usageWithoutWeeklyLimitFails() {
        expectError("{\"five_hour\":{\"utilization\":3}}", false);
        expectError("{\"seven_day\":null}", false);
        expectError("{\"seven_day\":{\"utilization\":null}}", false);
    }

    @Test
    public void loggedOutErrorsAskForLogin() {
        expectError("{\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"message\":\"Invalid authorization\","
                + "\"details\":{\"error_visibility\":\"user_facing\",\"error_code\":\"account_session_invalid\"}}}", true);
        expectError("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"x\"}}", true);
        expectError("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}", false);
    }

    @Test
    public void orgPrefersLastActiveChatOrg() throws Exception {
        Object orgs = UsageApi.json(ORGS);
        assertEquals(ORG_TEAM, UsageApi.pickOrg(orgs, ORG_TEAM));
        // 쿠키가 없거나 API 전용 조직이면 첫 채팅 조직
        assertEquals(ORG_CHAT, UsageApi.pickOrg(orgs, null));
        assertEquals(ORG_CHAT, UsageApi.pickOrg(orgs, ORG_API));
        assertEquals(ORG_CHAT, UsageApi.pickOrg(orgs, "gone"));
        // 채팅 조직이 없으면 쿠키 → 첫 조직
        Object apiOnly = UsageApi.json("[{\"uuid\":\"a\"},{\"uuid\":\"b\",\"capabilities\":null}]");
        assertEquals("b", UsageApi.pickOrg(apiOnly, "b"));
        assertEquals("a", UsageApi.pickOrg(apiOnly, null));
    }

    @Test
    public void orgListErrors() {
        try {
            UsageApi.pickOrg(UsageApi.json("[]"), null);
            fail();
        } catch (UsageApi.ApiException e) {
            assertFalse(e.needLogin);
        }
        try {
            UsageApi.pickOrg(UsageApi.json("{\"type\":\"error\",\"error\":{\"type\":\"permission_error\"}}"), null);
            fail();
        } catch (UsageApi.ApiException e) {
            assertTrue(e.needLogin);
        }
    }

    @Test
    public void jsonOnlyFromJsonPages() {
        assertNull(UsageApi.json(null));
        assertNull(UsageApi.json(""));
        assertNull(UsageApi.json("Just a moment...\nVerify you are human"));
        assertNull(UsageApi.json("{not json"));
        assertNull(UsageApi.json("\"text\""));
        assertTrue(UsageApi.json("  \n[1,2]") != null);
        assertTrue(UsageApi.json("{\"a\":1}") != null);
    }

    @Test
    public void decodeEvaluateJavascriptResult() {
        assertEquals("{\"a\":1}", UsageApi.decodeJs("\"{\\\"a\\\":1}\""));
        assertEquals("", UsageApi.decodeJs("\"\""));
        assertNull(UsageApi.decodeJs("null"));
        assertNull(UsageApi.decodeJs(null));
    }

    @Test
    public void cookieValue() {
        String h = "sessionKey=sk-ant-sid01-x; lastActiveOrg=" + ORG_CHAT + "; cf_clearance=a=b";
        assertEquals(ORG_CHAT, UsageApi.cookie(h, "lastActiveOrg"));
        assertEquals("a=b", UsageApi.cookie(h, "cf_clearance"));
        assertNull(UsageApi.cookie(h, "ActiveOrg"));
        assertNull(UsageApi.cookie(null, "lastActiveOrg"));
        assertNull(UsageApi.cookie("lastActiveOrg=", "lastActiveOrg"));
    }

    @Test
    public void pages() {
        assertEquals("https://claude.ai/api/organizations/" + ORG_CHAT + "/usage", UsageApi.usageUrl(ORG_CHAT));
        assertTrue(UsageApi.samePage(UsageApi.orgsUrl() + "?__cf_chl_tk=abc", UsageApi.orgsUrl()));
        assertFalse(UsageApi.samePage(UsageApi.usageUrl(ORG_CHAT), UsageApi.orgsUrl()));
        assertFalse(UsageApi.samePage(null, UsageApi.orgsUrl()));

        assertTrue(UsageApi.isLoginPage("https://claude.ai/login?returnTo=%2F"));
        assertTrue(UsageApi.isLoginPage("https://claude.ai/magic-link#abc"));
        assertFalse(UsageApi.isLoginPage("https://claude.ai/new"));

        assertTrue(UsageApi.isSignedInPage("https://claude.ai/new"));
        assertTrue(UsageApi.isSignedInPage("https://claude.ai"));
        assertTrue(UsageApi.isSignedInPage("https://claude.ai/chat/abc"));
        assertFalse(UsageApi.isSignedInPage("https://claude.ai/login"));
        assertFalse(UsageApi.isSignedInPage("https://claude.ai/api/organizations"));
        assertFalse(UsageApi.isSignedInPage("https://claude.ai.evil.com/new"));
        assertFalse(UsageApi.isSignedInPage("https://accounts.google.com/o/oauth2"));
        assertFalse(UsageApi.isSignedInPage(null));
    }

    @Test
    public void timeParsing() {
        assertEquals(OffsetDateTime.parse("2025-11-06T04:00:00Z").toInstant().toEpochMilli(),
                UsageApi.parseTime("2025-11-06T04:00:10Z"));
        assertEquals(OffsetDateTime.parse("2025-11-06T13:00:00+09:00").toInstant().toEpochMilli(),
                UsageApi.parseTime("2025-11-06T12:59:31.5+09:00"));
        assertEquals(0L, UsageApi.parseTime("다음 주 목요일"));
    }

    private static void expectError(String body, boolean needLogin) {
        try {
            UsageApi.parseUsage(UsageApi.json(body));
            fail("오류여야 함: " + body);
        } catch (UsageApi.ApiException e) {
            assertEquals(body, needLogin, e.needLogin);
        }
    }
}
