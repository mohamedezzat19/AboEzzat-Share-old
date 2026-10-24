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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
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

    private SharedPreferences prefs;
    private View tabHome, tabBrowser, tabSettings;
    private TextView txtNavHome, txtNavBrowser, txtNavSettings, tvConnectedIpDisplay;
    private EditText etTvIpSettings, etTvPortSettings;

    private WebView mainWebView;
    private EditText etBrowserUrl;
    private ProgressBar browserProgress;

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

        initViews();
        setupNavigation();
        setupHomeActions();
        setupBrowser();
        setupSettings();
        startLocalServer();
    }

    private void initViews() {
        tabHome = findViewById(R.id.tabHome);
        tabBrowser = findViewById(R.id.tabBrowser);
        tabSettings = findViewById(R.id.tabSettings);

        txtNavHome = findViewById(R.id.txtNavHome);
        txtNavBrowser = findViewById(R.id.txtNavBrowser);
        txtNavSettings = findViewById(R.id.txtNavSettings);

        tvConnectedIpDisplay = findViewById(R.id.tvConnectedIpDisplay);
        etTvIpSettings = findViewById(R.id.etTvIpSettings);
        etTvPortSettings = findViewById(R.id.etTvPortSettings);

        updateIpDisplay();
    }

    private void updateIpDisplay() {
        String savedIp = prefs.getString("tv_ip", "10.229.144.16");
        String savedPort = prefs.getString("tv_port", "1997");
        tvConnectedIpDisplay.setText("متصل بالشاشة: " + savedIp + ":" + savedPort);
        etTvIpSettings.setText(savedIp);
        etTvPortSettings.setText(savedPort);
    }

    private void setupNavigation() {
        findViewById(R.id.navHome).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.navBrowser).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.navSettings).setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int index) {
        hideKeyboard();
        tabHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        tabBrowser.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        tabSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        txtNavHome.setTextColor(index == 0 ? 0xFF38EF7D : 0xFF667085);
        txtNavBrowser.setTextColor(index == 1 ? 0xFF38EF7D : 0xFF667085);
        txtNavSettings.setTextColor(index == 2 ? 0xFF38EF7D : 0xFF667085);
    }

    private void setupHomeActions() {
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

    private void setupBrowser() {
        mainWebView = findViewById(R.id.mainWebView);
        etBrowserUrl = findViewById(R.id.etBrowserUrl);
        browserProgress = findViewById(R.id.browserProgress);
        Button btnBrowserHome = findViewById(R.id.btnBrowserHome);
        Button btnBrowserGo = findViewById(R.id.btnBrowserGo);

        WebSettings ws = mainWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);

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
        });

        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.endsWith(".mp4") || url.endsWith(".m3u8") || url.contains(".mp4?")) {
                    sendStreamUrlToTv(url);
                    Toast.makeText(MainActivity.this, "تم كشف رابط الفيديو وإرساله للشاشة!", Toast.LENGTH_SHORT).show();
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                etBrowserUrl.setText(url);
            }
        });

        btnBrowserHome.setOnClickListener(v -> switchTab(0));
        btnBrowserGo.setOnClickListener(v -> loadUrlFromInput());

        etBrowserUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromInput();
                return true;
            }
            return false;
        });

        mainWebView.loadUrl("https://www.google.com");
    }

    private void loadUrlFromInput() {
        hideKeyboard();
        String url = etBrowserUrl.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://www.google.com/search?q=" + url;
        }
        mainWebView.loadUrl(url);
    }

    private void setupSettings() {
        Button btnSave = findViewById(R.id.btnSaveSettings);

        btnSave.setOnClickListener(v -> {
            hideKeyboard();
            etTvIpSettings.clearFocus();
            etTvPortSettings.clearFocus();

            String ip = etTvIpSettings.getText().toString().trim();
            String port = etTvPortSettings.getText().toString().trim();

            if (ip.isEmpty()) ip = "10.229.144.16";
            if (port.isEmpty()) port = "1997";

            prefs.edit().putString("tv_ip", ip).putString("tv_port", port).apply();
            updateIpDisplay();

            Toast.makeText(MainActivity.this, "تم حفظ الإعدادات بنجاح ✓", Toast.LENGTH_SHORT).show();
            switchTab(0);
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
        String tvIp = prefs.getString("tv_ip", "10.229.144.16");
        String port = prefs.getString("tv_port", "1997");
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
        String tvIp = prefs.getString("tv_ip", "10.229.144.16");
        String port = prefs.getString("tv_port", "1997");

        try {
            String url = "http://" + tvIp + ":" + port + "/play?url=" + URLEncoder.encode(videoUrl, "UTF-8");
            Request req = new Request.Builder().url(url).build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر الاتصال بالشاشة!", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تم الإرسال والتشغيل ✓", Toast.LENGTH_SHORT).show());
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
        if (tabBrowser.getVisibility() == View.VISIBLE) {
            if (mainWebView.canGoBack()) {
                mainWebView.goBack();
            } else {
                switchTab(0);
            }
        } else if (tabSettings.getVisibility() == View.VISIBLE) {
            switchTab(0);
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
