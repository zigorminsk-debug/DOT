package com.example.motioncalm;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Один релиз GitHub с APK-файлом. Тег релиза имеет вид v1.1-build2:
 * первая часть — версия (versionName), число после "build" — номер сборки (versionCode).
 *
 * Класс не зависит от Android, поэтому его логика покрыта обычными JVM-тестами.
 */
public final class ReleaseInfo {

    /** Репозиторий, из которого приложение берёт обновления. */
    static final String REPO = "zigorminsk-debug/DOT";

    /** Файлы разрешено скачивать только из релизов этого репозитория. */
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";

    private static final Pattern TAG = Pattern.compile("^v(\\d+(?:\\.\\d+)*)-build(\\d+)$");

    public final String tag;
    public final boolean draft;
    public final boolean prerelease;
    /** Описание релиза (примечания к сборке). */
    public final String body;
    public final String apkUrl;
    public final long apkSize;
    /** Версия из тега, например "1.1". Пусто, если тег не распознан. */
    public final String versionName;
    /** Номер сборки из тега, например 2. -1, если тег не распознан. */
    public final int build;

    public ReleaseInfo(String tag, boolean draft, boolean prerelease, String body, String apkUrl, long apkSize) {
        this.tag = tag == null ? "" : tag;
        this.draft = draft;
        this.prerelease = prerelease;
        this.body = body == null ? "" : body;
        this.apkUrl = apkUrl == null ? "" : apkUrl;
        this.apkSize = apkSize;

        Matcher m = TAG.matcher(this.tag);
        if (m.matches()) {
            this.versionName = m.group(1);
            this.build = parseBuild(m.group(2));
        } else {
            this.versionName = "";
            this.build = -1;
        }
    }

    /** Можно ли предложить этот релиз к установке. */
    public boolean isInstallable() {
        return !draft
                && build > 0
                && apkUrl.startsWith(DOWNLOAD_PREFIX)
                && apkUrl.endsWith(".apk")
                && apkSize > 0;
    }

    /** Самая новая установимая сборка из списка или null, если таких нет. */
    public static ReleaseInfo newest(List<ReleaseInfo> releases) {
        ReleaseInfo best = null;
        for (ReleaseInfo r : releases) {
            if (r.isInstallable() && (best == null || r.build > best.build)) {
                best = r;
            }
        }
        return best;
    }

    private static int parseBuild(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
