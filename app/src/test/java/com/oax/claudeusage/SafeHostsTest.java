package com.oax.claudeusage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafeHostsTest {
    @Test
    public void claudeAndLoginProvidersStayInPopup() {
        assertTrue(SafeHosts.allowed("https", "claude.ai"));
        assertTrue(SafeHosts.allowed("HTTPS", "Claude.AI"));
        assertTrue(SafeHosts.allowed("https", "support.claude.com"));
        assertTrue(SafeHosts.allowed("https", "www.anthropic.com"));
        assertTrue(SafeHosts.allowed("https", "accounts.google.com"));
        assertTrue(SafeHosts.allowed("https", "accounts.google.co.kr"));
        assertTrue(SafeHosts.allowed("https", "appleid.apple.com"));
    }

    @Test
    public void lookalikesAndOtherSitesGoToBrowser() {
        assertFalse(SafeHosts.allowed("https", "evilclaude.ai"));
        assertFalse(SafeHosts.allowed("https", "claude.ai.evil.com"));
        assertFalse(SafeHosts.allowed("https", "claude-ai.com"));
        assertFalse(SafeHosts.allowed("https", "accounts.google.evil.com"));
        assertFalse(SafeHosts.allowed("https", "example.com"));
        assertFalse(SafeHosts.allowed("http", "claude.ai"));
        assertFalse(SafeHosts.allowed("mailto", null));
        assertFalse(SafeHosts.allowed("https", null));
    }
}
