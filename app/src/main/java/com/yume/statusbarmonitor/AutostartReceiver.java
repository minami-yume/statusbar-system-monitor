package com.yume.statusbarmonitor;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;

public class AutostartReceiver extends BroadcastReceiver {

    private static final String TAG = "AutostartReceiver";
    // 延迟 5 秒执行
    private static final long DELAY_MILLIS = 5 * 1000;
    // 唯一的 Alarm ID
    private static final int ALARM_REQUEST_CODE = 12345;

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {

            Log.d(TAG, "Boot completed received. Setting up delayed start alarm.");

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent serviceIntent = new Intent(context, MonitorService.class);

            // 使用 FLAG_IMMUTABLE 或 FLAG_UPDATE_CURRENT
            int flags = PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getService(
                    context,
                    ALARM_REQUEST_CODE,
                    serviceIntent,
                    flags
            );

            long triggerAtMillis = System.currentTimeMillis() + DELAY_MILLIS;

            // 使用 setExactAndAllowWhileIdle 或 setAndAllowWhileIdle
            // 在 Doze 模式下也允许运行的精确闹钟
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);

            // Toast 提示设置了延迟启动
            Toast.makeText(context.getApplicationContext(),
                    "状态栏监控服务已启动",
                    Toast.LENGTH_SHORT).show();
        }
    }
}