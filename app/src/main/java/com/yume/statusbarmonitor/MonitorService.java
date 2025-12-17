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
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
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

    // 存储当前配置的Key
    private String key1 = "";
    private String key2 = "";
    private String keyRing = "";

    @Override
    public void onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getExtras() != null) {
            settings = intent.getExtras();
        } else {
            // 系统重启服务时
            settings = loadSettingsFromSharedPreferences();
        }

        updateInterval = settings.getInt("interval", 3000);
        loadCustomFont(settings.getInt(Constants.KEY_FONT_CHOICE, 0));

        // 提取 key，默认为空
        key1 = settings.getString(Constants.KEY_DATA1, "none");
        key2 = settings.getString(Constants.KEY_DATA2, "none");
        keyRing = settings.getString("ring_key", "none");

        // 启动前台服务 (占位)
        Notification initialNotification = createNotification(settings, "...", "Loading...", "...", 0);
        startForeground(1, initialNotification);

        handler = new Handler(Looper.getMainLooper());
        updateTask = new Runnable() {
            @Override
            public void run() {
                // 1. 获取所有原始数据
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

                // 修正：存储计算放到主循环，注意异常捕获已在方法内
                int storageUsagePercent = getStorageUsagePercent();
                double storageUsageFree = getStorageUsage()[1]/1024.0/1024.0/1024.0;

                updateNetworkSpeed();

                // 2. 准备显示内容
                // 图标上的文字（Short）
                String content1 = getDataValue(key1, temperature, currentNow, voltage, battery_percent, memoryUsageMB, memoryUsagePercent, wattsDivisor, downloadSpeed, uploadSpeed, storageUsagePercent,storageUsageFree);
                String content2 = getDataValue(key2, temperature, currentNow, voltage, battery_percent, memoryUsageMB, memoryUsagePercent, wattsDivisor, downloadSpeed, uploadSpeed, storageUsagePercent,storageUsageFree);

                // 拼接图标文字
                String iconContent = content1;
                if (!"none".equals(key2)) {
                    iconContent += "\t" + content2; // \t 用于绘图时分割行
                }

                // 标题文字（Notification Title）
                String label1 = getLabelFromKey(key1);
                String label2 = getLabelFromKey(key2);

                String titleContent = "数: " + label1;
                if (!"none".equals(key2)) {
                    titleContent += " | " + label2;
                }
                if (!"none".equals(keyRing)) {
                    String ringLabel = getLabelFromKey(keyRing);
                    titleContent += " 环: " + ringLabel;
                }

                // 全部信息文字（Notification Expanded Body）
                String allInfoContent = buildAllInfoString(temperature, currentNow, voltage, battery_percent, memoryUsageMB, memoryUsagePercent, wattsDivisor, downloadSpeed, uploadSpeed, storageUsagePercent,storageUsageFree);

                // 3. 计算圆环进度
                int progressPercent = -1; // -1 代表不画
                switch (keyRing) {
                    case "battery_percent":
                        progressPercent = battery_percent;
                        break;
                    case "memory_percent":
                        progressPercent = memoryUsagePercent;
                        break;
                    case "storage_percent":
                        progressPercent = storageUsagePercent;
                        break;
                    default:
                        progressPercent = -1;
                        break;
                }

                // 4. 更新通知
                Notification updatedNotification = createNotification(settings, iconContent, titleContent, allInfoContent, progressPercent);
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

    // --- 数据获取与处理 ---

    // 构建所有信息的字符串，用于下拉显示
    @SuppressLint("DefaultLocale")
    private String buildAllInfoString(int temp, long current, int voltage, int battery_percent, String memMB, int memPercent, int wDivisor, long downSpeed, long upSpeed, int storagePercent, double storageUsageFree) {
        double watts = (Math.abs(current) * voltage) / (double) wDivisor;

        StringBuilder sb = new StringBuilder();
        sb.append("电量: ").append(battery_percent).append("%  ");
        sb.append("温度: ").append(temp).append("°C\n");

        sb.append("功率: ").append(String.format("%.2fW", watts)).append("  ");
        sb.append("电压: ").append(String.format("%.2fV", voltage / 1000f)).append("  ");
        sb.append("电流: ").append(current).append("mA\n");

        sb.append("内存: ").append(memMB).append(" (").append(memPercent).append("%)\n");
        sb.append("存储: ").append(storagePercent).append("% 已用 ").append(String.format("%.2f",storageUsageFree)).append("GiB 空闲\n");

        sb.append("网速: ↓").append(formatSpeed(downSpeed)).append("  ↑").append(formatSpeed(upSpeed));

        return sb.toString();
    }

    // 格式化单个数据，用于图标绘制
    @SuppressLint("DefaultLocale")
    private String getDataValue(String key, int temp, long current, int voltage, int battery_percent, String memMB, int memPercent, int wDivisor, long downSpeed, long upSpeed, int storagePercent, double storageUsageFree) {
        switch (key) {
            case "temperature": return temp + "°";
            case "current": return Math.abs(current) + ""; // 图标上通常不显示负号以节省空间
            case "voltage": return String.format("%.1f", (voltage / 1000f));
            case "battery_percent": return battery_percent + "%";
            case "memory_mb": return memMB;
            case "memory_percent": return memPercent + "%";
            case "storage_percent": return storagePercent + "%";
            case "watt":
                double watts = (Math.abs(current) * voltage) / (double) wDivisor;
                return String.format("%.1f", watts);
            case "download_speed": return formatSpeedShort(downSpeed) + "↓";
            case "upload_speed": return formatSpeedShort(upSpeed) + "↑";
            case "storage_free": return String.format("%.1f",storageUsageFree);
            default: return "";
        }
    }

    // 简短的网速格式化 (用于图标)
    @SuppressLint("DefaultLocale")
    private String formatSpeedShort(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.1fM", bytesPerSec / 1024f / 1024f);
        } else {
            return (bytesPerSec / 1024) + "K";
        }
    }

    // 完整的网速格式化 (用于文本)
    @SuppressLint("DefaultLocale")
    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.2f MB/s", bytesPerSec / 1024f / 1024f);
        } else {
            return (bytesPerSec / 1024) + " KB/s";
        }
    }

    // --- 绘图逻辑 ---

    private Notification createNotification(Bundle settings, String iconText, String titleText, String bigText, int progress) {
        int bitmapSize = settings.getInt(Constants.KEY_BITMAP_SIZE);

        // 创建 Bitmap
        Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);

        // 绘制方形圆环
        Paint strokePaint = new Paint();
        strokePaint.setAntiAlias(true);
        strokePaint.setStyle(Paint.Style.STROKE);

        float strokeWidth = bitmapSize / 10f;
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setStrokeCap(Paint.Cap.SQUARE);

        // 稍微内缩一点防止边缘被切
        // float inset = strokeWidth / 2;
        // android.graphics.RectF rect = new RectF(inset, inset, bitmapSize - inset, bitmapSize - inset);
        // 如果想要贴边，可以用 0，但可能会有锯齿
        android.graphics.RectF rect = new RectF(0, 0, bitmapSize, bitmapSize);

        if (progress >= 0) {
            if (progress > 100) progress = 100;
            strokePaint.setColor(Color.WHITE); // 纯白，交给系统变色

            android.graphics.Path path = new Path();
            path.addRect(rect, Path.Direction.CW);

            PathMeasure measure = new PathMeasure(path, false);
            float length = measure.getLength();

            Path partialPath = new Path();
            measure.getSegment(0, length * (progress / 100f), partialPath, true);

            canvas.drawPath(partialPath, strokePaint);
        }

        // 绘制文字
        String[] lines = iconText.split("\t");
        int fontSize = settings.getInt(Constants.KEY_FONT_SIZE);
        int offset = settings.getInt(Constants.KEY_OFFSET);
        int paddingX = settings.getInt(Constants.KEY_PADDING_X);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(fontSize);
        textPaint.setTypeface(customTypeface);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float centerY = (bitmap.getHeight() / 2f) - (textPaint.descent() + textPaint.ascent()) / 2;
        if (lines.length > 0) {
            canvas.drawText(lines[0], paddingX, centerY - offset, textPaint);
        }
        if (lines.length > 1) {
            canvas.drawText(lines[1], paddingX, centerY + offset, textPaint);
        }

        IconCompat icon = IconCompat.createWithBitmap(bitmap);

        // 构建 Notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(titleText)  // 显示选中的两行数据
                .setContentText("展开查看详情") // 收起时的提示
                // 使用 BigTextStyle 显示所有信息
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        return builder.build();
    }

    // --- 硬件数据获取 ---

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
        return usedMemory + "M"; // 简化显示
    }

    private int getMemoryUsagePercent() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long totalMemory = memoryInfo.totalMem;
        long availableMemory = memoryInfo.availMem;
        long usedMemory = totalMemory - availableMemory;
        // 避免除以0
        if (totalMemory == 0) return 0;
        return (int) (usedMemory * 100 / totalMemory);
    }

    private int getStorageUsagePercent() {
        long[] usage = getStorageUsage();
        long total = usage[0];
        long used = usage[2];

        if (total > 0) {
            return (int) ((used * 100) / total); // 修正：先乘100防止整数除法为0
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

            double realTotal = total / (1024.0 * 1024.0 * 1024.0);

            // 常见手机存储档位 (单位: GiB)
            int[] standardCapacities = {8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};


            for (int cap : standardCapacities) {
                // 当读取容量比标准档位略小时假定真实容量为该档位
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

    private void updateNetworkSpeed() {
        long now = System.currentTimeMillis();
        long rxBytes = android.net.TrafficStats.getTotalRxBytes();
        long txBytes = android.net.TrafficStats.getTotalTxBytes();

        if (lastTime != 0) {
            long timeDelta = now - lastTime;
            if (timeDelta > 0) {
                long rxBytesDelta = rxBytes - lastRxBytes;
                long txBytesDelta = txBytes - lastTxBytes;
                // 计算每秒字节数
                downloadSpeed = (rxBytesDelta * 1000 / timeDelta);
                uploadSpeed = (txBytesDelta * 1000 / timeDelta);
            }
        }
        lastRxBytes = rxBytes;
        lastTxBytes = txBytes;
        lastTime = now;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "状态监测服务", NotificationManager.IMPORTANCE_LOW);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    // --- 配置加载 (Service重启时用) ---

    private Bundle loadSettingsFromSharedPreferences() {
        SharedPreferences sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        Bundle loadedSettings = new Bundle();

        // 加载字符串配置
        loadedSettings.putString(Constants.KEY_FONT_SIZE, sharedPrefs.getString(Constants.KEY_FONT_SIZE, "32"));
        loadedSettings.putString(Constants.KEY_OFFSET, sharedPrefs.getString(Constants.KEY_OFFSET, "15"));
        loadedSettings.putString(Constants.KEY_BITMAP_SIZE, sharedPrefs.getString(Constants.KEY_BITMAP_SIZE, "64"));
        loadedSettings.putString(Constants.KEY_PADDING_X, sharedPrefs.getString(Constants.KEY_PADDING_X, "-2"));
        loadedSettings.putString(Constants.KEY_DIVISOR, sharedPrefs.getString(Constants.KEY_DIVISOR, "1000000000"));

        // 加载 Spinner 索引并转换为 Key
        int idx1 = sharedPrefs.getInt("pref_idx_data1_v2", 1);
        int idx2 = sharedPrefs.getInt("pref_idx_data2_v2", 0);
        int idxRing = sharedPrefs.getInt("pref_idx_ring_v2", 2);

        String[] dataValues = getResources().getStringArray(R.array.data_values);
        String[] ringValues = getResources().getStringArray(R.array.ring_values);

        // 安全获取数组元素
        String val1 = (idx1 >= 0 && idx1 < dataValues.length) ? dataValues[idx1] : "none";
        String val2 = (idx2 >= 0 && idx2 < dataValues.length) ? dataValues[idx2] : "none";
        String valRing = (idxRing >= 0 && idxRing < ringValues.length) ? ringValues[idxRing] : "none";

        loadedSettings.putString(Constants.KEY_DATA1, val1);
        loadedSettings.putString(Constants.KEY_DATA2, val2);
        loadedSettings.putString("ring_key", valRing);

        // 刷新率计算
        int refreshRatePos = sharedPrefs.getInt(Constants.KEY_REFRESH_RATE_POS, 2);
        loadedSettings.putInt(Constants.KEY_REFRESH_RATE_POS, refreshRatePos);
        loadedSettings.putInt(Constants.KEY_FONT_CHOICE, sharedPrefs.getInt(Constants.KEY_FONT_CHOICE, 0));

        // 简单映射刷新率
        int interval = 3000;
        if (refreshRatePos == 0) interval = 1000;
        else if (refreshRatePos == 1) interval = 2000;
        else if (refreshRatePos == 3) interval = 4000;
        else if (refreshRatePos == 4) interval = 5000;
        loadedSettings.putInt("interval", interval);

        // 由于 createNotification 需要 int 类型，这里做个转换放入 bundle
        try {
            loadedSettings.putInt(Constants.KEY_BITMAP_SIZE, Integer.parseInt(loadedSettings.getString(Constants.KEY_BITMAP_SIZE)));
            loadedSettings.putInt(Constants.KEY_FONT_SIZE, Integer.parseInt(loadedSettings.getString(Constants.KEY_FONT_SIZE)));
            loadedSettings.putInt(Constants.KEY_OFFSET, Integer.parseInt(loadedSettings.getString(Constants.KEY_OFFSET)));
            loadedSettings.putInt(Constants.KEY_PADDING_X, Integer.parseInt(loadedSettings.getString(Constants.KEY_PADDING_X)));
            loadedSettings.putInt(Constants.KEY_DIVISOR, Integer.parseInt(loadedSettings.getString(Constants.KEY_DIVISOR)));
        } catch (Exception e) {
            // 设置默认值防止崩溃
            loadedSettings.putInt(Constants.KEY_BITMAP_SIZE, 64);
            loadedSettings.putInt(Constants.KEY_FONT_SIZE, 32);
            loadedSettings.putInt(Constants.KEY_OFFSET, 15);
            loadedSettings.putInt(Constants.KEY_PADDING_X, -2);
            loadedSettings.putInt(Constants.KEY_DIVISOR, 1000000000);
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
    /**
     * 根据英文 Key (如 "watt") 获取对应的中文 Label (如 "功耗 (Watt)")
     */
    private String getLabelFromKey(String key) {
        if ("none".equals(key) || key == null || key.isEmpty()) {
            return "无";
        }

        try {
            // 获取两个数组
            String[] values = getResources().getStringArray(R.array.data_values);
            String[] labels = getResources().getStringArray(R.array.data_labels);

            // 遍历英文数组找索引
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(key)) {
                    // 返回相同位置的中文
                    return labels[i];
                }
            }
        } catch (Exception e) {
            Log.e("MonitorService", "Error mapping key to label", e);
        }
        return key; // 找不到则返回原 Key 兜底
    }
}