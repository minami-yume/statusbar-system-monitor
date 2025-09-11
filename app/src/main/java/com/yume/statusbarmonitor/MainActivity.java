package com.yume.statusbarmonitor;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;

public class MainActivity extends AppCompatActivity {

    // UI 控件
    private EditText etSize1, etOffset1;
    private TextView statusText;
    private EditText etBitmapSize;
    private RadioGroup radioGroup1, radioGroup2;
    private Spinner refreshRateSpinner; // 新增：刷新频率下拉菜单
    private EditText etPadding;

    // SharedPreferences 键
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_DATA1_ID = "data1_id";
    private static final String KEY_DATA2_ID = "data2_id";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_BITMAP_SIZE = "bitmap_size";
    private static final String KEY_REFRESH_RATE_POS = "refresh_rate_position";

    private static final String KEY_PADDING_X = "padding_x";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化控件
        etSize1 = findViewById(R.id.et_size1);
        etOffset1 = findViewById(R.id.et_offset1);
        statusText = findViewById(R.id.statusText);
        etBitmapSize = findViewById(R.id.et_bitmap_size);
        radioGroup1 = findViewById(R.id.radioGroup1);
        radioGroup2 = findViewById(R.id.radioGroup2);
        refreshRateSpinner = findViewById(R.id.refresh_rate_spinner); // 初始化 Spinner
        etPadding = findViewById(R.id.et_padding);

        // 加载用户设置
        loadSettings();

        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(v -> {
            startServiceWithSettings();
        });

        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, MonitorService.class);
            stopService(serviceIntent);
            statusText.setText("服务已停止");
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 在应用暂停时保存设置
        saveSettings();
    }

    private void saveSettings() {
        SharedPreferences sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPrefs.edit();

        editor.putInt(KEY_DATA1_ID, radioGroup1.getCheckedRadioButtonId());
        editor.putInt(KEY_DATA2_ID, radioGroup2.getCheckedRadioButtonId());
        editor.putString(KEY_FONT_SIZE, etSize1.getText().toString());
        editor.putString(KEY_OFFSET, etOffset1.getText().toString());
        editor.putString(KEY_BITMAP_SIZE, etBitmapSize.getText().toString());
        editor.putInt(KEY_REFRESH_RATE_POS, refreshRateSpinner.getSelectedItemPosition());
        editor.putString(KEY_PADDING_X, etPadding.getText().toString());

        editor.apply();
    }

    private void loadSettings() {
        SharedPreferences sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        int data1Id = R.id.rb_mem_p1;
        int data2Id = R.id.rb_watt2;
        String fontSize = "32";
        String offset = "15";
        String bitmapSize = "64";
        int refreshRatePos = 2;
        String paddingX ="-2";


        try {
            // 尝试从 SharedPreferences 中加载设置
            data1Id = sharedPrefs.getInt(KEY_DATA1_ID, R.id.rb_mem_p1);
            data2Id = sharedPrefs.getInt(KEY_DATA2_ID, R.id.rb_watt2);
            fontSize = sharedPrefs.getString(KEY_FONT_SIZE, "32");
            offset = sharedPrefs.getString(KEY_OFFSET, "15");
            bitmapSize = sharedPrefs.getString(KEY_BITMAP_SIZE, "64");
            refreshRatePos = sharedPrefs.getInt(KEY_REFRESH_RATE_POS, 2);
            paddingX = sharedPrefs.getString(KEY_PADDING_X, "-2");
        } catch (ClassCastException e) {
            // 如果数据类型不匹配，打印错误日志并使用默认值
            // 这通常发生在 SharedPreferences 文件损坏或数据类型改变时
            Log.e("MainActivity", "Failed to load settings from SharedPreferences, using default values.", e);
        }

        radioGroup1.check(data1Id);
        radioGroup2.check(data2Id);
        etSize1.setText(fontSize);
        etOffset1.setText(offset);
        etBitmapSize.setText(bitmapSize);
        refreshRateSpinner.setSelection(refreshRatePos);
        etPadding.setText(paddingX);
    }


    private void startServiceWithSettings() {
        Intent serviceIntent = new Intent(this, MonitorService.class);

        // 获取并传递用户设置
        int selectedId1 = radioGroup1.getCheckedRadioButtonId();
        int selectedId2 = radioGroup2.getCheckedRadioButtonId();
        serviceIntent.putExtra("data1", getDataKey(selectedId1));
        serviceIntent.putExtra("data2", getDataKey(selectedId2));
        serviceIntent.putExtra("size", Integer.parseInt(etSize1.getText().toString()));
        serviceIntent.putExtra("offset", Integer.parseInt(etOffset1.getText().toString()));
        serviceIntent.putExtra("bitmapSize", Integer.parseInt(etBitmapSize.getText().toString()));
        serviceIntent.putExtra("padding_x", Integer.parseInt(etPadding.getText().toString()));

        // 新增：获取并传递刷新频率
        String selectedRate = refreshRateSpinner.getSelectedItem().toString();
        int interval = Integer.parseInt(selectedRate.replace("s", "")) * 1000;
        serviceIntent.putExtra("interval", interval);

        startForegroundService(serviceIntent);
        statusText.setText("服务已启动");
    }

    private String getDataKey(int radioButtonId) {
        if (radioButtonId == R.id.rb_temp1 || radioButtonId == R.id.rb_temp2) return "temperature";
        if (radioButtonId == R.id.rb_current1 || radioButtonId == R.id.rb_current2) return "current";
        if (radioButtonId == R.id.rb_voltage1 || radioButtonId == R.id.rb_voltage2) return "voltage";
        if (radioButtonId == R.id.rb_percent1 || radioButtonId == R.id.rb_percent2) return "percent";
        if (radioButtonId == R.id.rb_mem_p1 || radioButtonId == R.id.rb_mem_p2) return "memory_percent";
        if (radioButtonId == R.id.rb_watt1 || radioButtonId == R.id.rb_watt2) return "watt";
        if (radioButtonId == R.id.rb_none1 || radioButtonId == R.id.rb_none2) return "none";
        return "none";
    }
}