package com.example.motioncalm;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReleaseInfoTest {

    private static final String BASE = "https://github.com/zigorminsk-debug/DOT/releases/download/";

    private static ReleaseInfo release(String tag, boolean draft, String apkName, long size) {
        return new ReleaseInfo(tag, draft, false, "", BASE + tag + "/" + apkName, size);
    }

    @Test
    public void parsesVersionNameAndBuildFromTag() {
        ReleaseInfo r = release("v1.1-build2", false, "MotionCalm-1.1-build2.apk", 1000);
        assertEquals("1.1", r.versionName);
        assertEquals(2, r.build);
        assertTrue(r.isInstallable());
    }

    @Test
    public void picksHighestInstallableBuild() {
        List<ReleaseInfo> list = Arrays.asList(
                release("v1.0-build1", false, "a.apk", 1000),
                release("v1.1-build3", false, "b.apk", 1000),
                release("v1.1-build2", false, "c.apk", 1000));
        assertEquals(3, ReleaseInfo.newest(list).build);
    }

    @Test
    public void ignoresDraftsAndReleasesWithoutApk() {
        List<ReleaseInfo> list = Arrays.asList(
                release("v1.2-build9", true, "draft.apk", 1000),
                new ReleaseInfo("v1.2-build8", false, false, "", "", 0),
                release("v1.1-build2", false, "ok.apk", 1000));
        assertEquals(2, ReleaseInfo.newest(list).build);
    }

    @Test
    public void ignoresUnknownTagsAndForeignUrls() {
        List<ReleaseInfo> list = Arrays.asList(
                release("latest", false, "x.apk", 1000),
                new ReleaseInfo("v9.0-build9", false, false, "", "https://example.com/evil.apk", 1000));
        assertNull(ReleaseInfo.newest(list));
    }

    @Test
    public void returnsNullForEmptyList() {
        assertNull(ReleaseInfo.newest(Collections.<ReleaseInfo>emptyList()));
    }
}
