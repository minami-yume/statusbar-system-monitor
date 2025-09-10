package com.yume.statusbarmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;

import android.app.ActivityManager;
import android.content.IntentFilter;

import com.google.android.material.color.DynamicColors;

public class MonitorService extends Service {

    private static final String CHANNEL_ID = "BatteryMonitorChannel";
    private static final long UPDATE_INTERVAL = 3000;

    private NotificationManager notificationManager;
    private Handler handler;
    private Runnable updateTask;
    // 存储用户设置
    private Bundle settings;

    @Override
    public void onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 从 Intent 中获取用户设置
        settings = new Bundle();
        settings.putString("data1", intent.getStringExtra("data1"));
        settings.putString("data2", intent.getStringExtra("data2"));
        settings.putInt("size", intent.getIntExtra("size", 50));
        settings.putInt("offset", intent.getIntExtra("offset", 25));
        settings.putInt("bitmapSize", intent.getIntExtra("bitmapSize", 100));

        // 创建初始通知并启动前台服务
        Notification initialNotification = createNotification(settings, "...","");
        startForeground(1, initialNotification);

        // 初始化 Handler 和 Runnable
        handler = new Handler(Looper.getMainLooper());
        updateTask = new Runnable() {
            @Override
            public void run() {
                // 在这里获取所有数据
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

                // 根据设置生成内容
                String content1 = getDataValue(settings.getString("data1"), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);
                String content2 = getDataValue(settings.getString("data2"), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);
                String fullContent1 = getFullDataValue(settings.getString("data1"), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);
                String fullContent2 = getFullDataValue(settings.getString("data2"), temperature, currentNow, voltage, percent, memoryUsageMB, memoryUsagePercent);

                String finalContent = content1;
                if (!"none".equals(settings.getString("data2"))) {
                    finalContent += "\t" + content2;
                }
                String finalFullContent = fullContent1;
                if (!"none".equals(settings.getString("data2"))) {
                    finalFullContent += "\t" + fullContent2;
                }

                // 更新通知
                Notification updatedNotification = createNotification(settings, finalContent,finalFullContent);
                notificationManager.notify(1, updatedNotification);

                // 再次发布任务
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        };

        // 首次发布任务
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

    // 创建通知的方法， now uses Bundle for settings
    private Notification createNotification(Bundle settings, String content,String fullContent) {
        int bitmapSize = settings.getInt("bitmapSize");
        Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);

        String[] lines = content.split("\t");
        int fontSize = settings.getInt("size");
        int offset = settings.getInt("offset");

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(fontSize);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);

        if (lines.length > 0) {
            canvas.drawText(lines[0], bitmap.getWidth() / 2f, (bitmap.getHeight() / 2f) - (paint.descent() + paint.ascent()) / 2 - offset, paint);
        }
        if (lines.length > 1) {
            canvas.drawText(lines[1], bitmap.getWidth() / 2f, (bitmap.getHeight() / 2f) - (paint.descent() + paint.ascent()) / 2 + offset, paint);
        }

        IconCompat icon = IconCompat.createWithBitmap(bitmap);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
//                .setContentTitle("系统状态")
                .setContentTitle(fullContent.replace("\n", ", "))
//                .setContentText(fullContent.replace("\n", ", "))
                .setSmallIcon(icon)
                .build();
    }

    // 修改 getDataValue 方法，增加对新内存百分比的支持
    private String getDataValue(String key, int temp, long current, int voltage, int percent, String memMB, int memPercent) {
        switch (key) {
            case "temperature": return temp + "°";
            case "current": return (current / 1000) + "";
            case "voltage": return (voltage / 1000f) + "";
            case "percent": return percent + "%M";
            case "memory_mb": return memMB;
            case "memory_percent": return memPercent + "%B";

            default: return "";
        }
    }
    private String getFullDataValue(String key, int temp, long current, int voltage, int percent, String memMB, int memPercent) {
        switch (key) {
            case "temperature": return "Temperature:" + temp + "°C";

            case "current": return "Current:" + (current / 1000) + "mA";

            case "voltage": return "Voltage:" + (voltage / 1000f) + "V";

            case "percent": return "Battery:" + percent + "%";

            case "memory_mb": return "Memory:" + memMB;

            case "memory_percent": return "MemoryUsage:" + memPercent + "%";

            default: return "";
        }
    }

    // 获取电流的方法
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
    // 增加一个获取内存使用百分比的方法
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
