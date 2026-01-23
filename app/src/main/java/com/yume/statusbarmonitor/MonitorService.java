package com.yume.statusbarmonitor;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
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

    // 网速计算相关
    private long lastTime = 0;
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long downloadSpeed = 0;
    private long uploadSpeed = 0;

    // 配置 Key
    private String key1 = "";
    private String key2 = "";
    private String keyRing = "";
    private float dashPhase = 0;

    // [优化] 内存复用对象
    private Bitmap mIconBitmap;
    private Canvas mIconCanvas;
    private Paint mStrokePaint;
    private Paint mTextPaint;
    private RectF mRect;
    private Path mPath;
    private ActivityManager.MemoryInfo mMemoryInfo;

    // [优化] 屏幕状态控制
    private boolean isScreenOn = true;
    private ScreenStateReceiver screenReceiver;

    @Override
    public void onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        // 1. 初始化复用对象
        initReusableObjects();

        // 2. 注册屏幕广播
        registerScreenReceiver();
    }

    private void initReusableObjects() {
        mStrokePaint = new Paint();
        mStrokePaint.setAntiAlias(true);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeCap(Paint.Cap.BUTT);

        mTextPaint = new Paint();
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextAlign(Paint.Align.LEFT);

        mRect = new RectF();
        mPath = new Path();
        mMemoryInfo = new ActivityManager.MemoryInfo();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. 解析配置
        if (intent != null && intent.getExtras() != null) {
            settings = intent.getExtras();
        } else {
            settings = loadSettingsFromSharedPreferences();
        }

        // 2. 应用配置
        updateInterval = settings.getInt("interval", 3000);
        loadCustomFont(settings.getInt(Constants.KEY_FONT_CHOICE, 0));
        key1 = settings.getString(Constants.KEY_DATA1, "none");
        key2 = settings.getString(Constants.KEY_DATA2, "none");
        keyRing = settings.getString("ring_key", "none");

        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }

        // 3. [防闪烁] 立即同步执行一次逻辑
        // 重置网速计算的基础时间，防止暂停很久后算出巨大的网速
        lastTime = 0;
        Notification firstNotification = performUpdateLogic();
        startForeground(1, firstNotification);

        // 4. 重置任务
        if (updateTask != null) {
            handler.removeCallbacks(updateTask);
        }

        updateTask = new Runnable() {
            @Override
            public void run() {
                // [省电] 息屏停止更新
                if (!isScreenOn) return;

                Notification notification = performUpdateLogic();
                notificationManager.notify(1, notification);

                handler.postDelayed(this, updateInterval);
            }
        };

        // 延迟执行下一次循环
        handler.postDelayed(updateTask, updateInterval);

        return START_STICKY;
    }

    /**
     * 核心逻辑封装：获取数据(含网速) -> 绘图 -> 生成通知
     */
    private Notification performUpdateLogic() {
        // A. 获取硬件数据
        Intent batteryIntent = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int temperature = 0;
        int voltage = 0;
        int battery_percent = 0;
        if (batteryIntent != null) {
            temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            battery_percent = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        }

        long currentNow = getBatteryCurrentNow();
        String memoryUsageMB = getMemoryUsageMB();
        int memoryUsagePercent = getMemoryUsagePercent();
        int wattsDivisor = settings.getInt(Constants.KEY_DIVISOR, 1000000000);
        int storageUsagePercent = getStorageUsagePercent();
        double storageUsageFree = getStorageUsage()[1]/1024.0/1024.0/1024.0;

        // B. 更新网速 (核心逻辑)
        updateNetworkSpeed();

        // C. 准备显示内容
        String content1 = getDataValue(key1, temperature, currentNow, voltage, battery_percent, memoryUsageMB, memoryUsagePercent, wattsDivisor, downloadSpeed, uploadSpeed, storageUsagePercent, storageUsageFree);
        String content2 = getDataValue(key2, temperature, currentNow, voltage, battery_percent, memoryUsageMB, memoryUsagePercent, wattsDivisor, downloadSpeed, uploadSpeed, storageUsagePercent, storageUsageFree);

        String iconContent = content1;
        if (!"none".equals(key2)) {
            iconContent += "\t" + content2;
        }

        String titleContent = "数: " + getLabelFromKey(key1);
        if (!"none".equals(key2)) {
            titleContent += " | " + getLabelFromKey(key2);
        }
        if (!"none".equals(keyRing)) {
            titleContent += " 环: " + getLabelFromKey(keyRing);
        }

        String allInfoContent = buildAllInfoString(temperature, currentNow, voltage, battery_percent, memoryUsageMB, memoryUsagePercent, wattsDivisor, downloadSpeed, uploadSpeed, storageUsagePercent, storageUsageFree);

        // D. 计算圆环进度
        int progressPercent = -1;
        switch (keyRing) {
            case "battery_percent": progressPercent = battery_percent; break;
            case "memory_percent": progressPercent = memoryUsagePercent; break;
            case "storage_percent": progressPercent = storageUsagePercent; break;
            case "temperature_64": progressPercent = temperature * 25 / 16; break;
            case "temperature_50": progressPercent = temperature * 2; break;
            case "temperature_40": progressPercent = temperature * 5 / 2; break;
            default: progressPercent = -1; break;
        }

        dashPhase -= -settings.getInt(Constants.KEY_DASH_LENGTH);
        if (dashPhase < -1000) dashPhase = 0;

        // E. 构建通知
        return createNotification(settings, iconContent, titleContent, allInfoContent, progressPercent);
    }

    // --- 优化的绘图与通知构建 ---

    private Notification createNotification(Bundle settings, String iconText, String titleText, String bigText, int progress) {
        int bitmapSize = settings.getInt(Constants.KEY_BITMAP_SIZE);

        // [优化] Bitmap 复用
        if (mIconBitmap == null || mIconBitmap.getWidth() != bitmapSize) {
            if (mIconBitmap != null) mIconBitmap.recycle();
            mIconBitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
            mIconCanvas = new Canvas(mIconBitmap);
            mRect.set(0, 0, bitmapSize, bitmapSize);
        }

        // [优化] 清空画布
        mIconBitmap.eraseColor(Color.TRANSPARENT);

        float strokeWidth = bitmapSize / 10f;
        mStrokePaint.setStrokeWidth(strokeWidth);

        boolean isDashed = settings.getBoolean(Constants.KEY_RING_DASHED, false);
        int dashLength = settings.getInt(Constants.KEY_DASH_LENGTH, 6);
        int dashGap = settings.getInt(Constants.KEY_DASH_GAP, 2);

        if (progress >= 0) {
            if (progress > 100) progress = 100;
            mStrokePaint.setColor(Color.WHITE);

            if (isDashed) {
                mStrokePaint.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{(float) dashLength, (float) dashGap}, dashPhase));
            } else {
                mStrokePaint.setPathEffect(null);
            }

            mPath.reset();
            mPath.addRect(mRect, Path.Direction.CW);

            PathMeasure measure = new PathMeasure(mPath, false);
            float length = measure.getLength();

            Path partialPath = new Path();
            measure.getSegment(0, length * (progress / 100f), partialPath, true);

            mIconCanvas.drawPath(partialPath, mStrokePaint);
        }

        String[] lines = iconText.split("\t");
        int fontSize = settings.getInt(Constants.KEY_FONT_SIZE);
        int offset = settings.getInt(Constants.KEY_OFFSET);
        int paddingX = settings.getInt(Constants.KEY_PADDING_X);
        int paddingY = settings.getInt(Constants.KEY_PADDING_Y);

        mTextPaint.setTextSize(fontSize);
        mTextPaint.setTypeface(customTypeface);

        float centerY = (mIconBitmap.getHeight() / 2f) - (mTextPaint.descent() + mTextPaint.ascent()) / 2;
        if (lines.length > 0) {
            mIconCanvas.drawText(lines[0], paddingX, centerY - offset + paddingY, mTextPaint);
        }
        if (lines.length > 1) {
            mIconCanvas.drawText(lines[1], paddingX, centerY + offset + paddingY, mTextPaint);
        }

        IconCompat icon = IconCompat.createWithBitmap(mIconBitmap);

        // [修复] 点击跳转 Intent
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentPendingIntent = null;

        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            contentPendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(titleText)
                .setContentText("展开查看详情")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent);
        }

        return builder.build();
    }

    // --- 屏幕广播与生命周期 ---

    private void registerScreenReceiver() {
        screenReceiver = new ScreenStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    private class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
                if (handler != null && updateTask != null) {
                    handler.removeCallbacks(updateTask);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
                // 亮屏时，重置网速时间基准，防止瞬间算出巨大数值
                lastTime = 0;
                if (handler != null && updateTask != null) {
                    handler.removeCallbacks(updateTask);
                    handler.post(updateTask);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && updateTask != null) {
            handler.removeCallbacks(updateTask);
        }
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception e) {}
        }
        if (mIconBitmap != null && !mIconBitmap.isRecycled()) {
            mIconBitmap.recycle();
        }
        notificationManager.cancel(1);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- 辅助方法: 网速与硬件数据 ---

    private void updateNetworkSpeed() {
        long now = System.currentTimeMillis();
        long rxBytes = TrafficStats.getTotalRxBytes();
        long txBytes = TrafficStats.getTotalTxBytes();

        // 第一次运行或亮屏瞬间，只记录不计算
        if (lastTime == 0) {
            lastRxBytes = rxBytes;
            lastTxBytes = txBytes;
            lastTime = now;
            downloadSpeed = 0;
            uploadSpeed = 0;
            return;
        }

        long timeDelta = now - lastTime;
        // 至少 500ms 间隔才计算，防止除以 0 或波动过大
        if (timeDelta > 500) {
            long rxBytesDelta = rxBytes - lastRxBytes;
            long txBytesDelta = txBytes - lastTxBytes;

            if (rxBytesDelta >= 0 && txBytesDelta >= 0) {
                downloadSpeed = (rxBytesDelta * 1000 / timeDelta);
                uploadSpeed = (txBytesDelta * 1000 / timeDelta);
            }

            lastRxBytes = rxBytes;
            lastTxBytes = txBytes;
            lastTime = now;
        }
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
        activityManager.getMemoryInfo(mMemoryInfo); // 复用对象
        long totalMemory = mMemoryInfo.totalMem / (1024 * 1024);
        long availableMemory = mMemoryInfo.availMem / (1024 * 1024);
        long usedMemory = totalMemory - availableMemory;
        return usedMemory + "M";
    }

    private int getMemoryUsagePercent() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mMemoryInfo); // 复用对象
        long totalMemory = mMemoryInfo.totalMem;
        long availableMemory = mMemoryInfo.availMem;
        long usedMemory = totalMemory - availableMemory;
        if (totalMemory == 0) return 0;
        return (int) (usedMemory * 100 / totalMemory);
    }

    private int getStorageUsagePercent() {
        long[] usage = getStorageUsage();
        long total = usage[0];
        long used = usage[2];
        if (total > 0) {
            return (int) ((used * 100) / total);
        } else {
            return 0;
        }
    }

    private long[] getStorageUsage() {
        long[] usage = new long[]{0L, 0L, 0L};
        try {
            java.io.File path = android.os.Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long total = totalBlocks * blockSize;
            // 简单估算真实容量
            double realTotal = total / (1024.0 * 1024.0 * 1024.0);
            int[] standardCapacities = {8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};
            for (int cap : standardCapacities) {
                if (realTotal <= cap) {
                    total = (long) cap * 1000 * 1000 * 1000;
                    break;
                }
            }
            long free = availableBlocks * blockSize;
            long used = total - free;
            usage[0] = total;
            usage[1] = free;
            usage[2] = used;
        } catch (Exception e) {
            Log.e("MonitorService", "getStorageUsage error", e);
        }
        return usage;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "状态监测服务", NotificationManager.IMPORTANCE_LOW);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    // --- 配置加载与格式化 ---

    private Bundle loadSettingsFromSharedPreferences() {
        SharedPreferences sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        Bundle loadedSettings = new Bundle();

        loadedSettings.putString(Constants.KEY_FONT_SIZE, sharedPrefs.getString(Constants.KEY_FONT_SIZE, "32"));
        loadedSettings.putString(Constants.KEY_OFFSET, sharedPrefs.getString(Constants.KEY_OFFSET, "15"));
        loadedSettings.putString(Constants.KEY_BITMAP_SIZE, sharedPrefs.getString(Constants.KEY_BITMAP_SIZE, "64"));
        loadedSettings.putString(Constants.KEY_PADDING_X, sharedPrefs.getString(Constants.KEY_PADDING_X, "-2"));
        loadedSettings.putString(Constants.KEY_PADDING_Y, sharedPrefs.getString(Constants.KEY_PADDING_Y, "0"));
        loadedSettings.putString(Constants.KEY_DIVISOR, sharedPrefs.getString(Constants.KEY_DIVISOR, "1000000000"));
        loadedSettings.putBoolean(Constants.KEY_RING_DASHED, sharedPrefs.getBoolean(Constants.KEY_RING_DASHED, false));
        loadedSettings.putString(Constants.KEY_DASH_LENGTH, sharedPrefs.getString(Constants.KEY_DASH_LENGTH,"6"));
        loadedSettings.putString(Constants.KEY_DASH_GAP, sharedPrefs.getString(Constants.KEY_DASH_GAP,"2"));

        int idx1 = sharedPrefs.getInt("pref_idx_data1_v2", 1);
        int idx2 = sharedPrefs.getInt("pref_idx_data2_v2", 0);
        int idxRing = sharedPrefs.getInt("pref_idx_ring_v2", 2);

        String[] dataValues = getResources().getStringArray(R.array.data_values);
        String[] ringValues = getResources().getStringArray(R.array.ring_values);

        String val1 = (idx1 >= 0 && idx1 < dataValues.length) ? dataValues[idx1] : "none";
        String val2 = (idx2 >= 0 && idx2 < dataValues.length) ? dataValues[idx2] : "none";
        String valRing = (idxRing >= 0 && idxRing < ringValues.length) ? ringValues[idxRing] : "none";

        loadedSettings.putString(Constants.KEY_DATA1, val1);
        loadedSettings.putString(Constants.KEY_DATA2, val2);
        loadedSettings.putString("ring_key", valRing);

        int refreshRatePos = sharedPrefs.getInt(Constants.KEY_REFRESH_RATE_POS, 2);
        loadedSettings.putInt(Constants.KEY_REFRESH_RATE_POS, refreshRatePos);
        loadedSettings.putInt(Constants.KEY_FONT_CHOICE, sharedPrefs.getInt(Constants.KEY_FONT_CHOICE, 0));

        int interval = 3000;
        if (refreshRatePos == 0) interval = 1000;
        else if (refreshRatePos == 1) interval = 2000;
        else if (refreshRatePos == 3) interval = 4000;
        else if (refreshRatePos == 4) interval = 5000;
        loadedSettings.putInt("interval", interval);

        try {
            loadedSettings.putInt(Constants.KEY_BITMAP_SIZE, Integer.parseInt(loadedSettings.getString(Constants.KEY_BITMAP_SIZE)));
            loadedSettings.putInt(Constants.KEY_FONT_SIZE, Integer.parseInt(loadedSettings.getString(Constants.KEY_FONT_SIZE)));
            loadedSettings.putInt(Constants.KEY_OFFSET, Integer.parseInt(loadedSettings.getString(Constants.KEY_OFFSET)));
            loadedSettings.putInt(Constants.KEY_PADDING_X, Integer.parseInt(loadedSettings.getString(Constants.KEY_PADDING_X)));
            loadedSettings.putInt(Constants.KEY_PADDING_Y, Integer.parseInt(loadedSettings.getString(Constants.KEY_PADDING_Y)));
            loadedSettings.putInt(Constants.KEY_DIVISOR, Integer.parseInt(loadedSettings.getString(Constants.KEY_DIVISOR)));
            loadedSettings.putInt(Constants.KEY_DASH_LENGTH, Integer.parseInt(loadedSettings.getString(Constants.KEY_DASH_LENGTH)));
            loadedSettings.putInt(Constants.KEY_DASH_GAP, Integer.parseInt(loadedSettings.getString(Constants.KEY_DASH_GAP)));
        } catch (Exception e) {
            // Default fallback
            loadedSettings.putInt(Constants.KEY_BITMAP_SIZE, 64);
            loadedSettings.putInt(Constants.KEY_FONT_SIZE, 32);
            loadedSettings.putInt(Constants.KEY_OFFSET, 15);
            loadedSettings.putInt(Constants.KEY_PADDING_X, -2);
            loadedSettings.putInt(Constants.KEY_PADDING_Y, 0);
            loadedSettings.putInt(Constants.KEY_DIVISOR, 1000000000);
            loadedSettings.putInt(Constants.KEY_DASH_LENGTH, 6);
            loadedSettings.putInt(Constants.KEY_DASH_GAP, 2);
        }

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

    private String getLabelFromKey(String key) {
        if ("none".equals(key) || key == null || key.isEmpty()) {
            return "无";
        }
        try {
            String[] values = getResources().getStringArray(R.array.data_values);
            String[] labels = getResources().getStringArray(R.array.data_labels);
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(key)) return labels[i];
            }
            values = getResources().getStringArray(R.array.ring_values);
            labels = getResources().getStringArray(R.array.ring_labels);
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(key)) return labels[i];
            }
        } catch (Exception e) {
            Log.e("MonitorService", "Error mapping key to label", e);
        }
        return key;
    }

    @SuppressLint("DefaultLocale")
    private String buildAllInfoString(int temp, long current, int voltage, int battery_percent, String memMB, int memPercent, int wDivisor, long downSpeed, long upSpeed, int storagePercent, double storageUsageFree) {
        double watts = (Math.abs(current) * voltage) / (double) wDivisor;
        StringBuilder sb = new StringBuilder();
        sb.append("电量: ").append(battery_percent).append("%  ");
        sb.append("温度: ").append(temp).append("°C\n");
        sb.append("功率: ").append(String.format("%.2fW", watts)).append("  ");
        sb.append("电压: ").append(String.format("%.2fV", voltage / 1000f)).append("  ");
        sb.append("电流: ").append(current).append("\n");
        sb.append("内存: ").append(memMB).append(" (").append(memPercent).append("%)\n");
        sb.append("存储: ").append(storagePercent).append("% 已用 ").append(String.format("%.2f",storageUsageFree)).append("GiB 空闲\n");
        sb.append("网速: ↓").append(formatSpeed(downSpeed)).append("  ↑").append(formatSpeed(upSpeed));
        return sb.toString();
    }

    @SuppressLint("DefaultLocale")
    private String getDataValue(String key, int temp, long current, int voltage, int battery_percent, String memMB, int memPercent, int wDivisor, long downSpeed, long upSpeed, int storagePercent, double storageUsageFree) {
        switch (key) {
            case "temperature": return temp + "°";
            case "current": return Math.abs(current) + "";
            case "voltage": return String.format("%.1f", (voltage / 1000f));
            case "battery_percent": return battery_percent + "";
            case "memory_mb": return memMB;
            case "memory_percent": return memPercent + "";
            case "storage_percent": return storagePercent + "";
            case "watt":
                double watts = (Math.abs(current) * voltage) / (double) wDivisor;
                return String.format("%.1f", watts);
            case "download_speed": return formatSpeedShort(downSpeed) + "";
            case "upload_speed": return formatSpeedShort(upSpeed) + "";
            case "storage_free": return String.format("%.1f",storageUsageFree);
            default: return "";
        }
    }

    @SuppressLint("DefaultLocale")
    private String formatSpeedShort(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.1fM", bytesPerSec / 1024f / 1024f);
        } else {
            return (bytesPerSec / 1024) + "K";
        }
    }

    @SuppressLint("DefaultLocale")
    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.2f MB/s", bytesPerSec / 1024f / 1024f);
        } else {
            return (bytesPerSec / 1024) + " KB/s";
        }
    }
}