package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ScriptHudStatusTest {
    @Test public void publishesNonEmptyHudTextOnlyWhenItChanges() {
        ScriptHudStatus status = new ScriptHudStatus();
        assertNull(status.next("  ", 0));
        assertEquals("巡逻 · 崇城野外", status.next(" 巡逻 · 崇城野外 ", 10));
        assertNull(status.next("巡逻 · 崇城野外", 2_000));
    }

    @Test public void coalescesRapidHudChangesAndPublishesTheLatestAfterInterval() {
        ScriptHudStatus status = new ScriptHudStatus();
        assertEquals("准备中", status.next("准备中", 100));
        assertNull(status.next("寻找商人", 600));
        assertEquals("寻找商人", status.next("寻找商人", 1_100));
    }
}
