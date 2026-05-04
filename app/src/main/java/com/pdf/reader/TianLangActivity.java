package com.pdf.reader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TianLangActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PICK_IMAGE = 1;
    private static final int PICK_PDF = 2;

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

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        webView.loadUrl("file:///android_asset/tianlang.html");
    }

    public class NativeBridge {

        @JavascriptInterface
        public void pickFile(String type) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            if ("image".equals(type)) {
                intent.setType("image/*");
                startActivityForResult(intent, PICK_IMAGE);
            } else if ("pdf".equals(type)) {
                intent.setType("application/pdf");
                startActivityForResult(intent, PICK_PDF);
            }
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(TianLangActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void saveFile(String base64, String fileName) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File file = new File(getExternalFilesDir(null), fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
                showToast("已保存: " + file.getAbsolutePath());
            } catch (Exception e) {
                showToast("保存失败: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                if (requestCode == PICK_IMAGE) {
                    // 图片仍通过 Base64 传递（体积小，安全）
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = inputStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    inputStream.close();
                    String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    String fileName = getFileName(uri);
                    webView.evaluateJavascript("javascript:loadImage('" + base64 + "', '" + fileName + "')", null);
                } else if (requestCode == PICK_PDF) {
                    // PDF 直接拷贝到缓存目录，传递文件路径（避免 Base64 内存问题）
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    File tempFile = new File(getCacheDir(), "temp_pdf_" + System.currentTimeMillis() + ".pdf");
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = inputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    inputStream.close();
                    String filePath = tempFile.getAbsolutePath();
                    String fileName = getFileName(uri);
                    webView.evaluateJavascript(
                        "javascript:loadPdfFromPath('" + filePath + "', '" + fileName + "')", null);
                }
            } catch (Exception e) {
                Toast.makeText(this, "文件读取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileName(Uri uri) {
        String name = null;
        if (uri.getScheme().equals("file")) {
            name = new File(uri.getPath()).getName();
        } else if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) name = cursor.getString(nameIndex);
                }
            } catch (Exception e) {}
        }
        return name != null ? name : "file";
    }
}
