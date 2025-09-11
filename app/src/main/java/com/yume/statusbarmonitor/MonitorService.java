package com.yume.statusbarmonitor;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.google.android.material.color.DynamicColors;

public class MonitorService extends Service {

    private static final String CHANNEL_ID = "BatteryMonitorChannel";
    private NotificationManager notificationManager;
    private Handler handler;
    private Runnable updateTask;
    private Bundle settings;
    private int updateInterval;
    private Typeface customTypeface;

    @Override
    public void onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 根据 intent 是否为空，判断服务是新启动还是被系统重启
        if (intent != null && intent.getExtras() != null) {
            // 从 MainActivity 启动
            settings = intent.getExtras();
        } else {
            // 服务被系统重启
            Log.d("MonitorService", "Service restarted by system, loading settings from SharedPreferences.");
            settings = loadSettingsFromSharedPreferences();
        }

        updateInterval = settings.getInt("interval", 3000);
        loadCustomFont(settings.getInt(Constants.KEY_FONT_CHOICE, 0));

        Notification initialNotification = createNotification(settings, "...", "...");
        startForeground(1, initialNotification);

        handler = new Handler(Looper.getMainLooper());
        updateTask = new Runnable() {
            @Override
            public void run() {
                Intent batteryIntent = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int temperature = 0;
                int voltage = 0;
                int percent = 0;
                if (batteryIntent != null) {
                    temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
                    voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                    percent = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                }

                long currentNow = getBatteryCurrentNow();
                String memoryUsageMB = getMemoryUsageMB();
                int memoryUsagePercent = getMemoryUsagePercent();

                String content1 = getDataValue(settings.getString(Constants.KEY_DATA1), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);
                String content2 = getDataValue(settings.getString(Constants.KEY_DATA2), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);
                String fullContent1 = getFullDataValue(settings.getString(Constants.KEY_DATA1), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);
                String fullContent2 = getFullDataValue(settings.getString(Constants.KEY_DATA2), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);

                String finalContent = content1;
                if (!"none".equals(settings.getString(Constants.KEY_DATA2))) {
                    finalContent += "\t" + content2;
                }
                String finalFullContent = fullContent1;
                if (!"none".equals(settings.getString(Constants.KEY_DATA2))) {
                    finalFullContent += "\t" + fullContent2;
                }

                Notification updatedNotification = createNotification(settings, finalContent, finalFullContent);
                notificationManager.notify(1, updatedNotification);

                handler.postDelayed(this, updateInterval);
            }
        };

