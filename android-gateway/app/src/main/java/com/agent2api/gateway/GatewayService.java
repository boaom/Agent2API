package com.agent2api.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 网关前台服务：拉起 APK 内的 aarch64 网关二进制，解包面板资源，监控退出并重启。
 *
 * 网关二进制以 libagent2api_server.so 的形式放在 jniLibs。Android 只允许从
 * nativeLibraryDir 执行（App 可写目录在 targetSdk >= 29 上是 W^X 禁止的），
 * 所以 APK 必须 extractNativeLibs=true，Gradle 也必须 useLegacyPackaging=true。
 */
public final class GatewayService extends Service {
    public static final String ACTION_START = "com.agent2api.gateway.START";
    public static final String ACTION_STOP = "com.agent2api.gateway.STOP";
    public static final String ACTION_RESTART = "com.agent2api.gateway.RESTART";
    public static final String CHANNEL_ID = "agent2api-gateway";
    public static final int NOTIFICATION_ID = 3065;
    public static final int PORT = 3065;

    public static volatile String state = "stopped";
    public static volatile String lastError = "";

    private static final String TAG = "Agent2APIGateway";
    private static final String LAUNCHER = "libagent2api_launcher.so";
    private static final String SERVER = "libagent2api_server.so";

    private final Object lock = new Object();
    private Process process;
    private Thread supervisor;
    private volatile boolean userStopped = false;
    private volatile boolean serviceDestroyed = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, GatewayService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void restart(Context context) {
        Intent intent = new Intent(context, GatewayService.class).setAction(ACTION_RESTART);
        context.startForegroundService(intent);
    }

    public static File logFile(Context context) {
        return new File(new File(context.getFilesDir(), "gateway"), "server.log");
    }

