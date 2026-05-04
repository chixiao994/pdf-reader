package com.pdf.reader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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
import java.util.HashMap;
import java.util.Map;

public class TianLangActivity extends AppCompatActivity {

    private WebView webView;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int totalPages = 0;
    private int currentPage = 0;
    private String currentFileName = "未命名";
    private TianLangEngine.CropParams cropParams = new TianLangEngine.CropParams();
    private Map<Integer, TianLangEngine.CropParams> pageParams = new HashMap<>();

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
        public void goToPage(int pageNum) {
            if (pdfRenderer == null) return;
            if (pageNum < 0 || pageNum >= totalPages) return;
            currentPage = pageNum;
            renderAndSendPage();
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

        @JavascriptInterface
        public void savePageParams(int page, String paramsJson) {
            // 预留，可存储每页参数
        }

        @JavascriptInterface
        public void exportCroppedPdf() {
            if (pdfRenderer == null || totalPages == 0) {
                showToast("请先打开PDF");
                return;
            }
            new AsyncTask<Void, Integer, String>() {
                @Override
                protected String doInBackground(Void... voids) {
                    try {
                        android.graphics.pdf.PdfDocument out = new android.graphics.pdf.PdfDocument();
                        for (int i = 0; i < totalPages; i++) {
                            PdfRenderer.Page page = pdfRenderer.openPage(i);
                            Bitmap orig = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                            page.render(orig, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            page.close();

                            TianLangEngine.PageResult res = TianLangEngine.processPage(orig, cropParams);
                            Bitmap result = res.resultBitmap;

                            android.graphics.pdf.PdfDocument.PageInfo info =
                                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                                            result.getWidth(), result.getHeight(), i).create();
                            android.graphics.pdf.PdfDocument.Page outPage = out.startPage(info);
                            outPage.getCanvas().drawBitmap(result, 0, 0, null);
                            out.finishPage(outPage);

                            orig.recycle();
                            result.recycle();
                            publishProgress(i + 1);
                        }

                        File file = new File(getExternalFilesDir(null),
                                "tianlang_output_" + System.currentTimeMillis() + ".pdf");
                        out.writeTo(new FileOutputStream(file));
                        out.close();
                        return file.getAbsolutePath();
                    } catch (Exception e) {
                        return null;
                    }
                }

                @Override
                protected void onPostExecute(String path) {
                    if (path != null) {
                        showToast("PDF已保存: " + path);
                    } else {
                        showToast("导出失败");
                    }
                }
            }.execute();
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
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = inputStream.read(buffer)) != -1) baos.write(buffer, 0, len);
                    inputStream.close();
                    String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    String fileName = getFileName(uri);
                    webView.evaluateJavascript("javascript:loadImage('" + base64 + "', '" + fileName + "')", null);
                } else if (requestCode == PICK_PDF) {
                    // 关闭旧的文件描述符
                    if (pdfRenderer != null) pdfRenderer.close();
                    if (fileDescriptor != null) try { fileDescriptor.close(); } catch (Exception e) {}

                    fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
                    pdfRenderer = new PdfRenderer(fileDescriptor);
                    totalPages = pdfRenderer.getPageCount();
                    currentPage = 0;
                    currentFileName = getFileName(uri);

                    // 通知 HTML 总页数并显示第一页
                    webView.evaluateJavascript("javascript:onPdfOpened(" + totalPages + ", '" + currentFileName + "')", null);
                    renderAndSendPage();
                }
            } catch (Exception e) {
                Toast.makeText(this, "文件打开失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void renderAndSendPage() {
        if (pdfRenderer == null) return;
        try {
            PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
            int w = page.getWidth(), h = page.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            bitmap.recycle();

            webView.evaluateJavascript(
                "javascript:onPageRendered('" + base64 + "', " + currentPage + ", " + totalPages + ")", null);
        } catch (Exception e) {
            Toast.makeText(this, "渲染页面失败", Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pdfRenderer != null) pdfRenderer.close();
        if (fileDescriptor != null) try { fileDescriptor.close(); } catch (Exception e) {}
    }
}
