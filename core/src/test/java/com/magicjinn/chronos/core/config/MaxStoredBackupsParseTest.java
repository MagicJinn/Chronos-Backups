package com.magicjinn.chronos.core.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class MaxStoredBackupsParseTest {
    @Test
    void oneNumberAppliesToBoth() {
        assertArrayEquals(new int[] { 5, 5 }, ChronosTomlSpec.parseMaxStoredBackups(5));
        assertArrayEquals(new int[] { 5, 5 }, ChronosTomlSpec.parseMaxStoredBackups("5"));
        assertArrayEquals(new int[] { 5, 5 }, ChronosTomlSpec.parseMaxStoredBackups(
                Collections.singletonList(5)));
    }

    @Test
    void commaPairSplitsLocalAndRemote() {
        assertArrayEquals(new int[] { 5, 10 }, ChronosTomlSpec.parseMaxStoredBackups("5, 10"));
        assertArrayEquals(new int[] { 5, 10 }, ChronosTomlSpec.parseMaxStoredBackups("5,10"));
        assertArrayEquals(new int[] { 5, 10 }, ChronosTomlSpec.parseMaxStoredBackups(
                Arrays.asList(5, 10)));
    }

    @Test
    void invalidReturnsNull() {
        assertNull(ChronosTomlSpec.parseMaxStoredBackups(null));
        assertNull(ChronosTomlSpec.parseMaxStoredBackups(""));
        assertNull(ChronosTomlSpec.parseMaxStoredBackups("5,"));
        assertNull(ChronosTomlSpec.parseMaxStoredBackups("5, 10, 15"));
        assertNull(ChronosTomlSpec.parseMaxStoredBackups("nope"));
    }
}