    public static String readLogTail(Context context) {
        File file = logFile(context);
        if (!file.isFile()) return "(还没有网关日志)";
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - 128 * 1024);
            byte[] bytes = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "读取日志失败：" + error;
        }
    }

    public static String accessSummary() {
        String ip = localIpv4();
        return "本机 127.0.0.1:" + PORT + (ip == null ? "" : " · 局域网 " + ip + ":" + PORT);
    }

    public static String localIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            userStopped = true;
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat("正在启动网关…");

        if (ACTION_RESTART.equals(action)) {
            userStopped = true;
            stopServer();
            Thread thread = supervisor;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(4000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        userStopped = false;
        ensureSupervisor();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        userStopped = true;
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception error) {
            Log.e(TAG, "startForeground failed", error);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, GatewayService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
            this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("Agent2API 网关")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(0xFF2F6FED)
            .addAction(new Notification.Action.Builder(null, "停止", stopPending).build());
        return builder.build();
    }

    private void updateNotification(String text) {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private void ensureSupervisor() {
        synchronized (lock) {
            if (supervisor != null && supervisor.isAlive()) return;
            supervisor = new Thread(this::supervise, "agent2api-gateway");
            supervisor.start();
        }
    }

    private void supervise() {
        int consecutiveFailures = 0;
        while (!userStopped && !serviceDestroyed) {
            Process running = null;
            long startedAt = SystemClock.elapsedRealtime();
            try {
                state = "starting";
                lastError = "";
                updateNotification("正在准备运行环境…");

                File home = new File(getFilesDir(), "gateway");
                if (!home.isDirectory() && !home.mkdirs()) {
                    throw new IOException("无法创建数据目录 " + home);
                }
                File ui = prepareUi(home);
                File log = logFile(this);
                if (log.length() > 8L * 1024 * 1024) {
                    // 防止反复崩溃把日志无限写大
                    //noinspection ResultOfMethodCallIgnored
                    log.delete();
                }

                File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
                File launcher = new File(nativeDir, LAUNCHER);
                File server = new File(nativeDir, SERVER);
                if (!server.isFile()) {
                    throw new IOException("APK 内缺少 " + SERVER + "（nativeLibraryDir=" + nativeDir + "）");
                }
                if (!server.canExecute()) {
                    throw new IOException("网关二进制没有执行权限：" + server);
                }

                ProcessBuilder builder;
                if (launcher.isFile()) {
                    builder = new ProcessBuilder(launcher.getAbsolutePath(), server.getAbsolutePath());
                } else {
                    builder = new ProcessBuilder(server.getAbsolutePath());
                }
                builder.directory(home);
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
                Map<String, String> env = builder.environment();
                env.put("AGENT2API_PROXY_HOME", home.getAbsolutePath());
                env.put("AGENT2API_HOST", "0.0.0.0");
                env.put("AGENT2API_PROXY_PORT", String.valueOf(PORT));
                env.put("AGENT2API_UI_DIR", ui.getAbsolutePath());
                env.put("AGENT2API_SELF_UPDATE", "0");
                env.put("AGENT2API_DEPLOY", "android");
                env.put("HOME", home.getAbsolutePath());
                env.put("TMPDIR", getCacheDir().getAbsolutePath());
                env.put("PATH", "/system/bin:/system/xbin:/vendor/bin");
                env.put("LANG", "zh_CN.UTF-8");

                updateNotification("正在启动网关…");
                running = builder.start();
                synchronized (lock) {
                    process = running;
                    if (userStopped) running.destroy();
                }
                state = "running";
                updateNotification(accessSummary());
                Log.i(TAG, "gateway started");

                int code = running.waitFor();
                if (SystemClock.elapsedRealtime() - startedAt > 30_000) consecutiveFailures = 0;
                synchronized (lock) {
                    if (process == running) process = null;
                }
                if (userStopped || serviceDestroyed) break;

                consecutiveFailures++;
                lastError = "网关进程退出（code=" + code + "）";
                state = "restarting";
                updateNotification("网关退出，准备重启…");
                Log.w(TAG, lastError);
            } catch (InterruptedException interrupted) {
                if (userStopped || serviceDestroyed) {
                    Thread.currentThread().interrupt();
                    break;
                }
                consecutiveFailures++;
                lastError = "启动被中断";
                state = "error";
            } catch (Exception error) {
                consecutiveFailures++;
                lastError = String.valueOf(error.getMessage());
                state = "error";
                updateNotification("启动失败，准备重试…");
                Log.e(TAG, "gateway start failed", error);
            } finally {
                synchronized (lock) {
                    if (process == running) process = null;
                }
                if (running != null) {
                    try {
                        running.destroy();
                    } catch (Exception ignored) {
                    }
                }
            }

            if (consecutiveFailures >= 5) {
                state = "error";
                lastError = lastError + "；连续失败 5 次，已停止自动重启";
                updateNotification("启动失败，请点开应用查看日志");
                break;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException interrupted) {
                if (userStopped || serviceDestroyed) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (userStopped || serviceDestroyed) {
            state = "stopped";
            updateNotification("网关已停止");
        }
        synchronized (lock) {
            if (supervisor == Thread.currentThread()) supervisor = null;
        }
    }

    private void stopServer() {
        Process running;
        synchronized (lock) {
            running = process;
            process = null;
        }
        if (running == null) return;
        try {
            running.destroy();
            if (!running.waitFor(3, TimeUnit.SECONDS)) {
                running.destroyForcibly();
                running.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception error) {
            try {
                running.destroyForcibly();
            } catch (Exception ignored) {
            }
            Log.w(TAG, "stop gateway failed", error);
        }
    }

    private File prepareUi(File home) throws Exception {
        File ui = new File(getFilesDir(), "ui");
        String stamp = uiStamp();
        File marker = new File(ui, ".stamp");
        if (ui.isDirectory() && marker.isFile() && stamp.equals(readText(marker))) {
            return ui;
        }

        state = "extracting";
        updateNotification("首次启动，正在解包面板…");
        if (ui.exists()) deleteRecursively(ui);
        if (!ui.mkdirs() && !ui.isDirectory()) throw new IOException("无法创建面板目录 " + ui);
        copyAssetDir(getAssets(), "ui", ui);
        writeText(marker, stamp);
        Log.i(TAG, "ui extracted to " + ui);
        return ui;
    }

    private String uiStamp() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode + ":" + info.lastUpdateTime;
        } catch (Exception error) {
            return "unknown";
        }
    }

    private static void copyAssetDir(AssetManager assets, String assetPath, File destination)
        throws IOException {
        String[] children = null;
        try {
            children = assets.list(assetPath);
        } catch (IOException ignored) {
            // AssetManager.list 对「文件路径」可能直接抛异常，下面按文件处理
        }
        if (children == null || children.length == 0) {
            try (InputStream input = assets.open(assetPath)) {
                copyFile(input, destination);
            } catch (IOException notAFile) {
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IOException("无法创建目录 " + destination);
                }
            }
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录 " + destination);
        }
        for (String child : children) {
            String childPath = assetPath + "/" + child;
            File childDestination = new File(destination, child);
            String[] grandchildren = null;
            try {
                grandchildren = assets.list(childPath);
            } catch (IOException ignored) {
            }
            if (grandchildren != null && grandchildren.length > 0) {
                copyAssetDir(assets, childPath, childDestination);
            } else {
                try (InputStream input = assets.open(childPath)) {
                    copyFile(input, childDestination);
                } catch (IOException notAFile) {
                    if (!childDestination.isDirectory() && !childDestination.mkdirs()) {
                        throw new IOException("无法创建目录 " + childDestination);
                    }
                }
            }
        }
    }

    private static void copyFile(InputStream input, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 4096)];
            int read = input.read(bytes);
            return new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean healthy() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + PORT + "/health").openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            connection.setUseCaches(false);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}