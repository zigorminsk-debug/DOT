package com.example.motioncalm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.Choreographer;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Фоновый сервис: показывает DotsView поверх всех приложений
 * и обновляет смещение точек по данным датчиков.
 *
 * Датчики и цикл кадров работают только при включённом экране. Когда экран выключают,
 * они останавливаются. Когда экран включают или телефон разблокируют, запускаются снова.
 * Пока экран включён, сторож раз в секунду проверяет датчики, кадры и окно и чинит их,
 * если они замолчали.
 *
 * Ускорение берём из сырого акселерометра, а силу тяжести отделяем через GravityTracker.
 * Так разгон и торможение не «уходят» в наклон, как это бывает у системного линейного датчика.
 */
public class MotionCueService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.example.motioncalm.START";
    public static final String ACTION_STOP = "com.example.motioncalm.STOP";
    public static final String ACTION_TEST = "com.example.motioncalm.TEST";
    public static final String EXTRA_INTENSITY = "intensity";
    public static final String EXTRA_DOTS = "dots";
    public static final String EXTRA_AMP = "amp";
    public static final String EXTRA_THICKNESS = "thickness";

    static final String PREFS = "motioncalm";
    static final String KEY_INTENSITY = "intensity";
    static final String KEY_DOTS = "dots";
    static final String KEY_AMP = "amp";
    static final String KEY_THICKNESS = "thickness";
    /** Толщина точек по умолчанию, dp (диаметр). */
    static final int DEFAULT_THICKNESS = 8;
    /** Амплитуда движения по умолчанию, проценты. */
    static final int DEFAULT_AMP = 50;
    /** Версия схемы настроек. 2 — с сборки 7: амплитуда по умолчанию 50%, а не 100%. */
    static final int SETTINGS_VERSION = 2;
    static final String KEY_SETTINGS_VERSION = "settings_version";

    private static final String TAG = "MotionCue";
    private static final String CHANNEL_ID = "motioncalm";
    private static final int NOTIF_ID = 1;
    private static final float SMOOTH_ALPHA = 0.25f;  // сглаживание ускорения
    private static final long SENSOR_STALE_NS = 300_000_000L; // 0.3 с без данных -> точки возвращаются
    private static final long TEST_DURATION_MS = 8000;
    private static final long WATCHDOG_PERIOD_MS = 1000;
    private static final long HEARTBEAT_PERIOD_NS = 5_000_000_000L;

    /** true, пока сервис работает. Читают главный экран и плитка. */
    public static volatile boolean running = false;

    // Диагностика: показывается на главном экране и пишется в журнал.
    public static volatile int debugEvents = 0;
    public static volatile float debugUx = 0f;
    public static volatile float debugUy = 0f;
    /** Ускорение автомобиля вперёд (+) и назад (-), м/с². */
    public static volatile float debugForward = 0f;
    /** Ускорение автомобиля вправо (+) и влево (-), м/с². */
    public static volatile float debugLateral = 0f;
    /** Ускорение автомобиля в координатах телефона, м/с². */
    public static volatile float debugAx = 0f;
    public static volatile float debugAy = 0f;
    public static volatile float debugAz = 0f;
    public static volatile boolean debugOverlayAdded = false;
    public static volatile boolean debugScreenOn = true;
    public static volatile boolean debugSensorOn = false;
    public static volatile long debugSensorAtNs = 0;
    public static volatile long debugFrameAtNs = 0;
    public static volatile int debugFrames = 0;
    public static volatile int debugSensorRestarts = 0;
    public static volatile int debugFrameRestarts = 0;

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private SensorManager sensorManager;
    private PowerManager powerManager;
    private DotsView dotsView;

    /** Сырой акселерометр: основной датчик. Без него движения нет. */
    private Sensor accelSensor;
    /** Датчик гравитации: нужен для начальной установки опоры и для сверки после перестановки телефона. */
    private Sensor gravitySensor;

    private final GravityTracker gravityTracker = new GravityTracker();
    private final float[] fusedGravity = new float[3];
    private boolean fusedGravityValid = false;
    /** Сглаженное ускорение автомобиля в координатах телефона, м/с². */
    private final float[] smooth = new float[3];
    private long lastAccelNs = 0;

    private int dotSizeDp = DEFAULT_THICKNESS;

    // Цель ускорения для физики точек. Датчик только обновляет цель,
    // а пересчёт делается на каждом кадре экрана (см. frameCallback).
    private volatile float targetUx = 0f;
    private volatile float targetUy = 0f;
    private volatile long lastSensorNs = 0;
    private long lastFrameNs = 0;

    private boolean screenOn = true;
    private boolean receiverRegistered = false;
    private boolean sensorSubscribed = false;
    private boolean frameLoopScheduled = false;
    private long lastHeartbeatNs = 0;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameLoopScheduled = false;
            if (dotsView == null) {
                return;
            }
            long nowNs = System.nanoTime();
            float dt = lastFrameNs == 0 ? 0.016f : (frameTimeNanos - lastFrameNs) * 1e-9f;
            lastFrameNs = frameTimeNanos;
            debugFrameAtNs = nowNs;
            debugFrames++;

            float ux = targetUx;
            float uy = targetUy;
            boolean stale = nowNs - lastSensorNs > SENSOR_STALE_NS;
            if (stale && !testing) {
                ux = 0f;
                uy = 0f;
            }

            dotsView.step(ux, uy, dt);
            debugUx = dotsView.getOffsetXdp();
            debugUy = dotsView.getOffsetYdp();
            startFrameLoop();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean testing = false;
    private long testStart = 0;

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

    /** Выключение экрана, включение экрана и разблокировка. */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                onScreenOn();
            }
        }
    };

    /** Сторож: пока экран включён, раз в секунду проверяет датчики, кадры и окно. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running || !screenOn) {
                return;
            }
            checkHealth();
            handler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

        screenOn = powerManager.isInteractive();
        debugScreenOn = screenOn;
        registerScreenReceiver();
        running = true;
        Log.i(TAG, "служба создана, экран " + (screenOn ? "включён" : "выключен"));
        requestTileRefresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Сразу переводим сервис в foreground: после startForegroundService() Android ждёт startForeground().
        startAsForeground();

        boolean test = intent != null && ACTION_TEST.equals(intent.getAction());
        // Для обычной работы нужен датчик; тест движения обходится без него.
        if (!Settings.canDrawOverlays(this) || (accelSensor == null && !test)) {
            stopCue();
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateSettings(prefs);
        int intensity;
        if (intent != null && intent.hasExtra(EXTRA_INTENSITY)) {
            intensity = intent.getIntExtra(EXTRA_INTENSITY, 50);
            prefs.edit().putInt(KEY_INTENSITY, intensity).apply();
        } else {
            intensity = prefs.getInt(KEY_INTENSITY, 50);
        }

        if (dotsView == null && !addOverlay()) {
            stopCue();
            return START_NOT_STICKY;
        }
        if (screenOn) {
            resumeCue();
            scheduleWatchdog();
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
            amp = intent.getIntExtra(EXTRA_AMP, DEFAULT_AMP);
            prefs.edit().putInt(KEY_AMP, amp).apply();
        } else {
            amp = prefs.getInt(KEY_AMP, DEFAULT_AMP);
        }
        dotsView.setAmplitude(0.3f + 1.7f * amp / 100f);
        dotsView.setIntensity(0.1f + 0.9f * intensity / 100f);

        int thickness;
        if (intent != null && intent.hasExtra(EXTRA_THICKNESS)) {
            thickness = intent.getIntExtra(EXTRA_THICKNESS, DEFAULT_THICKNESS);
            prefs.edit().putInt(KEY_THICKNESS, thickness).apply();
        } else {
            thickness = prefs.getInt(KEY_THICKNESS, DEFAULT_THICKNESS);
        }
        dotSizeDp = thickness;
        dotsView.setDotSize(dotSizeDp);

        if (test && !testing) {
            testing = true;
            testStart = SystemClock.uptimeMillis();
            handler.post(testTick);
        }

        return START_STICKY;
    }

    /**
     * Одноразовая замена настроек, сохранённых старыми сборками. Пока в файле настроек нет версии 2,
     * амплитуда (в том числе сохранённые 100%) становится DEFAULT_AMP. Выбор пользователя после этого не трогаем.
     */
    static void migrateSettings(SharedPreferences prefs) {
        if (prefs.getInt(KEY_SETTINGS_VERSION, 1) < SETTINGS_VERSION) {
            prefs.edit()
                    .putInt(KEY_AMP, DEFAULT_AMP)
                    .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
                    .apply();
        }
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
            Log.w(TAG, "окно не добавилось: " + e);
            return false;
        }
        dotsView = view;
        overlayParams = lp;
        debugOverlayAdded = true;
        lastFrameNs = 0;
        return true;
    }

    /** Возвращает окно, если система его убрала. false — окно вернуть не удалось. */
    private boolean ensureOverlayAttached() {
        if (dotsView == null || dotsView.isAttachedToWindow()) {
            return true;
        }
        try {
            windowManager.addView(dotsView, overlayParams);
            debugOverlayAdded = true;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "окно не вернулось: " + e);
            return false;
        }
    }

    /** Датчики и цикл кадров при включённом экране. Безопасно вызывать многократно. */
    private void resumeCue() {
        if (dotsView == null) {
            return;
        }
        subscribeSensor();
        startFrameLoop();
    }

    private void onScreenOff() {
        screenOn = false;
        debugScreenOn = false;
        Log.i(TAG, "экран выключен: останавливаю датчики, кадры и сторож");
        handler.removeCallbacks(watchdog);
        unsubscribeSensor();
        stopFrameLoop();
    }

    private void onScreenOn() {
        screenOn = true;
        debugScreenOn = true;
        if (dotsView == null) {
            return;   // окно ещё не показано: при старте всё запустится само
        }
        Log.i(TAG, "экран включён: возобновляю датчики и кадры");
        // Точки начинают с покоя, а не с последнего значения до выключения экрана.
        targetUx = 0f;
        targetUy = 0f;
        lastFrameNs = 0;
        if (!ensureOverlayAttached()) {
            stopCue();
            return;
        }
        resumeCue();
        scheduleWatchdog();
    }

    /**
     * Подписка на датчики: акселерометр и (если есть) датчик гравитации.
     * Опору силы тяжести сбрасываем: после паузы телефон могли переставить.
     */
    private void subscribeSensor() {
        if (sensorSubscribed || accelSensor == null || dotsView == null) {
            return;
        }
        sensorSubscribed = sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        if (sensorSubscribed) {
            if (gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME);
            }
            gravityTracker.invalidate();
            fusedGravityValid = false;
            lastAccelNs = 0;
        }
        debugSensorOn = sensorSubscribed;
        lastSensorNs = System.nanoTime();   // отсчёт тишины начинается заново
        if (!sensorSubscribed) {
            Log.w(TAG, "датчик не подключился");
        }
    }

    private void unsubscribeSensor() {
        if (!sensorSubscribed) {
            return;
        }
        sensorManager.unregisterListener(this);   // снимает все датчики, на которые подписаны
        sensorSubscribed = false;
        debugSensorOn = false;
    }

    private void startFrameLoop() {
        if (frameLoopScheduled || dotsView == null) {
            return;
        }
        frameLoopScheduled = true;
        debugFrameAtNs = System.nanoTime();   // отсчёт тишины кадров начинается заново
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopFrameLoop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameLoopScheduled = false;
    }

    private void scheduleWatchdog() {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_PERIOD_MS);
    }

    /** Одна проверка сторожа. Вызывается раз в секунду, пока экран включён. */
    private void checkHealth() {
        long nowNs = System.nanoTime();

        if (dotsView != null && !dotsView.isAttachedToWindow()) {
            Log.w(TAG, "окно с точками пропало, возвращаю");
            if (!ensureOverlayAttached()) {
                stopCue();
                return;
            }
        }

        if (CueHealth.sensorNeedsSubscribe(screenOn, testing, accelSensor != null, sensorSubscribed,
                nowNs, lastSensorNs)) {
            if (sensorSubscribed) {
                Log.w(TAG, "датчик молчит, переподписываюсь");
                debugSensorRestarts++;
                unsubscribeSensor();
            }
            subscribeSensor();
        }

        if (CueHealth.frameLoopNeedsStart(screenOn, dotsView != null, frameLoopScheduled,
                nowNs, debugFrameAtNs)) {
            Log.w(TAG, "кадры остановились, перезапускаю");
            debugFrameRestarts++;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameLoopScheduled = false;
            startFrameLoop();
        }

        // Строка раз в секунду: по ней видно, как реагируют точки на разгон, торможение и поворот
        Log.i(TAG, String.format(Locale.US, "motion fwd=%+.2f lat=%+.2f ax=%+.2f ay=%+.2f az=%+.2f",
                debugForward, debugLateral, debugAx, debugAy, debugAz));

        if (nowNs - lastHeartbeatNs >= HEARTBEAT_PERIOD_NS) {
            lastHeartbeatNs = nowNs;
            Log.i(TAG, "heartbeat events=" + debugEvents
                    + " frames=" + debugFrames
                    + " screen=" + (screenOn ? "on" : "off")
                    + " sensor=" + (sensorSubscribed ? "on" : "off")
                    + " sensorRestarts=" + debugSensorRestarts
                    + " frameRestarts=" + debugFrameRestarts);
        }
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void stopCue() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void requestTileRefresh() {
        TileService.requestListeningState(this, new ComponentName(this, CueTileService.class));
    }

    /** Есть ли на телефоне акселерометр, по которому измеряется движение. */
    static boolean hasMotionSensor(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
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
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GRAVITY) {
            System.arraycopy(event.values, 0, fusedGravity, 0, 3);
            fusedGravityValid = true;
            return;
        }
        if (type == Sensor.TYPE_ACCELEROMETER) {
            processAccelerometer(event.values, event.timestamp);
        }
    }

    /** Показания акселерометра → ускорение автомобиля → движение точек. */
    private void processAccelerometer(float[] f, long timestampNs) {
        if (testing) {
            return;   // во время теста точки движутся по сценарию, датчик не мешает
        }
        if (!gravityTracker.isReady()) {
            gravityTracker.reset(fusedGravityValid ? fusedGravity : f);
        }
        float dtS = lastAccelNs == 0
                ? 0.02f
                : Math.min(0.1f, Math.max(0.001f, (timestampNs - lastAccelNs) * 1e-9f));
        lastAccelNs = timestampNs;

        float[] a = gravityTracker.update(f, dtS, fusedGravityValid ? fusedGravity : null);
        smooth[0] += SMOOTH_ALPHA * (a[0] - smooth[0]);
        smooth[1] += SMOOTH_ALPHA * (a[1] - smooth[1]);
        smooth[2] += SMOOTH_ALPHA * (a[2] - smooth[2]);

        int rotation = windowManager.getDefaultDisplay().getRotation();
        float[] r = CueMotion.screenAccel(smooth, gravityTracker.reference(), rotation);

        debugEvents++;
        targetUx = r[0];
        targetUy = r[1];
        debugForward = r[2];
        debugLateral = r[3];
        debugAx = smooth[0];
        debugAy = smooth[1];
        debugAz = smooth[2];
        long nowNs = System.nanoTime();
        lastSensorNs = nowNs;
        debugSensorAtNs = nowNs;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    @Override
    public void onDestroy() {
        running = false;
        testing = false;
        handler.removeCallbacks(testTick);
        handler.removeCallbacks(watchdog);
        unsubscribeSensor();
        stopFrameLoop();
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
                // приёмник уже снят
            }
            receiverRegistered = false;
        }
        if (dotsView != null) {
            try {
                windowManager.removeView(dotsView);
            } catch (IllegalArgumentException ignored) {
                // окно уже удалено
            }
            dotsView = null;
        }
        debugOverlayAdded = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        requestTileRefresh();
        Log.i(TAG, "служба остановлена");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
