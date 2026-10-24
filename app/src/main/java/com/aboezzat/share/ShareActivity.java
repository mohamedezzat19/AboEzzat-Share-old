package com.aboezzat.share;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    sendToTv(extractUrl(sharedText));
                    return;
                }
            }
        }
        finish();
    }

    private String extractUrl(String text) {
        String[] parts = text.split("\\s+");
        for (String item : parts) {
            if (item.startsWith("http://") || item.startsWith("https://")) {
                return item;
            }
        }
        return text;
    }

    private void sendToTv(String videoUrl) {
        SharedPreferences prefs = getSharedPreferences("AboEzzatPrefs", MODE_PRIVATE);
        // قراءة الـ IP المحفوظ في التطبيق أو استخدام الافتراضي للشاشة
        String tvIp = prefs.getString("tv_ip", "10.229.144.16");

        try {
            String targetUrl = "http://" + tvIp + ":1997/play?url=" + URLEncoder.encode(videoUrl, "UTF-8");

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(targetUrl)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(ShareActivity.this, "تعذر الاتصال بالشاشة! تأكد من تشغيل المشغل", Toast.LENGTH_LONG).show();
                        finish();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    runOnUiThread(() -> {
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
            Toast.makeText(this, "خطأ في الرابط!", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
