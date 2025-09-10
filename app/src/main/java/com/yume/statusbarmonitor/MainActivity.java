package com.yume.statusbarmonitor;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;

public class MainActivity extends AppCompatActivity {

    // UI 控件
    private RadioGroup radioGroup1_1, radioGroup1_2;
    private RadioGroup radioGroup2_1, radioGroup2_2;
    private EditText etSize1, etOffset1, etSize2, etOffset2;
    private TextView statusText;
    private EditText etBitmapSize;
    private RadioGroup radioGroup1, radioGroup2;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        // 初始化控件
//        radioGroup1_1 = findViewById(R.id.radioGroup1_1);
//        radioGroup1_2 = findViewById(R.id.radioGroup1_2);

        etSize1 = findViewById(R.id.et_size1);
        etOffset1 = findViewById(R.id.et_offset1);

        statusText = findViewById(R.id.statusText);

        etBitmapSize = findViewById(R.id.et_bitmap_size);

        radioGroup1 = findViewById(R.id.radioGroup1);
        radioGroup2 = findViewById(R.id.radioGroup2);

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

    private void startServiceWithSettings() {
        Intent serviceIntent = new Intent(this, MonitorService.class);

        // 获取第一个图标的设置
        int selectedId1 = radioGroup1.getCheckedRadioButtonId();
        int selectedId2 = radioGroup2.getCheckedRadioButtonId();
        serviceIntent.putExtra("data1", getDataKey(selectedId1));
        serviceIntent.putExtra("data2", getDataKey(selectedId2));
        serviceIntent.putExtra("size", Integer.parseInt(etSize1.getText().toString()));
        serviceIntent.putExtra("offset", Integer.parseInt(etOffset1.getText().toString()));
        serviceIntent.putExtra("bitmapSize", Integer.parseInt(etBitmapSize.getText().toString()));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        statusText.setText("服务已启动");
    }

    private String getDataKey(int radioButtonId) {
        if (radioButtonId == R.id.rb_temp1 || radioButtonId == R.id.rb_temp2) return "temperature";
        if (radioButtonId == R.id.rb_current1 || radioButtonId == R.id.rb_current2) return "current";
        if (radioButtonId == R.id.rb_voltage1 || radioButtonId == R.id.rb_voltage2) return "voltage";
        if (radioButtonId == R.id.rb_percent1 || radioButtonId == R.id.rb_percent2) return "percent";
//        if (radioButtonId == R.id.rb_mem_gb1 || radioButtonId == R.id.rb_mem_gb2) return "memory_mb";
        if (radioButtonId == R.id.rb_mem_p1 || radioButtonId == R.id.rb_mem_p2) return "memory_percent";
        if (radioButtonId == R.id.rb_watt1 || radioButtonId == R.id.rb_watt2) return "watt";
        if (radioButtonId == R.id.rb_none1 || radioButtonId == R.id.rb_none2) return "none";
        return "none";
    }

}