package com.example.motioncalm;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Разбор ответа GitHub /releases. Пример повторяет структуру реального ответа API. */
public class UpdateCheckerTest {

    private static final String JSON = "["
            + "{\"tag_name\":\"v1.1-build2\",\"draft\":false,\"prerelease\":false,\"body\":\"Примечания\","
            + "\"assets\":[{\"name\":\"MotionCalm-1.1-build2.apk\",\"size\":2000,"
            + "\"browser_download_url\":\"https://github.com/zigorminsk-debug/DOT/releases/download/v1.1-build2/MotionCalm-1.1-build2.apk\"}]},"
            + "{\"tag_name\":\"v1.0-build1\",\"draft\":false,\"prerelease\":true,\"body\":null,"
            + "\"assets\":[{\"name\":\"notes.txt\",\"size\":10,\"browser_download_url\":\"https://github.com/zigorminsk-debug/DOT/releases/download/v1.0-build1/notes.txt\"}]},"
            + "{\"tag_name\":\"v9.0-build9\",\"draft\":true,\"prerelease\":false,\"body\":\"\",\"assets\":[]}"
            + "]";

    @Test
    public void parsesReleasesAndPicksNewestInstallable() throws Exception {
        List<ReleaseInfo> list = UpdateChecker.parse(JSON);
        assertEquals(3, list.size());

        ReleaseInfo newest = ReleaseInfo.newest(list);
        assertNotNull(newest);
        assertEquals(2, newest.build);
        assertEquals("1.1", newest.versionName);
        assertEquals("Примечания", newest.body);
        assertEquals(2000L, newest.apkSize);
        assertTrue(newest.apkUrl.endsWith("MotionCalm-1.1-build2.apk"));
    }

    @Test
    public void nullBodyBecomesEmptyAndNonApkIsNotInstallable() throws Exception {
        List<ReleaseInfo> list = UpdateChecker.parse(JSON);
        assertEquals("", list.get(1).body);
        assertFalse(list.get(1).isInstallable());
        assertTrue(list.get(2).draft);
    }
}
