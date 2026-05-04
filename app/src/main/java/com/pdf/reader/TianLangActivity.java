package com.pdf.reader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TianLangActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new NativeBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 可注入初始文件等
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.loadUrl("file:///android_asset/tianlang.html");
    }

    public class NativeBridge {
        // 文件选择：调用系统文件选择器，结果需通过 onActivityResult 回传 HTML
        @JavascriptInterface
        public void pickFile() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, 100);
        }

        // 保存 Base64 数据到文件
        @JavascriptInterface
        public void saveFile(String base64, String fileName) {
            try {
                byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                File file = new File(getExternalFilesDir(null), fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
                Toast.makeText(TianLangActivity.this, "已保存: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(TianLangActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
            }
        }

        // 如果需要加载 PDF 文件，由原生侧渲染页面后传数据给 HTML
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            // 可通过 JavaScript 将 uri 传给 HTML 处理
            webView.evaluateJavascript("javascript:receiveFileUri('" + uri.toString() + "')", null);
        }
    }
}
