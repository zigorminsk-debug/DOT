package com.example.motioncalm;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    /** Сколько символов примечаний к релизу показывать в статусе обновления. */
    private static final int MAX_NOTES_CHARS = 800;

    private Switch switchCue;
    private SeekBar seekIntensity;
    private RadioGroup rgDots;
    private Button btnTest;
    private SeekBar seekAmp;
    private TextView tvAmp;
    private TextView tvIntensity;
    private TextView tvStatus;
    private TextView tvSensor;
    private TextView tvVersion;
    private Button btnCheckUpdate;
    private TextView tvUpdateStatus;
    private ProgressBar progressUpdate;
    private Button btnInstallUpdate;

    private SensorManager sensorManager;
    private Sensor sensor;

    private SharedPreferences prefs;
    private boolean updatingUi = false;

    /** Сборка, найденная последней проверкой. null — устанавливать пока нечего. */
    private ReleaseInfo pendingRelease;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(MotionCueService.PREFS, MODE_PRIVATE);

        switchCue = findViewById(R.id.switchCue);
        seekIntensity = findViewById(R.id.seekIntensity);
        rgDots = findViewById(R.id.rgDots);
        btnTest = findViewById(R.id.btnTest);
        seekAmp = findViewById(R.id.seekAmp);
        tvAmp = findViewById(R.id.tvAmp);
        tvIntensity = findViewById(R.id.tvIntensity);
        tvStatus = findViewById(R.id.tvStatus);
        tvSensor = findViewById(R.id.tvSensor);
        tvVersion = findViewById(R.id.tvVersion);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus);
        progressUpdate = findViewById(R.id.progressUpdate);
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate);

        tvVersion.setText(getString(R.string.version_format,
                installedVersionName(), UpdateInstaller.installedVersionCode(this)));

        int amp = prefs.getInt(MotionCueService.KEY_AMP, 50);
        seekAmp.setProgress(amp);
        tvAmp.setText(getString(R.string.amp_label, amp));
        seekAmp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvAmp.setText(getString(R.string.amp_label, progress));
                if (fromUser) {
                    prefs.edit().putInt(MotionCueService.KEY_AMP, progress).apply();
                    if (MotionCueService.running) {
                        startCue();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    requestOverlayPermission();
                    return;
                }
                // Проверка отрисовки работает и без датчика: движение имитируется.
                Intent intent = new Intent(MainActivity.this, MotionCueService.class)
                        .setAction(MotionCueService.ACTION_TEST)
                        .putExtra(MotionCueService.EXTRA_INTENSITY, seekIntensity.getProgress())
                        .putExtra(MotionCueService.EXTRA_DOTS, currentDotLevel())
                        .putExtra(MotionCueService.EXTRA_AMP, seekAmp.getProgress());
                sendToService(intent);
                setSwitchSilently(true);
            }
        });

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (sensor == null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (sensor == null) {
            tvSensor.setText(R.string.sensor_missing);
        }

        int dotLevel = prefs.getInt(MotionCueService.KEY_DOTS, 1);
        rgDots.check(dotLevel == 0 ? R.id.rbFew : dotLevel == 2 ? R.id.rbMany : R.id.rbMedium);
        rgDots.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                prefs.edit().putInt(MotionCueService.KEY_DOTS, currentDotLevel()).apply();
                if (MotionCueService.running) {
                    startCue();
                }
            }
        });

        int intensity = prefs.getInt(MotionCueService.KEY_INTENSITY, 50);
        seekIntensity.setProgress(intensity);
        updateIntensityLabel(intensity);

        switchCue.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingUi) {
                    return;
                }
                if (isChecked) {
                    if (!Settings.canDrawOverlays(MainActivity.this)) {
                        setSwitchSilently(false);
                        requestOverlayPermission();
                        return;
                    }
                    if (sensor == null) {
                        setSwitchSilently(false);
                        updateStatus();
                        return;
                    }
                    startCue();
                } else {
                    stopCue();
                }
                updateStatus();
            }
        });

        seekIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateIntensityLabel(progress);
                if (fromUser && MotionCueService.running) {
                    startCue();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(MotionCueService.KEY_INTENSITY, seekBar.getProgress()).apply();
            }
        });

        btnCheckUpdate.setOnClickListener(v -> checkForUpdates());
        btnInstallUpdate.setOnClickListener(v -> installUpdate());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setSwitchSilently(MotionCueService.running);
        updateStatus();
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        tvSensor.setText(String.format(Locale.US,
                "Датчик: X %.2f  Y %.2f  Z %.2f м/с²\nВеличина: %.2f м/с²\n"
                        + "Сервис: работает %s, точки на экране %s\n"
                        + "Событий от датчика в сервисе: %d\n"
                        + "Сдвиг точек: X %.2f  Y %.2f",
                x, y, z, mag,
                MotionCueService.running ? "да" : "нет",
                MotionCueService.debugOverlayAdded ? "добавлены" : "не добавлены",
                MotionCueService.debugEvents,
                MotionCueService.debugUx, MotionCueService.debugUy));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    private void startCue() {
        Intent intent = new Intent(this, MotionCueService.class)
                .setAction(MotionCueService.ACTION_START)
                .putExtra(MotionCueService.EXTRA_INTENSITY, seekIntensity.getProgress())
                .putExtra(MotionCueService.EXTRA_DOTS, currentDotLevel())
                .putExtra(MotionCueService.EXTRA_AMP, seekAmp.getProgress());
        sendToService(intent);
        setSwitchSilently(true);
    }

    /**
     * Сервис уже запущен в foreground, поэтому новые настройки передаём обычным startService.
     * startForegroundService нужен только для первого запуска.
     */
    private void sendToService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !MotionCueService.running) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private int currentDotLevel() {
        int id = rgDots.getCheckedRadioButtonId();
        if (id == R.id.rbFew) {
            return 0;
        }
        if (id == R.id.rbMany) {
            return 2;
        }
        return 1;
    }

    private void stopCue() {
        stopService(new Intent(this, MotionCueService.class));
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void setSwitchSilently(boolean checked) {
        updatingUi = true;
        switchCue.setChecked(checked);
        updatingUi = false;
    }

    private void updateIntensityLabel(int value) {
        tvIntensity.setText(getString(R.string.intensity_label, value));
    }

    private void updateStatus() {
        if (!Settings.canDrawOverlays(this)) {
            tvStatus.setText(R.string.status_need_permission);
        } else if (sensor == null) {
            tvStatus.setText(R.string.status_no_sensor);
        } else if (MotionCueService.running) {
            tvStatus.setText(R.string.status_running);
        } else {
            tvStatus.setText(R.string.status_ready);
        }
    }

    private String installedVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ---- Обновления ----

    /** Проверка в фоне: запрос к GitHub не должен блокировать экран. */
    private void checkForUpdates() {
        btnCheckUpdate.setEnabled(false);
        btnInstallUpdate.setVisibility(View.GONE);
        pendingRelease = null;
        tvUpdateStatus.setText(R.string.update_checking);
        final long current = UpdateInstaller.installedVersionCode(this);
        new Thread(() -> {
            try {
                ReleaseInfo newest = ReleaseInfo.newest(UpdateChecker.fetchReleases());
                runOnUiThread(() -> onUpdateChecked(newest, current));
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> onUpdateCheckFailed(reason));
            }
        }, "update-check").start();
    }

    private void onUpdateChecked(ReleaseInfo newest, long current) {
        if (isFinishing()) {
            return;
        }
        btnCheckUpdate.setEnabled(true);
        if (newest == null) {
            tvUpdateStatus.setText(R.string.update_no_builds);
        } else if (newest.build > current) {
            pendingRelease = newest;
            String status = getString(R.string.update_available, newest.versionName, newest.build);
            String notes = newest.body.trim();
            if (!notes.isEmpty()) {
                if (notes.length() > MAX_NOTES_CHARS) {
                    notes = notes.substring(0, MAX_NOTES_CHARS) + "…";
                }
                status = status + "\n\n" + notes;
            }
            tvUpdateStatus.setText(status);
            btnInstallUpdate.setText(getString(R.string.update_install_button, newest.versionName, newest.build));
            btnInstallUpdate.setEnabled(true);
            btnInstallUpdate.setVisibility(View.VISIBLE);
        } else {
            tvUpdateStatus.setText(R.string.update_up_to_date);
        }
    }

    private void onUpdateCheckFailed(String reason) {
        if (isFinishing()) {
            return;
        }
        btnCheckUpdate.setEnabled(true);
        tvUpdateStatus.setText(getString(R.string.update_error, reason));
    }

    private void installUpdate() {
        final ReleaseInfo release = pendingRelease;
        if (release == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            // Пользователь должен разрешить установку из этого приложения.
            tvUpdateStatus.setText(R.string.update_need_unknown_sources);
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        btnInstallUpdate.setEnabled(false);
        btnCheckUpdate.setEnabled(false);
        progressUpdate.setProgress(0);
        progressUpdate.setVisibility(View.VISIBLE);
        tvUpdateStatus.setText(R.string.update_downloading);
        new Thread(() -> {
            try {
                File apk = UpdateInstaller.download(this, release,
                        percent -> runOnUiThread(() -> progressUpdate.setProgress(percent)));
                UpdateInstaller.install(this, apk);
                runOnUiThread(this::onInstallStarted);
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> onInstallFailed(reason));
            }
        }, "update-install").start();
    }

    private void onInstallStarted() {
        if (isFinishing()) {
            return;
        }
        progressUpdate.setVisibility(View.GONE);
        btnInstallUpdate.setEnabled(true);
        btnCheckUpdate.setEnabled(true);
        tvUpdateStatus.setText(R.string.update_confirm_in_system);
    }

    private void onInstallFailed(String reason) {
        if (isFinishing()) {
            return;
        }
        progressUpdate.setVisibility(View.GONE);
        btnInstallUpdate.setEnabled(true);
        btnCheckUpdate.setEnabled(true);
        tvUpdateStatus.setText(getString(R.string.update_failed, reason));
    }
}
