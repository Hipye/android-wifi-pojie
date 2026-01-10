package wifi.pojie;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WifiApplication extends Application {
    private static final String TAG = "WifiApplication";
    private static final String PREFS_NAME = "wifi_pojie_state";
    private static final String KEY_CURRENT_SSID = "current_ssid";
    private static final String KEY_CURRENT_DICT_FILE = "current_dict_file";
    private static final String KEY_CURRENT_START_LINE = "current_start_line";
    private static final String KEY_CURRENT_STATE_SAVED = "current_state_saved";
    
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            ioExecutor.submit(() -> {
                try {
                    saveCurrentStateSync();
                } catch (Exception e) {
                    Log.e(TAG, "保存状态失败", e);
                }
                
                StringBuilder sb = new StringBuilder();
                sb.append("设备型号: ").append(android.os.Build.MODEL).append("\n");
                sb.append("系统版本: ").append(android.os.Build.VERSION.RELEASE).append("\n");
                sb.append("API等级: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
                sb.append("处理器型号: ").append(android.os.Build.HARDWARE).append("\n");
                sb.append("系统架构: ").append(android.os.Build.SUPPORTED_ABIS != null ? android.os.Build.SUPPORTED_ABIS[0] : "未知").append("\n\n");
                sb.append("\n线程: ").append(thread.getName()).append("\n");
                sb.append("异常: ").append(throwable).append("\n\n");
                for (StackTraceElement element : throwable.getStackTrace()) {
                    sb.append(element.toString()).append("\n");
                }
                
                final String errorMsg = sb.toString();
                mainHandler.post(() -> {
                    Intent intent = new Intent(getApplicationContext(), ErrorActivity.class);
                    intent.putExtra("error", errorMsg);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                });
            });
        });
    }
    
    private void saveCurrentStateSync() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CURRENT_STATE_SAVED, true).apply();
        Log.d(TAG, "异常退出状态已保存");
    }
    
    public static void saveCurrentStateDetails(Context context, String ssid, String dictFileName, int startLine) {
        ioExecutor.submit(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit()
                    .putString(KEY_CURRENT_SSID, ssid)
                    .putString(KEY_CURRENT_DICT_FILE, dictFileName)
                    .putInt(KEY_CURRENT_START_LINE, startLine)
                    .putBoolean(KEY_CURRENT_STATE_SAVED, true)
                    .apply();
                Log.d(TAG, "状态已保存: ssid=" + ssid + ", dictFile=" + dictFileName + ", startLine=" + startLine);
            } catch (Exception e) {
                Log.e(TAG, "保存状态详情失败", e);
            }
        });
    }
    
    public static void clearCurrentState(Context context) {
        ioExecutor.submit(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit()
                    .remove(KEY_CURRENT_SSID)
                    .remove(KEY_CURRENT_DICT_FILE)
                    .remove(KEY_CURRENT_START_LINE)
                    .putBoolean(KEY_CURRENT_STATE_SAVED, false)
                    .apply();
                Log.d(TAG, "状态已清除");
            } catch (Exception e) {
                Log.e(TAG, "清除状态失败", e);
            }
        });
    }
    
    public static boolean hasSavedStateSync(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_CURRENT_STATE_SAVED, false);
    }
    
    public static void checkSavedStateAsync(Context context, StateCheckCallback callback) {
        ioExecutor.submit(() -> {
            try {
                boolean hasState = hasSavedStateSync(context);
                String ssid = "";
                String dictFileName = "";
                int startLine = 1;
                
                if (hasState) {
                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    ssid = prefs.getString(KEY_CURRENT_SSID, "");
                    dictFileName = prefs.getString(KEY_CURRENT_DICT_FILE, "");
                    startLine = prefs.getInt(KEY_CURRENT_START_LINE, 1);
                }
                
                final boolean finalHasState = hasState;
                final String finalSsid = ssid;
                final String finalDictFileName = dictFileName;
                final int finalStartLine = startLine;
                
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onStateChecked(finalHasState, finalSsid, finalDictFileName, finalStartLine);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "检查保存状态失败", e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onStateChecked(false, "", "", 1);
                    }
                });
            }
        });
    }
    
    public static SavedState getSavedStateSync(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return new SavedState(
            prefs.getBoolean(KEY_CURRENT_STATE_SAVED, false),
            prefs.getString(KEY_CURRENT_SSID, ""),
            prefs.getString(KEY_CURRENT_DICT_FILE, ""),
            prefs.getInt(KEY_CURRENT_START_LINE, 1)
        );
    }
    
    public interface StateCheckCallback {
        void onStateChecked(boolean hasState, String ssid, String dictFileName, int startLine);
    }
    
    public static class SavedState {
        public final boolean hasState;
        public final String ssid;
        public final String dictFileName;
        public final int startLine;
        
        public SavedState(boolean hasState, String ssid, String dictFileName, int startLine) {
            this.hasState = hasState;
            this.ssid = ssid;
            this.dictFileName = dictFileName;
            this.startLine = startLine;
        }
    }
}