        handler.post(updateTask);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && updateTask != null) {
            handler.removeCallbacks(updateTask);
        }
        notificationManager.cancel(1);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Bundle loadSettingsFromSharedPreferences() {
        SharedPreferences sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        Bundle loadedSettings = new Bundle();

        // 从 SharedPreferences 加载数据
        int data1Id = sharedPrefs.getInt(Constants.KEY_DATA1, R.id.rb_mem_p1);
        int data2Id = sharedPrefs.getInt(Constants.KEY_DATA2, R.id.rb_watt2);

        loadedSettings.putString(Constants.KEY_DATA1, getDataKey(data1Id));
        loadedSettings.putString(Constants.KEY_DATA2, getDataKey(data2Id));

        try {
            loadedSettings.putInt(Constants.KEY_FONT_SIZE, Integer.parseInt(sharedPrefs.getString(Constants.KEY_FONT_SIZE, "32")));
            loadedSettings.putInt(Constants.KEY_OFFSET, Integer.parseInt(sharedPrefs.getString(Constants.KEY_OFFSET, "15")));
            loadedSettings.putInt(Constants.KEY_BITMAP_SIZE, Integer.parseInt(sharedPrefs.getString(Constants.KEY_BITMAP_SIZE, "64")));
            loadedSettings.putInt(Constants.KEY_PADDING_X, Integer.parseInt(sharedPrefs.getString(Constants.KEY_PADDING_X, "-2")));
        } catch (NumberFormatException e) {
            Log.e("MonitorService", "Error parsing number from SharedPreferences, using defaults.", e);
            loadedSettings.putInt(Constants.KEY_FONT_SIZE, 32);
            loadedSettings.putInt(Constants.KEY_OFFSET, 15);
            loadedSettings.putInt(Constants.KEY_BITMAP_SIZE, 64);
            loadedSettings.putInt(Constants.KEY_PADDING_X, -2);
        }

        loadedSettings.putInt(Constants.KEY_REFRESH_RATE_POS, sharedPrefs.getInt(Constants.KEY_REFRESH_RATE_POS, 2));
        loadedSettings.putInt(Constants.KEY_FONT_CHOICE, sharedPrefs.getInt(Constants.KEY_FONT_CHOICE, 0));

        // 根据刷新位置计算间隔
        int refreshRatePos = loadedSettings.getInt(Constants.KEY_REFRESH_RATE_POS);
        int interval;
        if (refreshRatePos == 0) { // 1s
            interval = 1000;
        } else if (refreshRatePos == 1) { // 2s
            interval = 2000;
        } else if (refreshRatePos == 2) { // 3s
            interval = 3000;
        } else if (refreshRatePos == 3) { // 4s
            interval = 4000;
        } else if (refreshRatePos == 4) { // 5s
            interval = 5000;
        } else {
            interval = 3000; // 默认
        }
        loadedSettings.putInt("interval", interval);

        return loadedSettings;
    }

    private void loadCustomFont(int fontChoicePosition) {
        String fontFile = Constants.FONT_FILENAMES[fontChoicePosition];
        if (fontFile != null) {
            try {
                customTypeface = Typeface.createFromAsset(getAssets(), fontFile);
            } catch (Exception e) {
                Log.e("MonitorService", "Can't load font: " + fontFile, e);
                customTypeface = Typeface.DEFAULT_BOLD;
            }
        } else {
            customTypeface = Typeface.DEFAULT_BOLD;
        }
    }

    private Notification createNotification(Bundle settings, String content, String fullContent) {
        int bitmapSize = settings.getInt(Constants.KEY_BITMAP_SIZE);
        Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);

        String[] lines = content.split("\t");
        int fontSize = settings.getInt(Constants.KEY_FONT_SIZE);
        int offset = settings.getInt(Constants.KEY_OFFSET);
        int paddingX = settings.getInt(Constants.KEY_PADDING_X);

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(fontSize);
        paint.setTypeface(customTypeface);
        paint.setTextAlign(Paint.Align.LEFT);

        float centerY = (bitmap.getHeight() / 2f) - (paint.descent() + paint.ascent()) / 2;
        if (lines.length > 0) {
            canvas.drawText(lines[0], paddingX, centerY - offset, paint);
        }
        if (lines.length > 1) {
            canvas.drawText(lines[1], paddingX, centerY + offset, paint);
        }

        IconCompat icon = IconCompat.createWithBitmap(bitmap);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(fullContent.replace("\t", ", "))
                .setSmallIcon(icon)
                .build();
    }

    // ... (保持 getDataValue, getFullDataValue, getBatteryCurrentNow, getMemoryUsageMB, getMemoryUsagePercent, createNotificationChannel 等方法不变)
    @SuppressLint("DefaultLocale")
    private String getDataValue(String key, int temp, long current, int voltage, int percent, String memMB, int memPercent) {
        switch (key) {
            case "temperature": return temp + "°";
            case "current": return (current / 1000) + "";
            case "voltage": return String.format("%.1fⱽ", (voltage / 1000f));
            case "percent": return percent + "%" ;
            case "memory_mb": return memMB;
            case "memory_percent": return "ᔿ"+memPercent ;
            case "watt":
                double watts = (current * voltage) / 1000000000.0;
                return String.format("%.1f", watts);
            default: return "";
        }
    }
    @SuppressLint("DefaultLocale")
    private String getFullDataValue(String key, int temp, long current, int voltage, int percent, String memMB, int memPercent) {
        switch (key) {
            case "temperature": return "Temperature:" + temp + "°C";
            case "current": return "Current:" + (current / 1000) + "mA";
            case "voltage": return "Voltage:" + (voltage / 1000f) + "V";
            case "percent": return "Battery:" + percent + "%";
            case "memory_mb": return "Memory:" + memMB;
            case "memory_percent": return "MemoryUsage:" + memPercent + "%";
            case "watt":
                double watts = (current * voltage) / 1000000000.0;
                return "Watts:"+String.format("%.3fW", watts);
            default: return "";
        }
    }

    private String getDataKey(int radioButtonId) {
        if (radioButtonId == R.id.rb_temp1 || radioButtonId == R.id.rb_temp2) return "temperature";
        if (radioButtonId == R.id.rb_current1 || radioButtonId == R.id.rb_current2) return "current";
        if (radioButtonId == R.id.rb_voltage1 || radioButtonId == R.id.rb_voltage2) return "voltage";
        if (radioButtonId == R.id.rb_watt1 || radioButtonId == R.id.rb_watt2) return "watt";
        if (radioButtonId == R.id.rb_percent1 || radioButtonId == R.id.rb_percent2) return "percent";
        if (radioButtonId == R.id.rb_mem_p1 || radioButtonId == R.id.rb_mem_p2) return "memory_percent";
        if (radioButtonId == R.id.rb_none1 || radioButtonId == R.id.rb_none2) return "none";
        return "none";
    }

    private long getBatteryCurrentNow() {
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager != null) {
            return batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }
        return 0;
    }

    private String getMemoryUsageMB() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long totalMemory = memoryInfo.totalMem / (1024 * 1024);
        long availableMemory = memoryInfo.availMem / (1024 * 1024);
        long usedMemory = totalMemory - availableMemory;
        return usedMemory + "M/" + totalMemory + "M";
    }

    private int getMemoryUsagePercent() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long totalMemory = memoryInfo.totalMem;
        long availableMemory = memoryInfo.availMem;
        long usedMemory = totalMemory - availableMemory;
        return (int) (usedMemory * 100 / totalMemory);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "通知", NotificationManager.IMPORTANCE_LOW);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setVibrationPattern(new long[]{0});
        notificationManager.createNotificationChannel(channel);
    }
}