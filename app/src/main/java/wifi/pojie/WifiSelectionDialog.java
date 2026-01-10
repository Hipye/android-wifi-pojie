package wifi.pojie;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WifiSelectionDialog extends AppCompatActivity {
    private static final String TAG = "WifiSelectionDialog";
    private WifiListAdapter adapter;
    private List<WifiInfo> wifiInfoList;
    private Handler handler;
    private Runnable addWifiRunnable;
    private SwipeRefreshLayout swipeRefreshLayout;

    private SettingsManager settingsManager;
    private WifiManager wifiManager;
    private WifiScanReceiver wifiScanReceiver;

    String result;

    private static final int REQUEST_CODE_FINE_LOCATION = 1001;

    public static class WifiInfo {
        String name;
        int rssi;
        boolean isSaved;

        public WifiInfo(String name, int rssi, boolean isSaved) {
            this.name = name;
            this.rssi = rssi;
            this.isSaved = isSaved;
        }
    }

    private class WifiScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "WifiScanReceiver: 收到扫描结果广播");
            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            Log.d(TAG, "WifiScanReceiver: EXTRA_RESULTS_UPDATED=" + success);
            
            if (!success) {
                Log.w(TAG, "WifiScanReceiver: 刷新失败");
                Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show();
            }
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "WifiScanReceiver: 定位权限被拒绝");
                Toast.makeText(context, "定位被拒绝", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            
            List<ScanResult> scanResults = wifiManager.getScanResults();
            Log.d(TAG, "WifiScanReceiver: 扫描结果数量=" + scanResults.size());
            
            wifiInfoList.clear();
            List<String> savedNetworks = getSavedNetworks();
            Log.d(TAG, "WifiScanReceiver: 已保存网络数量=" + savedNetworks.size());
            
            int successCount = 0;
            for (ScanResult scanResult : scanResults) {
                String name = scanResult.SSID;
                int rssi = scanResult.level;
                boolean isSaved = savedNetworks.contains(name);
                if (!name.isEmpty()) {
                    Log.d(TAG, "WifiScanReceiver: 添加WiFi - name=" + name + ", rssi=" + rssi + ", isSaved=" + isSaved);
                    wifiInfoList.add(new WifiInfo(name, rssi, isSaved));
                    successCount++;
                } else {
                    Log.d(TAG, "WifiScanReceiver: 跳过空SSID");
                }
            }
            
            Log.d(TAG, "WifiScanReceiver: 成功添加" + successCount + "个WiFi");
            
            wifiInfoList.sort((wifi1, wifi2) -> Integer.compare(wifi2.rssi, wifi1.rssi));
            Log.d(TAG, "WifiScanReceiver: 已按RSSI排序");
            
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);
                Log.d(TAG, "WifiScanReceiver: 已更新UI，WiFi列表数量=" + wifiInfoList.size());
            });
            runOnUiThread(() -> swipeRefreshLayout.setRefreshing(false));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: 开始创建WifiSelectionDialog");
        setContentView(R.layout.activity_wifi_selection_dialog);

        setTitle("选择WiFi网络");
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels;
            params.height = getResources().getDisplayMetrics().heightPixels * 3 / 4;
            window.setAttributes(params);
        }
        settingsManager = new SettingsManager(this);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        Log.d(TAG, "onCreate: wifiManager=" + (wifiManager != null ? "已初始化" : "为空"));

        int scanMode = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE);
        Log.d(TAG, "onCreate: scanMode=" + scanMode + " (0=标准模式, 1=命令行模式)");
        if (scanMode == 0) {
            wifiScanReceiver = new WifiScanReceiver();
            registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            Log.d(TAG, "onCreate: 已注册WifiScanReceiver");
        }
        initViews();
        setupWifiList();
        Log.d(TAG, "onCreate: WifiSelectionDialog创建完成");
    }

    private void initViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        ListView wifiListView = findViewById(R.id.wifi_list);
        wifiInfoList = new ArrayList<>();
        adapter = new WifiListAdapter(this, wifiInfoList);
        wifiListView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::refreshWifiList);

        wifiListView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                swipeRefreshLayout.setEnabled(firstVisibleItem == 0);
            }
        });

        Button cancelButton = findViewById(R.id.button_cancel);
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

    }

    private void setupWifiList() {
        Log.d(TAG, "setupWifiList: 开始设置WiFi列表");
        swipeRefreshLayout.setRefreshing(true);
        handler = new Handler();
        addWifiRunnable = this::loadWifiListData;
        handler.post(addWifiRunnable);
        Log.d(TAG, "setupWifiList: 已提交loadWifiListData任务到Handler");
    }

    private void refreshWifiList() {
        handler = new Handler();
        addWifiRunnable = this::loadWifiListData;
        handler.post(addWifiRunnable);
    }

    private List<String> getSavedNetworks() {
        List<String> savedNetworks = new ArrayList<>();
        int manageMode = settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE);
        if (manageMode == 0) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予，无法获取已保存网络，返回空列表
                return savedNetworks;
            }
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            if (configuredNetworks != null) {
                for (WifiConfiguration config : configuredNetworks) {
                    if (config.SSID != null) {
                        savedNetworks.add(config.SSID.replace("\"", ""));
                    }
                }
            }
        } else if (manageMode == 1) {
            String result = runCommand("cmd wifi list-networks", settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD));
            String[] lines = result.split("\n");
            for (int i = 1; i < lines.length; i++) {
                try {
                    String name = lines[i].substring(13, 46).split(" ")[0];
                    savedNetworks.add(name);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing saved network: " + lines[i], e);
                }
            }
        }
        return savedNetworks;
    }

    private void loadWifiListData() {
        Log.d(TAG, "loadWifiListData: 开始加载WiFi列表数据");
        int scanMode = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE);
        Log.d(TAG, "loadWifiListData: scanMode=" + scanMode);
        
        if (scanMode == 0) {
            Log.d(TAG, "loadWifiListData: 使用标准模式扫描");
            if (wifiManager != null) {
                boolean wifiEnabled = wifiManager.isWifiEnabled();
                Log.d(TAG, "loadWifiListData: WiFi状态=" + (wifiEnabled ? "已开启" : "已关闭"));
                
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "loadWifiListData: 定位权限未授予，请求权限");
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            REQUEST_CODE_FINE_LOCATION);
                } else {
                    Log.d(TAG, "loadWifiListData: 定位权限已授予，开始扫描");
                    boolean scanStarted = wifiManager.startScan();
                    Log.d(TAG, "loadWifiListData: startScan返回=" + scanStarted);
                }
            } else {
                Log.e(TAG, "loadWifiListData: wifiManager为空，无法扫描");
            }
        } else if (scanMode == 1) {
            Log.d(TAG, "loadWifiListData: 使用命令行模式扫描");
            int runType = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD);
            Log.d(TAG, "loadWifiListData: runType=" + runType + " (0=命令行, 1=Shizuku)");
            
            String scanResult = runCommand("cmd wifi start-scan", runType);
            Log.d(TAG, "loadWifiListData: start-scan命令返回: " + scanResult);
            
            result = runCommand("cmd wifi list-scan-results", runType);
            Log.d(TAG, "loadWifiListData: list-scan-results命令返回长度=" + result.length());
            Log.d(TAG, "loadWifiListData: list-scan-results原始结果:\n" + result);
            
            String[] lines = result.split("\n");
            Log.d(TAG, "loadWifiListData: 解析到" + lines.length + "行数据");

            // 清空并重新填充列表
            wifiInfoList.clear();
            List<String> savedNetworks = getSavedNetworks();
            Log.d(TAG, "loadWifiListData: 已保存网络数量=" + savedNetworks.size());

            int successCount = 0;
            int failCount = 0;
            
            for (int i = 1; i < lines.length; i++) {
                try {
                    if (lines[i].length() < 64) {
                        Log.w(TAG, "loadWifiListData: 第" + i + "行长度不足，跳过 - 长度=" + lines[i].length());
                        continue;
                    }
                    
                    String fullSubstring = lines[i].substring(64, 99);
                    int bracketIndex = fullSubstring.indexOf('[');
                    String name;
                    if (bracketIndex != -1) {
                        name = fullSubstring.substring(0, bracketIndex).trim();
                    } else {
                        name = fullSubstring.split(" ")[0].trim();
                    }
                    
                    if (lines[i].length() < 53) {
                        Log.w(TAG, "loadWifiListData: 第" + i + "行长度不足53，跳过");
                        continue;
                    }
                    
                    String line = lines[i];
                    int rssi;
                    Pattern rssiPattern = Pattern.compile("\\s+(-?\\d+)\\(");
                    Matcher rssiMatcher = rssiPattern.matcher(line);
                    if (rssiMatcher.find()) {
                        String rssiStr = rssiMatcher.group(1);
                        Log.d(TAG, "loadWifiListData: 第" + i + "行 - 使用正则提取RSSI: " + rssiStr);
                        rssi = Integer.parseInt(rssiStr);
                    } else {
                        Log.e(TAG, "loadWifiListData: 第" + i + "行 - 无法提取RSSI值，跳过");
                        continue;
                    }
                    
                    Log.d(TAG, "loadWifiListData: 第" + i + "行 - name: " + name + ", rssi: " + rssi);
                    boolean isSaved = savedNetworks.contains(name);
                    if (name.isEmpty()) {
                        Log.d(TAG, "loadWifiListData: 第" + i + "行 - name为空，跳过");
                        continue;
                    }
                    wifiInfoList.add(new WifiInfo(name, rssi, isSaved));
                    successCount++;
                } catch (RuntimeException e) {
                    Log.e(TAG, "loadWifiListData: 第" + i + "行解析失败 - " + lines[i], e);
                    failCount++;
                }
            }
            
            Log.d(TAG, "loadWifiListData: 解析完成 - 成功=" + successCount + ", 失败=" + failCount + ", 总计=" + wifiInfoList.size());
            
            // 按RSSI从大到小排序
            wifiInfoList.sort((wifi1, wifi2) -> Integer.compare(wifi2.rssi, wifi1.rssi));
            Log.d(TAG, "loadWifiListData: 已按RSSI排序");

            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);
                Log.d(TAG, "loadWifiListData: 已更新UI，WiFi列表数量=" + wifiInfoList.size());
            });
        } else {
            Log.w(TAG, "loadWifiListData: 未知的scanMode=" + scanMode);
            runOnUiThread(() -> {
                swipeRefreshLayout.setRefreshing(false);
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult: requestCode=" + requestCode + ", grantResults.length=" + grantResults.length);
        
        if (requestCode == REQUEST_CODE_FINE_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，重新开始扫描
                Log.d(TAG, "onRequestPermissionsResult: 定位权限已授予，重新开始扫描");
                if (wifiManager != null) {
                    boolean scanStarted = wifiManager.startScan();
                    Log.d(TAG, "onRequestPermissionsResult: startScan返回=" + scanStarted);
                } else {
                    Log.e(TAG, "onRequestPermissionsResult: wifiManager为空");
                }
            } else {
                // 权限被拒绝，停止刷新动画
                Log.w(TAG, "onRequestPermissionsResult: 定位权限被拒绝");
                runOnUiThread(() -> {
                    swipeRefreshLayout.setRefreshing(false);
                });
            }
        }
    }

    public static String runCommand(String command, int type) {
        if (type == 0) {
            return CommandRunner.executeCommandSync(command, true);
        } else if (type == 1) {
            return ShizukuHelper.executeCommandSync(command);
        }
        return "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && addWifiRunnable != null) {
            handler.removeCallbacks(addWifiRunnable);
        }
        if (wifiScanReceiver != null) {
            unregisterReceiver(wifiScanReceiver);
        }
    }
}

