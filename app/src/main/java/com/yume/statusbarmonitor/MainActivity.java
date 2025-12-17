package com.yume.statusbarmonitor;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText etSize1, etOffset1, etPadding, etBitmapSize, etDivisor;
    private Spinner spinnerData1, spinnerData2; // 使用 Spinner 替代 RadioGroup
    private Spinner refreshRateSpinner, fontSpinner;
    private TextView statusText;

    // 用于将 Spinner 选项映射为 Service 能听懂的字符串
    private String[] dataValues;

    // *** 使用新的 Key 来存储 Spinner 的位置 ***
    // 这样可以彻底避开旧版本存入的 RadioButton ID 导致的类型冲突和闪退
    private static final String PREF_KEY_IDX_1 = "pref_idx_data1_v2";
    private static final String PREF_KEY_IDX_2 = "pref_idx_data2_v2";

    private Spinner spinnerRing; // 增加 spinnerRing
    private String[] ringValues; // 增加 ringValues 数组

    // 增加圆环的存储 Key
    private static final String PREF_KEY_IDX_RING = "pref_idx_ring_v2";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 加载数组资源
        dataValues = getResources().getStringArray(R.array.data_values);

        // 初始化控件
        statusText = findViewById(R.id.statusText);
        etSize1 = findViewById(R.id.et_size1);
        etOffset1 = findViewById(R.id.et_offset1);
        etPadding = findViewById(R.id.et_padding);
        etBitmapSize = findViewById(R.id.et_bitmap_size);
        etDivisor = findViewById(R.id.et_divisor);

        spinnerData1 = findViewById(R.id.spinner_data1);
        spinnerData2 = findViewById(R.id.spinner_data2);
        refreshRateSpinner = findViewById(R.id.refresh_rate_spinner);
        fontSpinner = findViewById(R.id.font_spinner);

        // 在 onCreate 中绑定
        spinnerRing = findViewById(R.id.spinner_ring);
        dataValues = getResources().getStringArray(R.array.data_values);
        ringValues = getResources().getStringArray(R.array.ring_values);

        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        loadSettings();

        startButton.setOnClickListener(v -> {
            saveSettings(); // 先保存
            startServiceWithSettings(); // 再启动
        });

        stopButton.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, MonitorService.class);
            stopService(serviceIntent);
            statusText.setText("服务已停止");
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit();

        // 保存输入框 (保持原有逻辑)
        editor.putString(Constants.KEY_FONT_SIZE, etSize1.getText().toString());
        editor.putString(Constants.KEY_OFFSET, etOffset1.getText().toString());
        editor.putString(Constants.KEY_BITMAP_SIZE, etBitmapSize.getText().toString());
        editor.putString(Constants.KEY_PADDING_X, etPadding.getText().toString());
        editor.putString(Constants.KEY_DIVISOR, etDivisor.getText().toString());
        editor.putInt(Constants.KEY_REFRESH_RATE_POS, refreshRateSpinner.getSelectedItemPosition());
        editor.putInt(Constants.KEY_FONT_CHOICE, fontSpinner.getSelectedItemPosition());

        editor.putInt(PREF_KEY_IDX_RING, spinnerRing.getSelectedItemPosition());

        // *** 关键：保存 Spinner 的选中位置到新的 Key ***
        editor.putInt(PREF_KEY_IDX_1, spinnerData1.getSelectedItemPosition());
        editor.putInt(PREF_KEY_IDX_2, spinnerData2.getSelectedItemPosition());

        editor.apply();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);

        // 安全加载文本框 (使用 try-catch 防止旧数据类型不匹配)
        setSafeText(etSize1, prefs, Constants.KEY_FONT_SIZE, "32");
        setSafeText(etOffset1, prefs, Constants.KEY_OFFSET, "15");
        setSafeText(etBitmapSize, prefs, Constants.KEY_BITMAP_SIZE, "64");
        setSafeText(etPadding, prefs, Constants.KEY_PADDING_X, "-2");
        setSafeText(etDivisor, prefs, Constants.KEY_DIVISOR, "1000000000");

        refreshRateSpinner.setSelection(prefs.getInt(Constants.KEY_REFRESH_RATE_POS, 2));
        fontSpinner.setSelection(prefs.getInt(Constants.KEY_FONT_CHOICE, 0));

        // *** 关键：加载 Spinner 位置。如果之前没存过（旧用户），默认选中第 1 项和第 3 项 ***
        spinnerData1.setSelection(prefs.getInt(PREF_KEY_IDX_1, 1)); // 默认 Watt
        spinnerData2.setSelection(prefs.getInt(PREF_KEY_IDX_2, 3)); // 默认 Memory

        spinnerRing.setSelection(prefs.getInt(PREF_KEY_IDX_RING, 0)); // 默认选“无”
    }

    private void startServiceWithSettings() {
        Intent serviceIntent = new Intent(this, MonitorService.class);

        try {
            // 1. 获取 Spinner 选中的位置
            int pos1 = spinnerData1.getSelectedItemPosition();
            int pos2 = spinnerData2.getSelectedItemPosition();

            // 2. 将位置转换为 Service 能看懂的 String Key (例如 "watt", "temp")
            // 这一步解决了 "通知无法显示" 的问题
            String key1 = (pos1 >= 0 && pos1 < dataValues.length) ? dataValues[pos1] : "none";
            String key2 = (pos2 >= 0 && pos2 < dataValues.length) ? dataValues[pos2] : "none";

            serviceIntent.putExtra(Constants.KEY_DATA1, key1);
            serviceIntent.putExtra(Constants.KEY_DATA2, key2);

            // 3. 传递数字参数
            serviceIntent.putExtra(Constants.KEY_FONT_SIZE, Integer.parseInt(etSize1.getText().toString()));
            serviceIntent.putExtra(Constants.KEY_OFFSET, Integer.parseInt(etOffset1.getText().toString()));
            serviceIntent.putExtra(Constants.KEY_BITMAP_SIZE, Integer.parseInt(etBitmapSize.getText().toString()));
            serviceIntent.putExtra(Constants.KEY_PADDING_X, Integer.parseInt(etPadding.getText().toString()));
            serviceIntent.putExtra(Constants.KEY_DIVISOR, Integer.parseInt(etDivisor.getText().toString()));

            // 4. 处理刷新率 (保留你的 "1s" 去掉 "s" 的逻辑)
            String selectedRate = refreshRateSpinner.getSelectedItem().toString();
            int interval = 3000;
            try {
                interval = Integer.parseInt(selectedRate.replace("s", "")) * 1000;
            } catch (Exception e) {
                interval = 3000; // 默认值防止解析失败
            }
            serviceIntent.putExtra("interval", interval);

            serviceIntent.putExtra(Constants.KEY_FONT_CHOICE, fontSpinner.getSelectedItemPosition());


            int posRing = spinnerRing.getSelectedItemPosition();
            String keyRing = (posRing >= 0 && posRing < ringValues.length) ? ringValues[posRing] : "none";

            // 传给 Service，Key 必须和 MonitorService 脚本里解析的对应（通常是 "ring_key" 或 Constants.KEY_RING）
            serviceIntent.putExtra("ring_key", keyRing);


            startForegroundService(serviceIntent);
            statusText.setText("服务已启动: \n" + key1 + " & " + key2 + " & " + keyRing);

        } catch (NumberFormatException e) {
            Toast.makeText(this, "参数错误：请输入有效的数字", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    // 辅助方法：安全设置文本，防止 Null 或 类型错误
    private void setSafeText(TextView view, SharedPreferences prefs, String key, String defaultVal) {
        try {
            String val = prefs.getString(key, defaultVal);
            view.setText(val);
        } catch (ClassCastException e) {
            // 兼容性处理：如果旧数据是 int，这里转为 String，防止闪退
            try {
                view.setText(String.valueOf(prefs.getInt(key, Integer.parseInt(defaultVal))));
            } catch (Exception ex) {
                view.setText(defaultVal);
            }
        }
    }
}