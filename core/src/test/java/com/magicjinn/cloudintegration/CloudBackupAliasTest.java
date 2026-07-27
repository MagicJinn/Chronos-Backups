package com.magicjinn.cloudintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudBackupAliasTest {
    @BeforeEach
    void setFruits() {
        CloudBackupAlias.setFruitsForTest(Arrays.asList("apple", "cherry", "mango"));
    }

    @AfterEach
    void clearFruits() {
        CloudBackupAlias.setFruitsForTest(null);
    }

    @Test
    void parseAliasIgnoresCommentsAndBlanks() {
        assertEquals(
                "cherry",
                CloudBackupAlias.parseAliasLines(
                        Arrays.asList(
                                "# comment",
                                "",
                                "  # another",
                                "cherry",
                                "ignored")));
        assertEquals("", CloudBackupAlias.parseAliasLines(Arrays.asList("# only", "")));
        assertEquals("", CloudBackupAlias.parseAliasLines(Collections.<String>emptyList()));
    }

    @Test
    void remoteFolderNamePrependsAlias() {
        assertEquals("world", CloudBackupAlias.remoteFolderName("world", ""));
        assertEquals("world", CloudBackupAlias.remoteFolderName("world", "  "));
        assertEquals("cherry-world", CloudBackupAlias.remoteFolderName("world", "cherry"));
        assertEquals("apple-My World", CloudBackupAlias.remoteFolderName("My World", "apple"));
    }

    @Test
    void pickAliasUsesBareWorldWhenFree() {
        Set<String> existing = new HashSet<String>();
        assertEquals("", CloudBackupAlias.pickAlias(existing, "world"));
    }

    @Test
    void pickAliasWalksFruitsInOrder() {
        Set<String> existing = new HashSet<String>();
        existing.add("world");
        assertEquals("apple", CloudBackupAlias.pickAlias(existing, "world"));

        existing.add("apple-world");
        assertEquals("cherry", CloudBackupAlias.pickAlias(existing, "world"));

        existing.add("cherry-world");
        assertEquals("mango", CloudBackupAlias.pickAlias(existing, "world"));
    }

    @Test
    void pickAliasFallsBackToNumberedFirstFruit() {
        Set<String> existing = new HashSet<String>();
        existing.add("world");
        existing.add("apple-world");
        existing.add("cherry-world");
        existing.add("mango-world");
        assertEquals("apple1", CloudBackupAlias.pickAlias(existing, "world"));

        existing.add("apple1-world");
        assertEquals("apple2", CloudBackupAlias.pickAlias(existing, "world"));
    }

    @Test
    void pickAliasIsCaseInsensitiveOnExistingNames() {
        Set<String> existing = new HashSet<String>();
        existing.add("World");
        existing.add("Apple-World");
        assertEquals("cherry", CloudBackupAlias.pickAlias(existing, "world"));
    }

    @Test
    void parseFruitJsonArrayReadsBundledShape() {
        List<String> fruits =
                CloudBackupAlias.parseFruitJsonArray("[\"apple\",\"cherry\", \"mango\"]");
        assertEquals(Arrays.asList("apple", "cherry", "mango"), fruits);
    }

    @Test
    void loadFruitsReadsClasspathResource() {
        CloudBackupAlias.setFruitsForTest(null);
        List<String> fruits = CloudBackupAlias.loadFruits();
        assertTrue(fruits.contains("apple"));
        assertTrue(fruits.contains("cherry"));
        assertTrue(fruits.size() >= 3);
    }
}
