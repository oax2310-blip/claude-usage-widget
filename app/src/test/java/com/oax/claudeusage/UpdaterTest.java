package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdaterTest {
    @Test
    public void versionFromReleaseTag() {
        assertEquals(23, Updater.versionFromTag("v1.0.23"));
        assertEquals(7, Updater.versionFromTag(" v1.0.7 "));
        assertEquals(-1, Updater.versionFromTag("v1.0.x"));
        assertEquals(-1, Updater.versionFromTag(""));
        assertEquals(-1, Updater.versionFromTag(null));
    }
}
