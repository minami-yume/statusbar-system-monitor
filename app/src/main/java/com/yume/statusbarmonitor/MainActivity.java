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

    private EditText etSize1, etOffset1;
    private TextView statusText;
    private EditText etBitmapSize;
    private RadioGroup radioGroup1, radioGroup2;
    private Spinner refreshRateSpinner, fontSpinner;
    private EditText etPadding;
    private  EditText etDivisor;

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
        refreshRateSpinner = findViewById(R.id.refresh_rate_spinner);
        etPadding = findViewById(R.id.et_padding);
        fontSpinner = findViewById(R.id.font_spinner);
        etDivisor = findViewById(R.id.et_divisor);

        loadSettings();

        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(v -> startServiceWithSettings());

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
        saveSettings();
    }

    private void saveSettings() {
        SharedPreferences sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPrefs.edit();

        editor.putInt(Constants.KEY_DATA1, radioGroup1.getCheckedRadioButtonId());
        editor.putInt(Constants.KEY_DATA2, radioGroup2.getCheckedRadioButtonId());
        editor.putString(Constants.KEY_FONT_SIZE, etSize1.getText().toString());
        editor.putString(Constants.KEY_OFFSET, etOffset1.getText().toString());
        editor.putString(Constants.KEY_BITMAP_SIZE, etBitmapSize.getText().toString());
        editor.putInt(Constants.KEY_REFRESH_RATE_POS, refreshRateSpinner.getSelectedItemPosition());
        editor.putString(Constants.KEY_PADDING_X, etPadding.getText().toString());
        editor.putInt(Constants.KEY_FONT_CHOICE, fontSpinner.getSelectedItemPosition());
        editor.putString(Constants.KEY_DIVISOR,etDivisor.getText().toString());
        editor.apply();
    }

    private void loadSettings() {
        SharedPreferences sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);

        int data1Id = R.id.rb_mem_p1;
        int data2Id = R.id.rb_watt2;
        String fontSize = "32";
        String offset = "15";
        String bitmapSize = "64";
        int refreshRatePos = 2;
        String paddingX = "-2";
        int savedFontPosition = 0;
        String divisor="1000000000";

        try {
            data1Id = sharedPrefs.getInt(Constants.KEY_DATA1, R.id.rb_mem_p1);
            data2Id = sharedPrefs.getInt(Constants.KEY_DATA2, R.id.rb_watt2);
            fontSize = sharedPrefs.getString(Constants.KEY_FONT_SIZE, "32");
            offset = sharedPrefs.getString(Constants.KEY_OFFSET, "15");
            bitmapSize = sharedPrefs.getString(Constants.KEY_BITMAP_SIZE, "64");
            refreshRatePos = sharedPrefs.getInt(Constants.KEY_REFRESH_RATE_POS, 2);
            paddingX = sharedPrefs.getString(Constants.KEY_PADDING_X, "-2");
            savedFontPosition = sharedPrefs.getInt(Constants.KEY_FONT_CHOICE, 0);
            divisor = sharedPrefs.getString(Constants.KEY_DIVISOR,"1000000000");
        } catch (ClassCastException e) {
            Log.e("MainActivity", "Failed to load settings from SharedPreferences, using default values.", e);
        }

        radioGroup1.check(data1Id);
        radioGroup2.check(data2Id);
        etSize1.setText(fontSize);
        etOffset1.setText(offset);
        etBitmapSize.setText(bitmapSize);
        refreshRateSpinner.setSelection(refreshRatePos);
        etPadding.setText(paddingX);
        fontSpinner.setSelection(savedFontPosition);
        etDivisor.setText(divisor);
    }

    private void startServiceWithSettings() {
        Intent serviceIntent = new Intent(this, MonitorService.class);

        int selectedId1 = radioGroup1.getCheckedRadioButtonId();
        int selectedId2 = radioGroup2.getCheckedRadioButtonId();
        serviceIntent.putExtra(Constants.KEY_DATA1, getDataKey(selectedId1));
        serviceIntent.putExtra(Constants.KEY_DATA2, getDataKey(selectedId2));
        serviceIntent.putExtra(Constants.KEY_FONT_SIZE, Integer.parseInt(etSize1.getText().toString()));
        serviceIntent.putExtra(Constants.KEY_OFFSET, Integer.parseInt(etOffset1.getText().toString()));
        serviceIntent.putExtra(Constants.KEY_BITMAP_SIZE, Integer.parseInt(etBitmapSize.getText().toString()));
        serviceIntent.putExtra(Constants.KEY_PADDING_X, Integer.parseInt(etPadding.getText().toString()));
        serviceIntent.putExtra(Constants.KEY_DIVISOR, Integer.parseInt(etDivisor.getText().toString()));

        String selectedRate = refreshRateSpinner.getSelectedItem().toString();
        int interval = Integer.parseInt(selectedRate.replace("s", "")) * 1000;
        serviceIntent.putExtra("interval", interval);

        int selectedFontPosition = fontSpinner.getSelectedItemPosition();
        serviceIntent.putExtra(Constants.KEY_FONT_CHOICE, selectedFontPosition);

        startForegroundService(serviceIntent);
        statusText.setText("服务已启动");
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
}