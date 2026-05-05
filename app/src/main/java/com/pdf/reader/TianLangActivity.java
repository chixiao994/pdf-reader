package com.pdf.reader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

public class TianLangActivity extends AppCompatActivity {

    private WebView webView;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int totalPages = 0;
    private String currentFileName = "未命名";

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
        settings.setAllowFileAccessFromFileURLs(true);

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
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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
        public void saveFile(String safeBase64, String fileName) {
            try {
                String base64 = URLDecoder.decode(safeBase64, "UTF-8");
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File file = new File(getExternalFilesDir(null), fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
                showToast("已保存: " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e("TianLang", "保存失败", e);
                showToast("保存失败: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == PICK_IMAGE) {
                handleImageResult(data);
            } else if (requestCode == PICK_PDF) {
                Uri uri = data.getData();
                if (uri != null) {
                    handlePdfUri(uri);
                }
            }
        }
    }

    private void handleImageResult(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        List<String> paths = new ArrayList<>();
        for (Uri uri : uris) {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                File tempFile = new File(getCacheDir(), "img_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) != -1) fos.write(buf, 0, len);
                }
                in.close();
                paths.add(tempFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e("TianLang", "图片读取失败", e);
            }
        }
        if (!paths.isEmpty()) {
            String json = new JSONArray(paths).toString();
            webView.evaluateJavascript(
                    String.format("javascript:onImageBatch('%s')", escapeJson(json)),
                    null
            );
        }
    }

    private void handlePdfUri(Uri uri) {
        try {
            closePdf();
            fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            pdfRenderer = new PdfRenderer(fileDescriptor);
            totalPages = pdfRenderer.getPageCount();
            currentFileName = getFileName(uri);
            renderAllPagesInBackground();
        } catch (Exception e) {
            Log.e("TianLang", "PDF打开失败", e);
            showToastSafe("PDF打开失败");
        }
    }

    private void renderAllPagesInBackground() {
        new AsyncTask<Void, Integer, List<String>>() {
            @Override
            protected void onPreExecute() {
                webView.evaluateJavascript(
                        "javascript:if(window.onPdfRenderProgress) onPdfRenderProgress(0, " + totalPages + ")",
                        null);
            }

            @Override
            protected List<String> doInBackground(Void... voids) {
                List<String> paths = new ArrayList<>();
                for (int i = 0; i < totalPages; i++) {
                    try {
                        PdfRenderer.Page page = pdfRenderer.openPage(i);
                        int w = page.getWidth(), h = page.getHeight();
                        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();

                        File tempFile = new File(getCacheDir(), "pdf_page_" + i + ".png");
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        bitmap.recycle();
                        paths.add(tempFile.getAbsolutePath());
                        publishProgress(i + 1);
                    } catch (Exception e) {
                        Log.e("TianLang", "渲染第" + i + "页失败", e);
                    }
                }
                return paths;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                int progress = values[0];
                webView.evaluateJavascript(
                        "javascript:if(window.onPdfRenderProgress) onPdfRenderProgress(" + progress + ", " + totalPages + ")",
                        null);
            }

            @Override
            protected void onPostExecute(List<String> paths) {
                webView.evaluateJavascript("javascript:hidePdfRenderProgress()", null);
                if (!paths.isEmpty()) {
                    String json = new JSONArray(paths).toString();
                    webView.evaluateJavascript(
                            String.format("javascript:onPdfBatch('%s', '%s', %d)",
                                    escapeJson(json),
                                    escapeJsString(currentFileName),
                                    paths.size()),
                            null
                    );
                } else {
                    showToastSafe("PDF渲染失败");
                }
            }
        }.execute();
    }

    private String escapeJson(String json) {
        if (json == null) return "";
        return json.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
            } catch (Exception e) { /* ignore */ }
        }
        return name != null ? name : "file.txt";
    }

    private void closePdf() {
        if (pdfRenderer != null) {
            pdfRenderer.close();
            pdfRenderer = null;
        }
        if (fileDescriptor != null) {
            try { fileDescriptor.close(); } catch (Exception e) { /* ignore */ }
            fileDescriptor = null;
        }
    }

    private void showToastSafe(final String msg) {
        runOnUiThread(() -> Toast.makeText(TianLangActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
        // 清理临时图片
        File[] files = getCacheDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("img_") || f.getName().startsWith("pdf_page_")) {
                    f.delete();
                }
            }
        }
    }
}
