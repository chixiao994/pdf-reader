package com.pdf.reader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

public class TianLangActivity extends AppCompatActivity {

    private WebView webView;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int totalPages = 0;
    private String currentFileName = "未命名";

    private byte[] pendingSaveData;
    private String pendingSaveFileName;
    private String tempPdfForSave = null;  // 原生导出的临时文件路径

    private static final int PICK_IMAGE = 1;
    private static final int PICK_PDF = 2;
    private static final int CREATE_FILE = 3;
    private static final int PICK_PROJECT = 4;
    private static final int CREATE_PDF_FILE = 5;   // 原生 PDF 保存

    private final List<byte[]> pdfPagesData = new ArrayList<>();   // 保留，但原生导出不再使用

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
            } else if ("project".equals(type)) {
                intent.setType("application/json");
                startActivityForResult(intent, PICK_PROJECT);
            }
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(TianLangActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        // 保存单张图片（保留）
        @JavascriptInterface
        public void saveFile(String base64, String fileName) {
            try {
                pendingSaveData = Base64.decode(base64, Base64.DEFAULT);
                pendingSaveFileName = fileName;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(intent, CREATE_FILE);
            } catch (Exception e) {
                showToast("保存失败: " + e.getMessage());
            }
        }

        // 项目保存/加载
        @JavascriptInterface
        public void saveProject(String safeJson) {
            try {
                String json = URLDecoder.decode(safeJson, "UTF-8");
                pendingSaveData = json.getBytes("UTF-8");
                String name = "天朗项目_" + System.currentTimeMillis() + ".tianlang";
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, CREATE_FILE);
            } catch (Exception e) {
                showToast("保存项目失败: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void pickProject() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/json");
            startActivityForResult(intent, PICK_PROJECT);
        }

        // ─── 原生 PDF 导出（新） ───
        @JavascriptInterface
        public void beginNativeExport(String cropInfoJson, String outputName) {
            try {
                final JSONArray array = new JSONArray(cropInfoJson);
                showToast("开始原生导出，保持原始分辨率...");

                new AsyncTask<Void, Void, File>() {
                    @Override
                    protected File doInBackground(Void... voids) {
                        File tempFile = new File(getCacheDir(), outputName + "_temp.pdf");
                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(tempFile);
                            android.graphics.pdf.PdfDocument document = new android.graphics.pdf.PdfDocument();

                            for (int i = 0; i < array.length(); i++) {
                                JSONObject obj = array.getJSONObject(i);
                                int index = obj.getInt("index");
                                int left = obj.getInt("left");
                                int right = obj.getInt("right");
                                int bind = obj.optInt("bind", -1);
                                // 可选模式，但裁剪逻辑已包含所有情况
                                // String mode = obj.optString("mode");

                                File imgFile = new File(getCacheDir(), "pdf_page_" + index + ".png");
                                if (!imgFile.exists()) {
                                    Log.e("TianLang", "图片不存在: " + imgFile.getPath());
                                    continue;
                                }

                                Bitmap src = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                                if (src == null) {
                                    Log.e("TianLang", "解码失败: " + imgFile.getPath());
                                    continue;
                                }

                                // 裁剪并添加页面
                                if (bind > 0 && bind > left && bind < right) {
                                    // 先右半页，后左半页
                                    Bitmap rightPart = Bitmap.createBitmap(src, bind, 0, right - bind, src.getHeight());
                                    Bitmap leftPart = Bitmap.createBitmap(src, left, 0, bind - left, src.getHeight());
                                    addBitmapToPdf(document, rightPart);
                                    addBitmapToPdf(document, leftPart);
                                    rightPart.recycle();
                                    leftPart.recycle();
                                } else {
                                    Bitmap crop = Bitmap.createBitmap(src, left, 0, right - left, src.getHeight());
                                    addBitmapToPdf(document, crop);
                                    crop.recycle();
                                }
                                src.recycle();
                            }

                            document.writeTo(fos);
                            document.close();
                            return tempFile;
                        } catch (Exception e) {
                            Log.e("TianLang", "原生导出失败", e);
                            if (tempFile.exists()) tempFile.delete();
                            return null;
                        } finally {
                            if (fos != null) {
                                try { fos.close(); } catch (Exception ignored) {}
                            }
                        }
                    }

                    @Override
                    protected void onPostExecute(File pdfFile) {
                        if (pdfFile == null || !pdfFile.exists()) {
                            showToast("PDF 生成失败，请检查日志");
                            return;
                        }
                        // 启动系统文件选择器
                        tempPdfForSave = pdfFile.getAbsolutePath();
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/pdf");
                        intent.putExtra(Intent.EXTRA_TITLE, outputName);
                        startActivityForResult(intent, CREATE_PDF_FILE);
                    }
                }.execute();

            } catch (Exception e) {
                showToast("参数解析错误: " + e.getMessage());
            }
        }

        private void addBitmapToPdf(android.graphics.pdf.PdfDocument doc, Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled()) return;
            android.graphics.pdf.PdfDocument.PageInfo info =
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            bitmap.getWidth(), bitmap.getHeight(), doc.getPages().size()).create();
            android.graphics.pdf.PdfDocument.Page page = doc.startPage(info);
            page.getCanvas().drawBitmap(bitmap, 0, 0, null);
            doc.finishPage(page);
        }

        // 保留旧接口兼容，但不再使用（可空实现）
        @JavascriptInterface public void beginPdf(int totalPages, String originalName) {}
        @JavascriptInterface public void addPageToPdf(String base64) {}
        @JavascriptInterface public void finishPdf(String fileName) {}
    }

    // ── 文件复制与保存 ──
    private void copyTempPdfToUri(String tempPath, Uri targetUri) {
        try {
            FileInputStream fis = new FileInputStream(tempPath);
            OutputStream os = getContentResolver().openOutputStream(targetUri);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
            fis.close();
            os.close();
            new File(tempPath).delete();
            showToastSafe("PDF 已保存");
        } catch (Exception e) {
            showToastSafe("保存失败: " + e.getMessage());
        }
    }

    // ── ActivityResult ──
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;

            if (requestCode == PICK_IMAGE) {
                handleImageResult(data);
            } else if (requestCode == PICK_PDF) {
                handlePdfUri(uri);
            } else if (requestCode == CREATE_FILE) {
                if (pendingSaveData != null) {
                    writeDataToUri(uri, pendingSaveData, pendingSaveFileName);
                    pendingSaveData = null;
                    pendingSaveFileName = null;
                }
            } else if (requestCode == PICK_PROJECT) {
                loadProjectFromUri(uri);
            } else if (requestCode == CREATE_PDF_FILE) {
                if (tempPdfForSave != null) {
                    copyTempPdfToUri(tempPdfForSave, uri);
                    tempPdfForSave = null;
                }
            }
        }
    }

    private void writeDataToUri(Uri uri, byte[] data, String fileName) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(data);
                os.close();
                showToastSafe("已保存: " + fileName);
            } else {
                showToastSafe("无法写入文件");
            }
        } catch (Exception e) {
            showToastSafe("保存失败: " + e.getMessage());
        }
    }

    // ── 图片导入 ──
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

    // ── PDF 导入并渲染所有页面为 PNG ──
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
                final int MAX_DIM = 3000; // 限制长边最大尺寸，避免 OOM

                for (int i = 0; i < totalPages; i++) {
                    try {
                        PdfRenderer.Page page = pdfRenderer.openPage(i);
                        int w = page.getWidth();
                        int h = page.getHeight();

                        float scale = 1.0f;
                        if (Math.max(w, h) > MAX_DIM) {
                            scale = (float) MAX_DIM / Math.max(w, h);
                        }
                        int newW = Math.round(w * scale);
                        int newH = Math.round(h * scale);

                        Bitmap bitmap = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888);
                        Matrix matrix = new Matrix();
                        matrix.postScale(scale, scale);
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();

                        if (!isBitmapValid(bitmap)) {
                            bitmap.recycle();
                            Log.e("TianLang", "第" + (i+1) + "页渲染无效（可能JPEG2000），已跳过");
                            continue;
                        }

                        File tempFile = new File(getCacheDir(), "pdf_page_" + i + ".png");
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        bitmap.recycle();

                        // 校验 PNG 有效性
                        Bitmap testBmp = BitmapFactory.decodeFile(tempFile.getAbsolutePath());
                        if (testBmp == null) {
                            tempFile.delete();
                            Log.e("TianLang", "第" + (i+1) + "页PNG写入失败，跳过");
                            continue;
                        } else {
                            testBmp.recycle();
                        }

                        paths.add(tempFile.getAbsolutePath());
                        publishProgress(i + 1);
                    } catch (Exception e) {
                        Log.e("TianLang", "渲染第" + (i+1) + "页异常: " + e.getMessage());
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
                    showToastSafe("PDF渲染失败（所有页均无效）");
                }
            }
        }.execute();
    }

    // Bitmap 有效性简单检测
    private boolean isBitmapValid(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() < 5 || bitmap.getHeight() < 5) return false;
        int[] colors = new int[5];
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        colors[0] = bitmap.getPixel(0, 0);
        colors[1] = bitmap.getPixel(w-1, 0);
        colors[2] = bitmap.getPixel(0, h-1);
        colors[3] = bitmap.getPixel(w-1, h-1);
        colors[4] = bitmap.getPixel(w/2, h/2);
        for (int i = 1; i < colors.length; i++) {
            if (colors[i] != colors[0]) return true;
        }
        return false;
    }

    // ── 项目加载 ──
    private void loadProjectFromUri(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) baos.write(buf, 0, len);
            in.close();
            String json = new String(baos.toByteArray(), "UTF-8");
            webView.evaluateJavascript(
                    "javascript:onProjectLoaded('" + escapeJsString(json) + "')",
                    null
            );
        } catch (Exception e) {
            showToastSafe("读取项目文件失败: " + e.getMessage());
        }
    }

    // ── 工具方法 ──
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
        } else {
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
