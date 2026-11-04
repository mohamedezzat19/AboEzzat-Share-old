package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

public class ShareActivity extends Activity {

    private static final int RECEIVER_PORT = 8081;

    // عميل OkHttp يتجاوز أي بروكسي محلي نشط بالهاتف للتواصل مع الشاشة
    private final OkHttpClient localClient = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    // عميل OkHttp عادي للطلبات الخارجية (مثل tikwm)
    private final OkHttpClient internetClient = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean isCaptured = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.TRANSPARENT);
        ProgressBar progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.CENTER;
        rootLayout.addView(progressBar, params);
        setContentView(rootLayout);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                String extractedUrl = extractUrl(sharedText);
                if (extractedUrl != null) {
                    processUrl(extractedUrl);
                    return;
                }
            }
        }
        finish();
    }

    private String extractUrl(String text) {
        Pattern pattern = Pattern.compile("(https?://[\\w\\-\\.\\?\\=\\&\\%\\/\\#\\+]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private void processUrl(String url) {
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            dispatchMediaUrl(url);
            return;
        }

        if (url.contains("tiktok.com") && !url.contains("/live")) {
            Toast.makeText(this, "جاري استخراج تيك توك...", Toast.LENGTH_SHORT).show();
            extractTikTokVideo(url);
            return;
        }

        Toast.makeText(this, "جاري قنص وتشغيل الفيديو...", Toast.LENGTH_SHORT).show();
        startSniffing(url);
    }

    private void extractTikTokVideo(final String url) {
        try {
            String apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8");
            Request request = new Request.Builder().url(apiUrl).build();

            internetClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> startSniffing(url));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        if (json.optInt("code") == 0 && json.has("data")) {
                            JSONObject data = json.getJSONObject("data");
                            String play = data.optString("play");
                            if (!play.isEmpty()) {
                                if (!play.startsWith("http")) play = "https://www.tikwm.com" + play;
                                dispatchMediaUrl(play);
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                    mainHandler.post(() -> startSniffing(url));
                }
            });
        } catch (Exception e) {
            startSniffing(url);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startSniffing(String url) {
        isCaptured = false;
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMediaFound(String mediaUrl) {
                checkAndSendMedia(mediaUrl);
            }
        }, "MediaSniffer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                checkAndSendMedia(reqUrl);
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                String js = "(function() { " +
                        "   var vids = document.getElementsByTagName('video'); " +
                        "   for (var i = 0; i < vids.length; i++) { " +
                        "       try { vids[i].play(); } catch(e){} " +
                        "       var src = vids[i].currentSrc || vids[i].src; " +
                        "       if (src && src.startsWith('http')) { " +
                        "           window.MediaSniffer.onMediaFound(src); " +
                        "       } " +
                        "   } " +
                        "})();";
                view.evaluateJavascript(js, null);
            }
        });

        webView.loadUrl(url);

        mainHandler.postDelayed(() -> {
            if (!isCaptured) {
                Toast.makeText(ShareActivity.this, "تعذر قنص الفيديو تلقائياً", Toast.LENGTH_SHORT).show();
                destroyWebView();
                finish();
            }
        }, 18000);
    }

    private synchronized void checkAndSendMedia(String reqUrl) {
        if (isCaptured || reqUrl == null || reqUrl.isEmpty()) return;

        String lower = reqUrl.toLowerCase();

        if (lower.contains(".jpg") || lower.contains(".png") || lower.contains(".webp") ||
            lower.contains(".js") || lower.contains(".css") || lower.contains("rsrc.php") ||
            lower.contains("preview")) {
            return;
        }

        boolean isDirectVideo = (lower.contains(".mp4") && !lower.contains("bytestart=")) ||
                                lower.contains("video_playback") ||
                                lower.contains("&mime=video");

        if (isDirectVideo) {
            isCaptured = true;
            mainHandler.post(() -> {
                dispatchMediaUrl(reqUrl);
                destroyWebView();
            });
        }
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    private void dispatchMediaUrl(String mediaUrl) {
        SharedPreferences prefs = getSharedPreferences("AboEzzatPrefs", MODE_PRIVATE);
        String cachedIp = prefs.getString("last_tv_ip", "").trim();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!cachedIp.isEmpty() && isReceiverAlive(cachedIp, RECEIVER_PORT, 250)) {
                sendToTv(cachedIp, mediaUrl);
                return;
            }

            mainHandler.post(() -> Toast.makeText(this, "جاري فحص الشبكة واصطياد الريسيفر...", Toast.LENGTH_SHORT).show());
            discoverReceiverAndSend(mediaUrl);
        });
    }

    // فحص المنفذ بدون المرور بالبروكسي نهائياً
    private boolean isReceiverAlive(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void discoverReceiverAndSend(String mediaUrl) {
        String subnet = getRealWifiSubnet();
        if (subnet == null) {
            mainHandler.post(() -> {
                Toast.makeText(this, "تعذر قراءة عنوان شبكة الـ Wi-Fi الحقيقي!", Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(40);
        AtomicBoolean found = new AtomicBoolean(false);

        for (int i = 1; i <= 254; i++) {
            final String host = subnet + "." + i;
            pool.execute(() -> {
                if (found.get()) return;

                if (isReceiverAlive(host, RECEIVER_PORT, 250)) {
                    if (found.compareAndSet(false, true)) {
                        pool.shutdownNow();

                        getSharedPreferences("AboEzzatPrefs", MODE_PRIVATE)
                                .edit()
                                .putString("last_tv_ip", host)
                                .apply();

                        sendToTv(host, mediaUrl);
                    }
                }
            });
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                pool.awaitTermination(3500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            if (!found.get()) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "لم يتم العثور على ريسيفر أبو عزت في الشبكة!", Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    // استخراج عنوان الشبكة الحقيقي متجاوزاً أي بروكسي أو VPN
    private String getRealWifiSubnet() {
        try {
            // المحاولة الأولى: قراءة واجهة wlan0 الحقيقية مباشرة
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().toLowerCase().contains("wlan")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (ip != null && !ip.startsWith("127.")) {
                                return ip.substring(0, ip.lastIndexOf('.'));
                            }
                        }
                    }
                }
            }

            // المحاولة الثانية: عبر ConnectivityManager المخصص للـ Wi-Fi
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(network);
                        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            LinkProperties lp = cm.getLinkProperties(network);
                            if (lp != null) {
                                for (LinkAddress linkAddr : lp.getLinkAddresses()) {
                                    InetAddress addr = linkAddr.getAddress();
                                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                        String ip = addr.getHostAddress();
                                        if (ip != null) {
                                            return ip.substring(0, ip.lastIndexOf('.'));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // المحاولة الثالثة: عبر WifiManager القياسي
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                int ipInt = info.getIpAddress();
                if (ipInt != 0) {
                    return String.format("%d.%d.%d",
                            (ipInt & 0xff),
                            (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void sendToTv(String ip, String mediaUrl) {
        try {
            String fullUrl = "http://" + ip + ":" + RECEIVER_PORT + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8");
            Request request = new Request.Builder().url(fullUrl).build();

            localClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> {
                        Toast.makeText(ShareActivity.this, "تعذر إرسال البث للشاشة", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    mainHandler.post(() -> {
                        if (response.isSuccessful()) {
                            Toast.makeText(ShareActivity.this, "تم الإرسال والتشغيل بنجاح ✓", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ShareActivity.this, "الشاشة استقبلت لكن تعذر التشغيل", Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    });
                }
            });
        } catch (Exception e) {
            mainHandler.post(this::finish);
        }
    }

    @Override
    protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }
}
