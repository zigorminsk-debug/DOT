package com.example.motioncalm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.Choreographer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Фоновый сервис: показывает DotsView поверх всех приложений
 * и обновляет смещение точек по данным акселерометра.
 */
public class MotionCueService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.example.motioncalm.START";
    public static final String ACTION_STOP = "com.example.motioncalm.STOP";
    public static final String ACTION_TEST = "com.example.motioncalm.TEST";
    public static final String EXTRA_INTENSITY = "intensity";
    public static final String EXTRA_DOTS = "dots";
    public static final String EXTRA_AMP = "amp";

    static final String PREFS = "motioncalm";
    static final String KEY_INTENSITY = "intensity";
    static final String KEY_DOTS = "dots";
    static final String KEY_AMP = "amp";

    /** true, пока сервис работает — читается из MainActivity. */
    public static volatile boolean running = false;

    /** Диагностика: сколько событий датчика получил сервис и какой сдвиг передал точкам. */
    public static volatile int debugEvents = 0;
    public static volatile float debugUx = 0f;
    public static volatile float debugUy = 0f;
    public static volatile boolean debugOverlayAdded = false;

    private static final String CHANNEL_ID = "motioncalm";
    private static final int NOTIF_ID = 1;
    private static final float GRAVITY_ALPHA = 0.9f;  // для фолбэка без linear-sensor
    private static final float SMOOTH_ALPHA = 0.25f;  // сглаживание ускорения

    private WindowManager windowManager;
    private SensorManager sensorManager;
    private Sensor sensor;
    private boolean hasLinearSensor;
    private DotsView dotsView;

    private float gx, gy;   // оценка силы тяжести (фолбэк)
    private float fx, fy;   // сглаженное ускорение в координатах устройства
    private long lastTimestamp = 0;

    // Цель ускорения для физики точек. Датчик только обновляет цель,
    // а пересчёт делается на каждом кадре экрана (см. frameCallback).
    private volatile float targetUx = 0f;
    private volatile float targetUy = 0f;
    private volatile long lastSensorNs = 0;
    private long lastFrameNs = 0;
    private static final long SENSOR_STALE_NS = 300_000_000L; // 0.3 с без данных -> точки возвращаются

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (dotsView == null) {
                return;
            }
            float dt = lastFrameNs == 0 ? 0.016f : (frameTimeNanos - lastFrameNs) * 1e-9f;
            lastFrameNs = frameTimeNanos;

            float ux = targetUx;
            float uy = targetUy;
            boolean stale = System.nanoTime() - lastSensorNs > SENSOR_STALE_NS;
            if (stale && !testing) {
                ux = 0f;
                uy = 0f;
            }

            dotsView.step(ux, uy, dt);
            debugUx = dotsView.getOffsetXdp();
            debugUy = dotsView.getOffsetYdp();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean testing = false;
    private long testStart = 0;
    private static final long TEST_DURATION_MS = 8000;

    /** Имитация поездки без датчиков: разгон, ход, торможение. Проверяет только отрисовку. */
    private final Runnable testTick = new Runnable() {
        @Override
        public void run() {
            long t = SystemClock.uptimeMillis() - testStart;
            if (dotsView == null || t > TEST_DURATION_MS) {
                testing = false;
                return;
            }
            float a;                       // м/с², вперёд по ходу автомобиля
            if (t < 2000) a = 2f;
            else if (t < 5000) a = 0f;
            else if (t < 7000) a = -3f;
            else a = 0f;
            targetUx = 0f;
            targetUy = -a; // вперёд = вверх по экрану (uy < 0)
            lastSensorNs = System.nanoTime();
            handler.postDelayed(this, 16);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        hasLinearSensor = sensor != null;
        if (!hasLinearSensor) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Сразу переводим сервис в foreground. После startForegroundService() Android ждёт
        // вызова startForeground(), даже если сервис тут же остановится по ошибке.
        startAsForeground();

        boolean test = intent != null && ACTION_TEST.equals(intent.getAction());
        // Для обычной работы нужен датчик; тест движения обходится без него.
        if (!Settings.canDrawOverlays(this) || (sensor == null && !test)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int intensity;
        if (intent != null && intent.hasExtra(EXTRA_INTENSITY)) {
            intensity = intent.getIntExtra(EXTRA_INTENSITY, 50);
            prefs.edit().putInt(KEY_INTENSITY, intensity).apply();
        } else {
            intensity = prefs.getInt(KEY_INTENSITY, 50);
        }

        if (dotsView == null) {
            if (!addOverlay()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            }
        }
        int dots;
        if (intent != null && intent.hasExtra(EXTRA_DOTS)) {
            dots = intent.getIntExtra(EXTRA_DOTS, 1);
            prefs.edit().putInt(KEY_DOTS, dots).apply();
        } else {
            dots = prefs.getInt(KEY_DOTS, 1);
        }
        dotsView.setDotLevel(dots);

        int amp;
        if (intent != null && intent.hasExtra(EXTRA_AMP)) {
            amp = intent.getIntExtra(EXTRA_AMP, 50);
            prefs.edit().putInt(KEY_AMP, amp).apply();
        } else {
            amp = prefs.getInt(KEY_AMP, 50);
        }
        dotsView.setAmplitude(0.3f + 1.7f * amp / 100f);
        dotsView.setIntensity(0.1f + 0.9f * intensity / 100f);

        if (test && !testing) {
            testing = true;
            testStart = SystemClock.uptimeMillis();
            handler.post(testTick);
        }

        return START_STICKY;
    }

    /** Добавляет окно с точками. false — если система не позволила его добавить. */
    private boolean addOverlay() {
        DotsView view = new DotsView(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // касания проходят к приложениям
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("MotionCalm");

        try {
            windowManager.addView(view, lp);
        } catch (RuntimeException e) {
            // Например, разрешение «поверх других окон» отозвали прямо перед запуском.
            return false;
        }
        dotsView = view;
        debugOverlayAdded = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
        return true;
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(this, MotionCueService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .addAction(0, getString(R.string.notif_stop), stopPi);

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (testing) {
            return;
        }
        float x = event.values[0];
        float y = event.values[1];

        if (!hasLinearSensor) {
            // Фолбэк: убираем силу тяжести низкочастотным фильтром
            gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * x;
            gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * y;
            x -= gx;
            y -= gy;
        }

        fx += SMOOTH_ALPHA * (x - fx);
        fy += SMOOTH_ALPHA * (y - fy);

        // Переводим оси устройства в оси экрана с учётом поворота
        int rotation = windowManager.getDefaultDisplay().getRotation();
        float ux, uy;
        switch (rotation) {
            case Surface.ROTATION_90:
                ux = -fy;
                uy = -fx;
                break;
            case Surface.ROTATION_180:
                ux = -fx;
                uy = fy;
                break;
            case Surface.ROTATION_270:
                ux = fy;
                uy = fx;
                break;
            case Surface.ROTATION_0:
            default:
                ux = fx;
                uy = -fy;
                break;
        }

        debugEvents++;

        targetUx = ux;
        targetUy = uy;
        lastSensorNs = System.nanoTime();
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    @Override
    public void onDestroy() {
        running = false;
        debugOverlayAdded = false;
        testing = false;
        handler.removeCallbacks(testTick);
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (dotsView != null) {
            try {
                windowManager.removeView(dotsView);
            } catch (IllegalArgumentException ignored) {
                // окно уже удалено
            }
            dotsView = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
