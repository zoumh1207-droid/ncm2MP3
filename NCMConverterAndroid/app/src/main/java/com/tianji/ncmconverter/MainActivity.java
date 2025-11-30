package com.tianji.ncmconverter;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView logView;
    private Button startBtn;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private String inputPath;
    private String outputPath;

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logView = findViewById(R.id.logView);
        startBtn = findViewById(R.id.startBtn);

        inputPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/NCMConverter/input";
        outputPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/NCMConverter/output";

        appendLog("Input: " + inputPath);
        appendLog("Output: " + outputPath);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean ok = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) { ok = false; break; }
                    }
                    if (ok) {
                        appendLog("权限已授予");
                    } else {
                        appendLog("请授予存储权限以继续");
                    }
                }
        );

        startBtn.setOnClickListener(v -> {
            if (!checkAndRequestPermissions()) {
                appendLog("正在请求权限...");
                return;
            }
            startConversion();
        });

        // create folders if not exists
        File in = new File(inputPath);
        File out = new File(outputPath);
        if (!in.exists()) in.mkdirs();
        if (!out.exists()) out.mkdirs();
    }

    private void startConversion() {
        appendLog("开始转换任务（后台执行）...");
        startBtn.setEnabled(false);
        executor.submit(() -> {
            try {
                NCMBatchConverter.DEBUG = true;
                NCMBatchConverter.runConversion(inputPath, outputPath, new NCMBatchConverter.Logger() {
                    @Override
                    public void info(String s) { runOnUiThread(() -> appendLog(s)); }
                    @Override
                    public void error(String s) { runOnUiThread(() -> appendLog("ERR: " + s)); }
                });
                runOnUiThread(() -> appendLog("转换完成。查看输出文件夹。")); 
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("任务异常: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> startBtn.setEnabled(true));
            }
        });
    }

    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (read && write) return true;
            permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE});
            return false;
        }
        return true;
    }

    private void appendLog(String s) {
        logView.append(s + "\n");
        final ScrollView sv = findViewById(R.id.scrollView);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
