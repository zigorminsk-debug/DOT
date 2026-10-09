package com.example.motioncalm;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Плитка в шторке быстрых настроек: включает и выключает подсказки движения.
 * Если включить нельзя (нет разрешения «поверх окон» или датчика), открывает приложение,
 * где видно, чего не хватает.
 */
public class CueTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        if (MotionCueService.running) {
            stopService(new Intent(this, MotionCueService.class));
        } else if (!Settings.canDrawOverlays(this) || !MotionCueService.hasMotionSensor(this)) {
            openApp();
            return;
        } else {
            try {
                Intent start = new Intent(this, MotionCueService.class)
                        .setAction(MotionCueService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(start);
                } else {
                    startService(start);
                }
            } catch (RuntimeException e) {
                // Android не разрешил запуск из фона: открываем приложение, там запуск всегда доступен.
                openApp();
                return;
            }
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = MotionCueService.running;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(on ? R.string.tile_on : R.string.tile_off));
        }
        tile.updateTile();
    }

    /** Открывает приложение. Старый вызов с Intent на Android 14+ запрещён, поэтому он только для старых версий. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openApp() {
        Intent app = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // На Android 14+ запуск активности из плитки разрешён только через PendingIntent.
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, app,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(app);
        }
    }
}
