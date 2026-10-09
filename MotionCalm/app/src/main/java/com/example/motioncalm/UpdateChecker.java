package com.example.motioncalm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Получает список релизов репозитория с GitHub.
 * Сетевые вызовы делать только в фоновом потоке.
 */
public final class UpdateChecker {

    private static final String API_URL =
            "https://api.github.com/repos/" + ReleaseInfo.REPO + "/releases?per_page=30";

    private UpdateChecker() {
    }

    /** Загружает релизы и разбирает их в список ReleaseInfo. */
    public static List<ReleaseInfo> fetchReleases() throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "MotionCalm-Android");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub ответил кодом " + code);
            }
            return parse(readAll(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    /** Разбирает JSON-ответ /releases. Для каждого релиза берётся первый .apk из assets. */
    static List<ReleaseInfo> parse(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<ReleaseInfo> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject rel = array.getJSONObject(i);
            String tag = rel.optString("tag_name", "");
            boolean draft = rel.optBoolean("draft", false);
            boolean prerelease = rel.optBoolean("prerelease", false);
            String body = rel.isNull("body") ? "" : rel.optString("body", "");

            String apkUrl = "";
            long apkSize = 0;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    if (asset.optString("name", "").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "");
                        apkSize = asset.optLong("size", 0);
                        break;
                    }
                }
            }
            result.add(new ReleaseInfo(tag, draft, prerelease, body, apkUrl, apkSize));
        }
        return result;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
