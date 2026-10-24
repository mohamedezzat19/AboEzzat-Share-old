package com.aboezzat.share;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends Activity {

    private static final int REQ_PICK_VIDEO = 101;
    private static final int REQ_PICK_IMAGE = 102;
    private static final int REQ_PICK_AUDIO = 103;

    private DrawerLayout drawerLayout;
    private SharedPreferences prefs;

    private WebView mainWebView;
    private EditText etBrowserUrl;
    private ProgressBar browserProgress;

    private View viewMediaFiles, viewSettings;
    private TextView tvDrawerStatus;
    private EditText etTvIpSettings, etTvPortSettings;

    private String lastDetectedMediaUrl = "";
    private String currentDetectedIp = "";
    private String currentDetectedPort = "1997";

    // محاكاة موقع مصر الجغرافي لتخطي الحظر
    private static final String EGYPT_LOCATION_JS =
            "navigator.geolocation.getCurrentPosition = function(success, error, options) {" +
            "   var position = {" +
            "       coords: {" +
            "           latitude: 30.044426," +
            "           longitude: 31.235712," +
            "           accuracy: 10," +
            "           altitude: null," +
            "           altitudeAccuracy: null," +
            "           heading: null," +
            "           speed: null" +
            "       }," +
            "       timestamp: new Date().getTime()" +
            "   };" +
            "   success(position);" +
            "};";

    private LocalMediaServer localServer;
    private File currentServedFile = null;
    private static final int LOCAL_SERVER_PORT = 8899;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AboEzzatPrefs", MODE_PRIVATE);
        currentDetectedIp = prefs.getString("tv_ip", "");
        currentDetectedPort = prefs.getString("tv_port", "1997");

        initViews();
        setupDrawer();
        setupBrowser();
        setupMediaAndRemote();
        setupSettings();
        startLocalServer();

        autoDiscoverTv();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        viewMediaFiles = findViewById(R.id.viewMediaFiles);
        viewSettings = findViewById(R.id.viewSettings);
        tvDrawerStatus = findViewById(R.id.tvDrawerStatus);
        etTvIpSettings = findViewById(R.id.etTvIpSettings);
        etTvPortSettings = findViewById(R.id.etTvPortSettings);

        updateStatusText("جاري البحث التلقائي عن الشاشة...");
    }

    private void updateStatusText(String text) {
        runOnUiThread(() -> {
            tvDrawerStatus.setText(text);
            if (!currentDetectedIp.isEmpty()) {
                etTvIpSettings.setText(currentDetectedIp);
                etTvPortSettings.setText(currentDetectedPort);
            }
        });
    }

    private void autoDiscoverTv() {
        new Thread(() -> {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                String myIp = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                if (myIp == null || myIp.equals("0.0.0.0")) return;

                String subnet = myIp.substring(0, myIp.lastIndexOf(".") + 1);
                int[] portsToTest = {1997, 8080};

                ExecutorService executor = Executors.newFixedThreadPool(25);

                for (int i = 1; i < 255; i++) {
                    final String host = subnet + i;
                    for (int port : portsToTest) {
                        executor.execute(() -> {
                            try {
                                Socket socket = new Socket();
                                socket.connect(new InetSocketAddress(host, port), 250);
                                socket.close();

                                currentDetectedIp = host;
                                currentDetectedPort = String.valueOf(port);
                                prefs.edit().putString("tv_ip", host).putString("tv_port", currentDetectedPort).apply();

                                runOnUiThread(() -> {
                                    updateStatusText("الشاشة: " + currentDetectedIp + ":" + currentDetectedPort + " ✓");
                                    Toast.makeText(MainActivity.this, "تم التقاط الشاشة تلقائياً: " + currentDetectedIp, Toast.LENGTH_SHORT).show();
                                });
                            } catch (Exception ignored) {}
                        });
                    }
                }
                executor.shutdown();
            } catch (Exception ignored) {}
        }).start();
    }

    private void setupDrawer() {
        findViewById(R.id.btnMenu).setOnClickListener(v -> {
            hideKeyboard();
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        findViewById(R.id.menuBrowser).setOnClickListener(v -> {
            showView(0);
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        findViewById(R.id.menuMediaFiles).setOnClickListener(v -> {
            showView(1);
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        findViewById(R.id.menuYoutube).setOnClickListener(v -> {
            showView(0);
            mainWebView.loadUrl("https://m.youtube.com");
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        findViewById(R.id.menuTiktok).setOnClickListener(v -> {
            showView(0);
            mainWebView.loadUrl("https://www.tiktok.com");
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        findViewById(R.id.menuSettings).setOnClickListener(v -> {
            showView(2);
            drawerLayout.closeDrawer(GravityCompat.START);
        });
    }

    private void showView(int index) {
        hideKeyboard();
        mainWebView.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewMediaFiles.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
    }

    private void setupBrowser() {
        mainWebView = findViewById(R.id.mainWebView);
        etBrowserUrl = findViewById(R.id.etBrowserUrl);
        browserProgress = findViewById(R.id.browserProgress);
        Button btnRefresh = findViewById(R.id.btnRefresh);
        Button btnCast = findViewById(R.id.btnCast);

        WebSettings ws = mainWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        mainWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    browserProgress.setVisibility(View.VISIBLE);
                    browserProgress.setProgress(newProgress);
                } else {
                    browserProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(EGYPT_LOCATION_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.endsWith(".mp4") || url.endsWith(".m3u8") || url.contains(".mp4?")) {
                    lastDetectedMediaUrl = url;
                    sendStreamUrlToTv(url);
                    Toast.makeText(MainActivity.this, "تم كشف رابط الفيديو وإرساله للشاشة!", Toast.LENGTH_SHORT).show();
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(EGYPT_LOCATION_JS, null);
                etBrowserUrl.setText(url);
            }
        });

        btnRefresh.setOnClickListener(v -> mainWebView.reload());

        btnCast.setOnClickListener(v -> {
            if (!lastDetectedMediaUrl.isEmpty()) {
                sendStreamUrlToTv(lastDetectedMediaUrl);
            } else {
                String currentUrl = mainWebView.getUrl();
                if (currentUrl != null) {
                    sendStreamUrlToTv(currentUrl);
                } else {
                    Toast.makeText(this, "لا يوجد فيديو نشط للبث!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        etBrowserUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromInput();
                return true;
            }
            return false;
        });

        mainWebView.loadUrl("https://www.google.com.eg");
    }

    private void loadUrlFromInput() {
        hideKeyboard();
        String url = etBrowserUrl.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://www.google.com.eg/search?q=" + url;
        }
        mainWebView.loadUrl(url);
    }

    private void setupMediaAndRemote() {
        findViewById(R.id.btnPickVideo).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            startActivityForResult(intent, REQ_PICK_VIDEO);
        });

        findViewById(R.id.btnPickImage).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        });

        findViewById(R.id.btnPickAudio).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            startActivityForResult(intent, REQ_PICK_AUDIO);
        });

        findViewById(R.id.btnVolUp).setOnClickListener(v -> sendControlToTv("vol_up"));
        findViewById(R.id.btnVolDown).setOnClickListener(v -> sendControlToTv("vol_down"));
        findViewById(R.id.btnMute).setOnClickListener(v -> sendControlToTv("mute"));

        findViewById(R.id.btnPlayPause).setOnClickListener(v -> sendControlToTv("play_pause"));
        findViewById(R.id.btnForward).setOnClickListener(v -> sendControlToTv("forward"));
        findViewById(R.id.btnRewind).setOnClickListener(v -> sendControlToTv("rewind"));
        findViewById(R.id.btnStop).setOnClickListener(v -> sendControlToTv("stop"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQ_PICK_VIDEO) {
                sendLocalFileToTv(uri, "video");
            } else if (requestCode == REQ_PICK_IMAGE) {
                sendLocalFileToTv(uri, "image");
            } else if (requestCode == REQ_PICK_AUDIO) {
                sendLocalFileToTv(uri, "audio");
            }
        }
    }

    private void setupSettings() {
        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> {
            hideKeyboard();
            String ip = etTvIpSettings.getText().toString().trim();
            String port = etTvPortSettings.getText().toString().trim();

            if (!ip.isEmpty()) currentDetectedIp = ip;
            if (!port.isEmpty()) currentDetectedPort = port;

            prefs.edit().putString("tv_ip", currentDetectedIp).putString("tv_port", currentDetectedPort).apply();
            updateStatusText("الشاشة: " + currentDetectedIp + ":" + currentDetectedPort + " ✓");

            Toast.makeText(MainActivity.this, "تم الحفظ ✓", Toast.LENGTH_SHORT).show();
            showView(0);
        });
    }

    private void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void sendControlToTv(String action) {
        String tvIp = currentDetectedIp.isEmpty() ? prefs.getString("tv_ip", "10.229.144.16") : currentDetectedIp;
        String port = currentDetectedPort;
        String url = "http://" + tvIp + ":" + port + "/control?action=" + action;

        Request req = new Request.Builder().url(url).build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {}
        });
    }

    public void sendStreamUrlToTv(String videoUrl) {
        String tvIp = currentDetectedIp.isEmpty() ? prefs.getString("tv_ip", "10.229.144.16") : currentDetectedIp;
        String port = currentDetectedPort;

        try {
            String url = "http://" + tvIp + ":" + port + "/play?url=" + URLEncoder.encode(videoUrl, "UTF-8");
            Request req = new Request.Builder().url(url).build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر الاتصال بالشاشة! جاري فحص الشبكة...", Toast.LENGTH_SHORT).show());
                    autoDiscoverTv();
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تم الإرسال والتشغيل بنجاح ✓", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception ignored) {}
    }

    private void sendLocalFileToTv(Uri fileUri, String type) {
        try {
            File tempFile = new File(getCacheDir(), "shared_media");
            InputStream in = getContentResolver().openInputStream(fileUri);
            FileOutputStream out = new FileOutputStream(tempFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();

            currentServedFile = tempFile;

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            String phoneIp = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
            String mediaUrl = "http://" + phoneIp + ":" + LOCAL_SERVER_PORT + "/media";

            sendStreamUrlToTv(mediaUrl);

        } catch (Exception e) {
            Toast.makeText(this, "خطأ في قراءة الملف!", Toast.LENGTH_SHORT).show();
        }
    }

    private void startLocalServer() {
        try {
            localServer = new LocalMediaServer(LOCAL_SERVER_PORT);
            localServer.start();
        } catch (Exception ignored) {}
    }

    private class LocalMediaServer extends NanoHTTPD {
        public LocalMediaServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            if (currentServedFile != null && currentServedFile.exists()) {
                try {
                    return newFixedLengthResponse(Response.Status.OK, "video/mp4",
                            new java.io.FileInputStream(currentServedFile), currentServedFile.length());
                } catch (Exception ignored) {}
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (viewMediaFiles.getVisibility() == View.VISIBLE || viewSettings.getVisibility() == View.VISIBLE) {
            showView(0);
        } else if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (localServer != null) localServer.stop();
        super.onDestroy();
    }
}
