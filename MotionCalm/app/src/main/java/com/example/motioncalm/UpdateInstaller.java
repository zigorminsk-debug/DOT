package com.example.motioncalm;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Загрузка APK новой сборки с GitHub и установка через системный PackageInstaller.
 * Вызывать только из фонового потока.
 *
 * Android сам проверяет подпись: обновление установится, только если APK подписан
 * тем же постоянным ключом, что и установленная версия.
 */
public final class UpdateInstaller {

    /** Получает процент загрузки (0..100). */
    public interface ProgressListener {
        void onProgress(int percent);
    }

    private UpdateInstaller() {
    }

    /** Номер сборки установленного приложения (versionCode). */
    public static long installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return versionCodeOf(info);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Скачивает APK релиза в кэш приложения и проверяет, что это новее установленной сборки. */
    public static File download(Context context, ReleaseInfo release, ProgressListener listener)
            throws IOException {
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Не могу создать папку для обновления");
        }
        File file = new File(dir, "update.apk");
        if (file.exists() && !file.delete()) {
            throw new IOException("Не могу заменить старый файл обновления");
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "MotionCalm-Android");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub ответил кодом " + code + " при загрузке APK");
            }
            long total = release.apkSize;
            long done = 0;
            int lastPercent = -1;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    int percent = (int) Math.min(100, done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(percent);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        if (file.length() != release.apkSize) {
            file.delete();
            throw new IOException("Файл загружен не полностью");
        }
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (info == null || !context.getPackageName().equals(info.packageName)) {
            file.delete();
            throw new IOException("Скачанный файл не является обновлением этого приложения");
        }
        if (versionCodeOf(info) <= installedVersionCode(context)) {
            file.delete();
            throw new IOException("Эта сборка не новее установленной");
        }
        return file;
    }

    /**
     * Передаёт APK системному установщику. Дальше пользователь подтверждает установку
     * в системном окне; результат приходит в InstallResultReceiver.
     */
    public static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }

            Intent statusIntent = new Intent(context, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_STATUS);
            // MUTABLE нужен, потому что установщик добавляет в Intent свои extra (статус).
            PendingIntent statusPending = PendingIntent.getBroadcast(context, sessionId, statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(statusPending.getIntentSender());
            committed = true;
            // Данные уже записаны в сессию установщика, временный файл больше не нужен.
            apk.delete();
        } finally {
            if (!committed) {
                session.abandon();
            }
            session.close();
        }
    }

    static long versionCodeOf(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }
}
