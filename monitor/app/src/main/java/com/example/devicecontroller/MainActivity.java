package com.example.devicecontroller;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private TextView statusText;
    private Button startButton;
    private Button stopButton;
    private static final int REQUEST_PERMISSIONS = 100;
    
    // Required permissions
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.INTERNET
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        setupListeners();
        checkPermissions();
    }
    
    private void initViews() {
        statusText = findViewById(R.id.statusText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
    }
    
    private void setupListeners() {
        startButton.setOnClickListener(v -> startMonitorService());
        stopButton.setOnClickListener(v -> stopMonitorService());
    }
    
    private void checkPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        
        // Add MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // This needs to be handled separately with intent
            }
        }
        
        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toArray(new String[0]),
                REQUEST_PERMISSIONS
            );
        } else {
            updateStatus("✅ All permissions granted");
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                updateStatus("✅ All permissions granted");
                startMonitorService();
            } else {
                showPermissionDialog();
            }
        }
    }
    
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("All permissions are required for the app to function properly.\n" +
                       "Please grant all permissions in Settings.")
            .setPositiveButton("Open Settings", (dialog, which) -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("Exit", (dialog, which) -> finish())
            .setCancelable(false)
            .show();
    }
    
    private void startMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateStatus("🟢 Service Running");
        Toast.makeText(this, "Monitor Service Started", Toast.LENGTH_SHORT).show();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }
    
    private void stopMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        stopService(intent);
        updateStatus("🔴 Service Stopped");
        Toast.makeText(this, "Monitor Service Stopped", Toast.LENGTH_SHORT).show();
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }
    
    private void updateStatus(String status) {
        statusText.setText("Status: " + status);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Check if service is running
        boolean isRunning = isServiceRunning(MonitorService.class);
        if (isRunning) {
            updateStatus("🟢 Service Running");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } else {
            updateStatus("🔴 Service Stopped");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }
    
    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = 
            (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : 
             manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}