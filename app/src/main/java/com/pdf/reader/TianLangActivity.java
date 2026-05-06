package com.pdf.reader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
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

    private static final int PICK_IMAGE = 1;
    private static final int PICK_PDF = 2;
    private static final int CREATE_FILE = 3;
    private static final int PICK_PROJECT = 4;   // 加载项目

    private final List<byte[]> pdfPagesData = new ArrayList<>();
    private String pdfOutputFileName = "output.pdf";

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

        @JavascriptInterface
        public void saveFile(String safeBase64, String fileName) {
            try {
                // 前端已不再编码，直接解码
                pendingSaveData = Base64.decode(safeBase64, Base64.DEFAULT);
                pendingSaveFileName = fileName;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(intent, CREATE_FILE);
            } catch (Exception e) {
                Log.e("TianLang", "保存准备失败", e);
                showToast("保存失败: " + e.getMessage());
            }
        }

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

        // ── PDF 生成 ──
        @JavascriptInterface
        public void beginPdf(int totalPages, String originalName) {
            pdfPagesData.clear();
            pdfOutputFileName = originalName;
            showToast("开始生成PDF，共 " + totalPages + " 页");
        }

        @JavascriptInterface
        public void addPageToPdf(String base64) {
            try {
                // 直接解码，不再使用 URLDecoder
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                if (data != null && data.length > 0) {
                    pdfPagesData.add(data);
                }
            } catch (Exception e) {
                Log.e("TianLang", "添加PDF页面失败", e);
            }
        }

        @JavascriptInterface
        public void finishPdf(String fileName) {
            if (pdfPagesData.isEmpty()) {
                showToast("没有页面可生成PDF");
                return;
            }
            showToast("PDF 生成中，请稍候…");

            new AsyncTask<Void, Void, File>() {
                @Override
                protected File doInBackground(Void... voids) {
                    try {
                        File tempFile = new File(getCacheDir(), fileName);
                        android.graphics.pdf.PdfDocument document = new android.graphics.pdf.PdfDocument();

                        for (int i = 0; i < pdfPagesData.size(); i++) {
                            byte[] pngData = pdfPagesData.get(i);
                            if (pngData == null) continue;

                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(pngData, 0, pngData.length, opts);
                            int maxW = 2000;
                            int scale = 1;
                            if (opts.outWidth > maxW) {
                                scale = Math.round((float) opts.outWidth / maxW);
                            }
                            opts.inJustDecodeBounds = false;
                            opts.inSampleSize = scale;
                            Bitmap bitmap = BitmapFactory.decodeByteArray(pngData, 0, pngData.length, opts);
                            if (bitmap == null) continue;

                            android.graphics.pdf.PdfDocument.PageInfo pageInfo =
                                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                                            bitmap.getWidth(), bitmap.getHeight(), i).create();
                            android.graphics.pdf.PdfDocument.Page page = document.startPage(pageInfo);
                            page.getCanvas().drawBitmap(bitmap, 0, 0, null);
                            document.finishPage(page);
                            bitmap.recycle();
                        }

                        FileOutputStream fos = new FileOutputStream(tempFile);
                        document.writeTo(fos);
                        document.close();
                        fos.close();
                        return tempFile;

                    } catch (Exception e) {
                        Log.e("TianLang", "PDF生成失败", e);
                        return null;
                    }
                }

                @Override
                protected void onPostExecute(File tempFile) {
                    if (tempFile == null) {
                        showToast("PDF 生成失败");
                        return;
                    }
                    try {
                        pendingSaveData = readFileToBytes(tempFile);
                        pendingSaveFileName = fileName;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/pdf");
                        intent.putExtra(Intent.EXTRA_TITLE, fileName);
                        startActivityForResult(intent, CREATE_FILE);
                        showToast("PDF已生成，请选择保存位置");
                    } catch (Exception e) {
                        showToast("准备保存失败: " + e.getMessage());
                    } finally {
                        pdfPagesData.clear();
                    }
                }
            }.execute();
        }
    }

    // ── Activity Result ──
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
            Log.e("TianLang", "写入失败", e);
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

    // ── PDF 导入 ──
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

    // ── 工具 ──
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

    private byte[] readFileToBytes(File file) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[4096];
        int len;
        while ((len = fis.read(buf)) != -1) baos.write(buf, 0, len);
        fis.close();
        return baos.toByteArray();
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
