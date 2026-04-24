package com.pdf.reader;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // 视图组件
    private LinearLayout mainLayout, fileListLayout;
    private FrameLayout readerContainer;
    private ImageView pdfImageView;
    private TextView pageTextView;
    private Button nightModeBtn, halfPageBtn, pageModeBtn, prevBtn, nextBtn, openFileBtn, refreshBtn, rotateBtn, flipModeBtn;

    // PDF相关
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int currentPage = 0;
    private int totalPages = 0;
    private String currentFilePath;

    // 设置
    private boolean nightMode = false;
    private boolean halfPageMode = false;
    private boolean doublePageMode = false;
    private boolean leftPage = false;
    private boolean controlsVisible = true;
    private boolean isRotated = false;

    // 翻页效果
    private boolean flipPageMode = true;
    private boolean isFlipping = false;

    // 页面缓存
    private Bitmap prevPageCache;
    private Bitmap currentPageCache;
    private Bitmap nextPageCache;
    private int cachedPrevPage = -1;
    private int cachedCurrentPage = -1;
    private int cachedNextPage = -1;
    private boolean cacheInitialized = false;

    // 缩放相关
    private float scaleFactor = 1.0f;
    private float minScale = 0.5f;
    private float maxScale = 5.0f;
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private PointF startPoint = new PointF();
    private PointF midPoint = new PointF();
    private float oldDistance = 1f;
    private int mode = NONE;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private long lastClickTime = 0;
    private static final int DOUBLE_TAP_TIME_THRESHOLD = 300;

    // 触摸相关
    private float touchStartX, touchStartY;
    private long touchStartTime;
    private boolean isClickCandidate = false;
    private boolean isSwiping = false;
    private static final int CLICK_MAX_DISTANCE = 20;
    private static final int CLICK_MAX_TIME = 200;
    private static final int SWIPE_MIN_DISTANCE = 60;
    private static final float SWIPE_VS_SCROLL_RATIO = 1.5f;

    private float lastTapX, lastTapY;
    private static final int DOUBLE_TAP_MAX_DISTANCE = 50;

    // 存储
    private SharedPreferences prefs;
    private static final String LAST_OPENED_FILE = "last_opened_file";
    private static final String AUTO_OPEN_LAST_FILE = "auto_open_last_file";
    private static final String FIRST_RUN = "first_run";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;

    // 颜色
    private static final int DAY_MODE_BG = Color.parseColor("#FFF8F0");
    private static final int DAY_MODE_TEXT = Color.parseColor("#3E2723");
    private static final int NIGHT_MODE_BG = Color.parseColor("#1A1A1A");
    private static final int NIGHT_MODE_TEXT = Color.parseColor("#D7CCC8");
    private static final int ANCIENT_RED = Color.parseColor("#8B4513");
    private static final int ANCIENT_GOLD = Color.parseColor("#D4AF37");
    private static final int ANCIENT_BROWN = Color.parseColor("#5D4037");
    private static final int ANCIENT_BEIGE = Color.parseColor("#D7CCC8");
    private static final int ANCIENT_GREEN = Color.parseColor("#4E342E");
    private static final int ANCIENT_PAPER = Color.parseColor("#FFF8F0");
    private static final int DAY_STATUS_BAR_COLOR = Color.parseColor("#5D4037");
    private static final int NIGHT_STATUS_BAR_COLOR = Color.parseColor("#2C2C2C");

    private class FlipImageView extends ImageView {
        public FlipImageView(Context context) {
            super(context);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("pdf_reader", MODE_PRIVATE);
        loadSettings();
        boolean firstRun = prefs.getBoolean(FIRST_RUN, true);
        if (firstRun) {
            prefs.edit().putBoolean(FIRST_RUN, false).apply();
            createMainLayout();
            requestPermissionsOnFirstRun();
        } else {
            checkAutoOpenLastFile();
        }
    }

    // ==================== 核心缓存方法（修复旋转、半页、双页、闪退） ====================

    private void initPageCache() {
        if (pdfRenderer == null) return;
        if (doublePageMode) {
            cacheInitialized = false;
            return;
        }
        clearPageCache();
        int prevPage = Math.max(0, currentPage - 1);
        int nextPage = Math.min(totalPages - 1, currentPage + 1);
        cachePage(prevPage, true);
        cachePage(currentPage, false);
        cachePage(nextPage, false);
        cachedPrevPage = prevPage;
        cachedCurrentPage = currentPage;
        cachedNextPage = nextPage;
        cacheInitialized = true;
    }

    private void cachePage(int pageIndex, boolean isAdjacent) {
        if (pageIndex < 0 || pageIndex >= totalPages) return;
        if (doublePageMode) return;

        try {
            PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();

            int viewWidth = pdfImageView.getWidth();
            int viewHeight = pdfImageView.getHeight();
            if (viewWidth == 0 || viewHeight == 0) {
                viewWidth = getResources().getDisplayMetrics().widthPixels;
                viewHeight = getResources().getDisplayMetrics().heightPixels;
            }

            float targetW = isRotated ? pageHeight : pageWidth;
            float targetH = isRotated ? pageWidth : pageHeight;

            float scale = Math.min((float) viewWidth / targetW, (float) viewHeight / targetH);
            if (halfPageMode && pageIndex == currentPage) {
                scale = Math.min((float) viewWidth / (targetW / 2), (float) viewHeight / targetH);
            }

            int renderW = Math.max(1, (int) (pageWidth * scale * 2));
            int renderH = Math.max(1, (int) (pageHeight * scale * 2));
            Bitmap bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            int finalW = Math.max(1, (int) (pageWidth * scale));
            int finalH = Math.max(1, (int) (pageHeight * scale));
            bitmap = Bitmap.createScaledBitmap(bitmap, finalW, finalH, true);

            if (nightMode) bitmap = invertColors(bitmap);

            // 半页裁剪（在旋转之前进行）
            if (halfPageMode && pageIndex == currentPage) {
                if (leftPage) {
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, finalW / 2, finalH);
                } else {
                    bitmap = Bitmap.createBitmap(bitmap, finalW / 2, 0, finalW / 2, finalH);
                }
            }

            if (isRotated) bitmap = rotateBitmap90(bitmap);

            // 放入缓存（先回收旧图）
            if (pageIndex == currentPage) {
                if (currentPageCache != null && !currentPageCache.isRecycled()) currentPageCache.recycle();
                currentPageCache = bitmap;
            } else if (pageIndex == currentPage - 1) {
                if (prevPageCache != null && !prevPageCache.isRecycled()) prevPageCache.recycle();
                prevPageCache = bitmap;
            } else if (pageIndex == currentPage + 1) {
                if (nextPageCache != null && !nextPageCache.isRecycled()) nextPageCache.recycle();
                nextPageCache = bitmap;
            }
        } catch (Exception e) {
            Log.e("PDF_DEBUG", "缓存页面失败: " + pageIndex, e);
        }
    }

    private void clearPageCache() {
        if (prevPageCache != null && !prevPageCache.isRecycled()) prevPageCache.recycle();
        if (currentPageCache != null && !currentPageCache.isRecycled()) currentPageCache.recycle();
        if (nextPageCache != null && !nextPageCache.isRecycled()) nextPageCache.recycle();
        prevPageCache = currentPageCache = nextPageCache = null;
        cachedPrevPage = cachedCurrentPage = cachedNextPage = -1;
        cacheInitialized = false;
    }

    private void updatePageCacheAfterFlip(boolean forward) {
        if (!cacheInitialized || !flipPageMode || doublePageMode) return;

        if (forward) {
            if (prevPageCache != null && !prevPageCache.isRecycled()) prevPageCache.recycle();
            prevPageCache = currentPageCache;
            currentPageCache = nextPageCache;
            nextPageCache = null;
            cachedPrevPage = cachedCurrentPage;
            cachedCurrentPage = cachedNextPage;
            int newNextPage = Math.min(totalPages - 1, currentPage + 1);
            if (newNextPage != cachedCurrentPage && newNextPage < totalPages) {
                cachePage(newNextPage, false);
                cachedNextPage = newNextPage;
            }
        } else {
            if (nextPageCache != null && !nextPageCache.isRecycled()) nextPageCache.recycle();
            nextPageCache = currentPageCache;
            currentPageCache = prevPageCache;
            prevPageCache = null;
            cachedNextPage = cachedCurrentPage;
            cachedCurrentPage = cachedPrevPage;
            int newPrevPage = Math.max(0, currentPage - 1);
            if (newPrevPage != cachedCurrentPage && newPrevPage >= 0) {
                cachePage(newPrevPage, false);
                cachedPrevPage = newPrevPage;
            }
        }

        if (currentPageCache != null) {
            pdfImageView.setImageBitmap(currentPageCache);
            centerImage();
            pdfImageView.invalidate();
        }
    }

    // ==================== 翻页逻辑（双页直接走普通模式） ====================

    private void goToPrevPage() {
        if (doublePageMode) {
            originalGoToPrevPage();
            return;
        }
        if (flipPageMode && cacheInitialized) {
            performCachedFlip(false);
        } else {
            originalGoToPrevPage();
        }
    }

    private void goToNextPage() {
        if (doublePageMode) {
            originalGoToNextPage();
            return;
        }
        if (flipPageMode && cacheInitialized) {
            performCachedFlip(true);
        } else {
            originalGoToNextPage();
        }
    }

    private void performCachedFlip(boolean forward) {
        if (isFlipping) return;
        if (forward) {
            if (currentPage >= totalPages - 1) { Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show(); return; }
        } else {
            if (currentPage <= 0) { Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show(); return; }
        }

        isFlipping = true;

        if (forward && nextPageCache != null) {
            pdfImageView.setImageBitmap(nextPageCache);
            centerImage(); pdfImageView.invalidate();
            currentPage++; if (halfPageMode) leftPage = true;
            updatePageNumberText();
            new android.os.Handler().postDelayed(() -> {
                updatePageCacheAfterFlip(true);
                saveReadingPosition();
                isFlipping = false;
            }, 50);
        } else if (!forward && prevPageCache != null) {
            pdfImageView.setImageBitmap(prevPageCache);
            centerImage(); pdfImageView.invalidate();
            currentPage--; if (halfPageMode) leftPage = false;
            updatePageNumberText();
            new android.os.Handler().postDelayed(() -> {
                updatePageCacheAfterFlip(false);
                saveReadingPosition();
                isFlipping = false;
            }, 50);
        } else {
            if (forward) originalGoToNextPage(); else originalGoToPrevPage();
            isFlipping = false;
        }
    }

    private void originalGoToPrevPage() {
        resetScaleIfNeeded();
        if (doublePageMode) {
            if (currentPage > 1) currentPage -= 2; else { currentPage = 0; Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show(); }
        } else if (halfPageMode) {
            if (leftPage) leftPage = false;
            else if (currentPage > 0) { currentPage--; leftPage = true; }
            else Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
        } else { if (currentPage > 0) currentPage--; }
        displayCurrentPage();
    }

    private void originalGoToNextPage() {
        resetScaleIfNeeded();
        if (doublePageMode) {
            if (currentPage < totalPages - 1) { currentPage += 2; if (currentPage >= totalPages) currentPage = totalPages - 1; }
            else Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
        } else if (halfPageMode) {
            if (leftPage) { if (currentPage < totalPages - 1) { currentPage++; leftPage = false; } else Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show(); }
            else leftPage = true;
        } else { if (currentPage < totalPages - 1) currentPage++; }
        displayCurrentPage();
    }

    private void resetScaleIfNeeded() { if (scaleFactor > 1.01f) resetScale(); }

    // ==================== 显示页面（支持双页和缓存） ====================

    private void displayCurrentPage() {
        if (pdfRenderer == null) return;
        try {
            if (doublePageMode) {
                showDoublePage(currentPage, Math.min(currentPage + 1, totalPages - 1));
                return;
            }
            if (flipPageMode && cacheInitialized && cachedCurrentPage == currentPage && currentPageCache != null && !currentPageCache.isRecycled()) {
                pdfImageView.setImageBitmap(currentPageCache);
                centerImage(); pdfImageView.invalidate();
                updatePageNumberText(); saveReadingPosition();
                return;
            }

            PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
            int pageWidth = page.getWidth(), pageHeight = page.getHeight();
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            if (isRotated) { int t = screenWidth; screenWidth = screenHeight; screenHeight = t; }

            float scale = Math.min((float) screenWidth / pageWidth, (float) screenHeight / pageHeight);
            if (halfPageMode) scale = Math.min((float) screenWidth / (pageWidth / 2), (float) screenHeight / pageHeight);

            int hrW = Math.max(1, (int)(pageWidth * scale * 4)), hrH = Math.max(1, (int)(pageHeight * scale * 4));
            Bitmap hrBmp = Bitmap.createBitmap(hrW, hrH, Bitmap.Config.ARGB_8888);
            page.render(hrBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            int scaledW = (int)(pageWidth * scale), scaledH = (int)(pageHeight * scale);
            Bitmap bitmap = Bitmap.createScaledBitmap(hrBmp, scaledW, scaledH, true);
            if (!hrBmp.isRecycled() && hrBmp != bitmap) hrBmp.recycle();

            if (halfPageMode) {
                bitmap = leftPage ? Bitmap.createBitmap(bitmap, 0, 0, scaledW/2, scaledH) : Bitmap.createBitmap(bitmap, scaledW/2, 0, scaledW/2, scaledH);
            }
            if (nightMode) bitmap = invertColors(bitmap);
            if (isRotated) bitmap = rotateBitmap90(bitmap);

            pdfImageView.setImageBitmap(bitmap);
            pdfImageView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            pdfImageView.invalidate();
            scaleFactor = 1.0f; matrix.reset();
            pdfImageView.postDelayed(() -> centerImage(), 100);
            updatePageNumberText(); saveReadingPosition();

            if (flipPageMode && !cacheInitialized) pdfImageView.postDelayed(() -> initPageCache(), 300);
        } catch (Exception e) { Toast.makeText(this, "显示页面失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); e.printStackTrace(); }
    }

    private void showDoublePage(int leftPageNum, int rightPageNum) {
        try {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            if (isRotated) { int t = screenWidth; screenWidth = screenHeight; screenHeight = t; }

            Bitmap doubleBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(doubleBitmap);
            canvas.drawColor(getBackgroundColor());

            int lw=0,lh=0,rw=0,rh=0;
            if (leftPageNum < totalPages) { PdfRenderer.Page lp = pdfRenderer.openPage(leftPageNum); lw = lp.getWidth(); lh = lp.getHeight(); lp.close(); }
            if (rightPageNum < totalPages) { PdfRenderer.Page rp = pdfRenderer.openPage(rightPageNum); rw = rp.getWidth(); rh = rp.getHeight(); rp.close(); }

            int maxH = Math.max(lh, rh);
            float scaleByH = (float)(screenHeight * 0.95) / maxH;
            float scaleByW = (float)(screenWidth * 0.95) / (lw + rw);
            float scale = Math.min(scaleByH, scaleByW);

            int scaledLH = (int)(lh * scale), scaledLW = (int)(lw * scale);
            int scaledRH = (int)(rh * scale), scaledRW = (int)(rw * scale);
            int totalScaledW = scaledLW + scaledRW;
            int startX = (screenWidth - totalScaledW) / 2;
            int startY = (screenHeight - scaledLH) / 2;

            if (leftPageNum < totalPages) {
                PdfRenderer.Page lp = pdfRenderer.openPage(leftPageNum);
                Bitmap lb = Bitmap.createBitmap(Math.max(1,scaledLW*2), Math.max(1,scaledLH*2), Bitmap.Config.ARGB_8888);
                lp.render(lb, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                lp.close();
                lb = Bitmap.createScaledBitmap(lb, scaledLW, scaledLH, true);
                if (nightMode) lb = invertColors(lb);
                canvas.drawBitmap(lb, startX, startY, null);
            }
            if (rightPageNum < totalPages) {
                PdfRenderer.Page rp = pdfRenderer.openPage(rightPageNum);
                Bitmap rb = Bitmap.createBitmap(Math.max(1,scaledRW*2), Math.max(1,scaledRH*2), Bitmap.Config.ARGB_8888);
                rp.render(rb, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                rp.close();
                rb = Bitmap.createScaledBitmap(rb, scaledRW, scaledRH, true);
                if (nightMode) rb = invertColors(rb);
                canvas.drawBitmap(rb, startX + scaledLW, startY, null);
            }

            if (isRotated) doubleBitmap = rotateBitmap90(doubleBitmap);
            pdfImageView.setImageBitmap(doubleBitmap);
            pageTextView.setText((leftPageNum + 1) + "," + (rightPageNum + 1) + "/" + totalPages);
            pdfImageView.invalidate();
            pdfImageView.postDelayed(() -> centerImage(), 100);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ==================== 模式切换（带缓存清理与初始化） ====================

    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        if (halfPageBtn != null) halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) {
            displayCurrentPage();
            if (flipPageMode && !doublePageMode) pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
    }

    private void toggleDoublePageMode() {
        doublePageMode = !doublePageMode;
        if (pageModeBtn != null) pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) {
            displayCurrentPage();
            if (!doublePageMode && flipPageMode) pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
    }

    private void toggleRotation() {
        isRotated = !isRotated;
        if (rotateBtn != null) rotateBtn.setText(isRotated ? "转回" : "旋转");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) {
            displayCurrentPage();
            if (flipPageMode && !doublePageMode) pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
    }

    private void toggleFlipPageMode() {
        flipPageMode = !flipPageMode;
        if (flipModeBtn != null) flipModeBtn.setText(flipPageMode ? "平滑" : "预载");
        if (!flipPageMode) clearPageCache();
        else if (pdfRenderer != null && !doublePageMode && !cacheInitialized) initPageCache();
        saveSettings();
        Toast.makeText(this, flipPageMode ? "已启用预载模式" : "已启用平滑翻页", Toast.LENGTH_SHORT).show();
    }

    private void toggleNightMode() {
        nightMode = !nightMode;
        if (nightModeBtn != null) nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        saveSettings();
        updateThemeColors();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) {
            if (readerContainer != null) readerContainer.setBackgroundColor(getBackgroundColor());
            if (pdfImageView != null) pdfImageView.setBackgroundColor(getBackgroundColor());
            if (pageTextView != null) pageTextView.setTextColor(getTextColor());
            displayCurrentPage();
            if (flipPageMode && !doublePageMode) pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
    }

    // ==================== 辅助方法 ====================

    private void goBackToFileList() {
        closePdf();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            showFileListWithoutScan();
        else showFileList();
    }

    @Override public void onBackPressed() {
        if (pdfRenderer != null) goBackToFileList(); else super.onBackPressed();
    }

    private void requestPermissionsOnFirstRun() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                .setTitle("需要存储权限").setMessage("简帙阅读器需要访问您的存储空间来扫描和读取PDF文件。")
                .setPositiveButton("允许", (d,w) -> requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE))
                .setNegativeButton("不允许", (d,w) -> showFileListWithoutScan()).setCancelable(false).show();
        } else showFileList();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) showFileList();
            else showFileListWithoutScan();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (pdfRenderer == null) {
            createMainLayout();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                showFileListWithoutScan();
            else showFileList();
        } else {
            if (pdfImageView != null) { pdfImageView.postDelayed(() -> { pdfImageView.invalidate(); centerImage(); }, 200); }
            if (flipPageMode && !cacheInitialized) pdfImageView.postDelayed(() -> initPageCache(), 500);
        }
    }

    private void checkAutoOpenLastFile() {
        String lastFile = prefs.getString(LAST_OPENED_FILE, null);
        boolean autoOpen = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true);
        if (autoOpen && lastFile != null && !lastFile.isEmpty() && new File(lastFile).exists()) {
            createMainLayout();
            new android.os.Handler().postDelayed(() -> openPdfFile(lastFile), 100);
        } else {
            createMainLayout();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                showFileListWithoutScan();
            else showFileList();
        }
    }

    private void loadSettings() {
        nightMode = prefs.getBoolean("night_mode", false);
        halfPageMode = prefs.getBoolean("half_page", false);
        doublePageMode = prefs.getBoolean("double_page", false);
        isRotated = prefs.getBoolean("is_rotated", false);
        flipPageMode = prefs.getBoolean("flip_page_mode", true);
    }

    private void saveSettings() {
        prefs.edit().putBoolean("night_mode", nightMode).putBoolean("half_page", halfPageMode)
            .putBoolean("double_page", doublePageMode).putBoolean("is_rotated", isRotated)
            .putBoolean("flip_page_mode", flipPageMode).apply();
    }

    private void saveLastOpenedFile(String path) { if (path!=null) prefs.edit().putString(LAST_OPENED_FILE, path).apply(); }
    private void saveReadingPosition() {
        if (currentFilePath != null) {
            prefs.edit().putInt(currentFilePath+"_page", currentPage).putInt(currentFilePath+"_half_left", leftPage?1:0).apply();
            saveLastOpenedFile(currentFilePath);
        }
    }
    private int getReadingPosition(String path) { return prefs.getInt(path+"_page", 0); }
    private boolean getHalfPageLeftState(String path) { return prefs.getInt(path+"_half_left",0)==1; }

    private void createMainLayout() {
        mainLayout = new LinearLayout(this); mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        updateThemeColors(); setContentView(mainLayout);
    }

    private void updateThemeColors() { mainLayout.setBackgroundColor(nightMode?NIGHT_MODE_BG:DAY_MODE_BG); }
    private int getStatusBarColor() { return nightMode?NIGHT_STATUS_BAR_COLOR:DAY_STATUS_BAR_COLOR; }
    private int getTextColor() { return nightMode?NIGHT_MODE_TEXT:DAY_MODE_TEXT; }
    private int getBackgroundColor() { return nightMode?NIGHT_MODE_BG:DAY_MODE_BG; }
    private int getButtonBackgroundColor() { return nightMode?ANCIENT_GREEN:ANCIENT_BROWN; }
    private int getButtonTextColor() { return nightMode?ANCIENT_BEIGE:ANCIENT_GOLD; }
    private int getSpecialButtonBackgroundColor() { return ANCIENT_RED; }
    private int getSpecialButtonTextColor() { return Color.WHITE; }

    private void showFileListWithoutScan() {
        mainLayout.removeAllViews();
        LinearLayout topBar = createTopBar();
        fileListLayout = new LinearLayout(this); fileListLayout.setOrientation(LinearLayout.VERTICAL); fileListLayout.setPadding(20,20,20,20);
        TextView txt = new TextView(this); txt.setText("存储权限未授予\n\n请手动选择PDF文件"); txt.setTextSize(16); txt.setGravity(Gravity.CENTER); txt.setTextColor(getTextColor()); txt.setPadding(0,50,0,50);
        fileListLayout.addView(txt);
        openFileBtn = new Button(this); openFileBtn.setText("选择PDF文件"); openFileBtn.setBackgroundColor(getSpecialButtonBackgroundColor()); openFileBtn.setTextColor(getSpecialButtonTextColor()); openFileBtn.setTextSize(14); openFileBtn.setAllCaps(false); openFileBtn.setOnClickListener(v->choosePdfFile());
        setupButtonStyle(openFileBtn, true);
        fileListLayout.addView(openFileBtn);
        fileListLayout.setBackgroundColor(getBackgroundColor());
        mainLayout.addView(topBar);
        mainLayout.addView(fileListLayout);
    }

    private void showFileList() {
        mainLayout.removeAllViews();
        LinearLayout topBar = createTopBar();
        fileListLayout = new LinearLayout(this); fileListLayout.setOrientation(LinearLayout.VERTICAL); fileListLayout.setPadding(20,20,20,20); fileListLayout.setBackgroundColor(getBackgroundColor());
        addContinueReadingButton(); scanPdfFiles();
        mainLayout.addView(topBar); mainLayout.addView(fileListLayout);
    }

    private void addContinueReadingButton() {
        String last = prefs.getString(LAST_OPENED_FILE, null);
        if (last!=null && new File(last).exists()) {
            Button btn = new Button(this); btn.setText("继续阅读: "+getShortFileName(new File(last).getName())); btn.setBackgroundColor(getSpecialButtonBackgroundColor()); btn.setTextColor(getSpecialButtonTextColor()); btn.setTextSize(14); btn.setAllCaps(false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT); p.bottomMargin=20; btn.setLayoutParams(p);
            setupButtonStyle(btn, true); btn.setOnClickListener(v->openPdfFile(last)); fileListLayout.addView(btn);
        }
    }

    private String getShortFileName(String name) { return name.length()>20? name.substring(0,17)+"...": name; }

    private LinearLayout createTopBar() {
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setBackgroundColor(getStatusBarColor()); top.setPadding(20,15,20,15);
        TextView title = new TextView(this); title.setText("简帙阅读器 v1.0.21"); title.setTextColor(nightMode?ANCIENT_BEIGE:ANCIENT_GOLD); title.setTextSize(18); title.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1.0f));
        nightModeBtn = new Button(this); nightModeBtn.setText(nightMode?"日间模式":"夜间模式"); nightModeBtn.setBackgroundColor(getButtonBackgroundColor()); nightModeBtn.setTextColor(getButtonTextColor()); nightModeBtn.setTextSize(12); nightModeBtn.setAllCaps(false); setupButtonStyle(nightModeBtn,false);
        refreshBtn = new Button(this); refreshBtn.setText("刷新"); refreshBtn.setBackgroundColor(getButtonBackgroundColor()); refreshBtn.setTextColor(getButtonTextColor()); refreshBtn.setTextSize(12); refreshBtn.setAllCaps(false); setupButtonStyle(refreshBtn,false);
        top.addView(title); top.addView(nightModeBtn); top.addView(refreshBtn);
        return top;
    }

    private void setupButtonStyle(Button btn, boolean isLarge) {
        btn.setPadding(isLarge?30:15, isLarge?20:10, isLarge?30:15, isLarge?20:10); btn.setTextSize(isLarge?14:12);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) btn.setElevation(4);
    }

    private void scanPdfFiles() {
        fileListLayout.removeAllViews(); addContinueReadingButton();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { showFileListWithoutScan(); return; }
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir.exists() && downloadDir.isDirectory()) {
            File[] files = downloadDir.listFiles((d,n)->n.toLowerCase().endsWith(".pdf"));
            if (files!=null && files.length>0) for (File f:files) addFileButton(f); else showNoFilesMessage();
        } else showNoFilesMessage();
        addFileChooserOptions();
    }

    private void addFileButton(File file) {
        Button btn = new Button(this); String name = getShortFileName(file.getName()); int last = getReadingPosition(file.getAbsolutePath());
        if (last>0) name+=" (读到第"+(last+1)+"页)";
        btn.setText(name); btn.setBackgroundColor(getButtonBackgroundColor()); btn.setTextColor(getButtonTextColor()); btn.setTextSize(14); btn.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT); p.bottomMargin=10; btn.setLayoutParams(p);
        setupButtonStyle(btn, true); btn.setOnClickListener(v->openPdfFile(file.getAbsolutePath())); fileListLayout.addView(btn);
    }

    private void showNoFilesMessage() {
        TextView tv = new TextView(this); tv.setText("未找到PDF文件\n\n请将PDF放入Download文件夹"); tv.setTextSize(16); tv.setGravity(Gravity.CENTER); tv.setTextColor(getTextColor()); tv.setPadding(0,50,0,50); fileListLayout.addView(tv);
    }

    private void addFileChooserOptions() { /* 已有代码保持不变 */ }

    private void choosePdfFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/pdf");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(i,"选择PDF文件"), FILE_PICKER_REQUEST_CODE); } catch (Exception e) { Toast.makeText(this,"未找到文件管理器",Toast.LENGTH_SHORT).show(); }
    }

    private void scanAllPdfFiles() { /* 原有扫描代码保持不变 */ }
    private void scanDirectoryForPdf(File dir, List<File> list) { /* 原有递归扫描保持不变 */ }

    private void openPdfFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) { Toast.makeText(this,"文件无法访问",Toast.LENGTH_SHORT).show(); return; }
            fileDescriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            currentFilePath = path; totalPages = pdfRenderer.getPageCount();
            currentPage = getReadingPosition(path); leftPage = getHalfPageLeftState(path);
            if (currentPage>=totalPages) currentPage=totalPages-1; if (currentPage<0) currentPage=0;
            saveLastOpenedFile(path); showReaderView();
        } catch (Exception e) { Toast.makeText(this,"打开PDF失败: "+e.getMessage(),Toast.LENGTH_SHORT).show(); }
    }

    private void openPdfFromUri(Uri uri) { /* 原有URI打开逻辑保持不变 */ }
    private String getRealPathFromUri(Uri uri) { /* 原有路径解析保持不变 */ }

    private boolean checkDoubleTap(MotionEvent e) {
        if (e.getAction()==MotionEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            float x=e.getX(), y=e.getY();
            if (now - lastClickTime < DOUBLE_TAP_TIME_THRESHOLD && Math.hypot(x-lastTapX, y-lastTapY) < DOUBLE_TAP_MAX_DISTANCE) {
                resetScale(); lastClickTime=0; return true;
            }
            lastClickTime=now; lastTapX=x; lastTapY=y;
        }
        return false;
    }

    private void showReaderView() {
        mainLayout.removeAllViews();
        readerContainer = new FrameLayout(this); readerContainer.setBackgroundColor(getBackgroundColor());
        pdfImageView = new FlipImageView(this); pdfImageView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pdfImageView.setScaleType(ImageView.ScaleType.MATRIX); pdfImageView.setBackgroundColor(getBackgroundColor());
        scaleFactor=1.0f; matrix.reset(); savedMatrix.reset(); mode=NONE;
        touchStartX=touchStartY=touchStartTime=0; isClickCandidate=isSwiping=false; lastTapX=lastTapY=lastClickTime=0; isFlipping=false;

        pdfImageView.setOnTouchListener((v,event)->{
            if (checkDoubleTap(event)) return true;
            if (event.getPointerCount()==2) return handleZoomMode((ImageView)v, event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: return handleTouchDown((ImageView)v, event);
                case MotionEvent.ACTION_MOVE: return handleTouchMove((ImageView)v, event);
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: return handleTouchUp((ImageView)v, event);
            }
            return true;
        });

        LinearLayout topBar = createReaderTopBar(); topBar.setId(View.generateViewId());
        pageTextView = new TextView(this); pageTextView.setId(View.generateViewId()); pageTextView.setTextColor(getTextColor()); pageTextView.setTextSize(14); pageTextView.setBackgroundColor(Color.parseColor("#805D4037")); pageTextView.setPadding(15,8,15,8); pageTextView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); pageParams.bottomMargin=20; pageTextView.setLayoutParams(pageParams);

        prevBtn = new Button(this); prevBtn.setText("上一页"); prevBtn.setBackgroundColor(getButtonBackgroundColor()); prevBtn.setTextColor(getButtonTextColor()); prevBtn.setTextSize(12); prevBtn.setAllCaps(false); prevBtn.setOnClickListener(v->goToPrevPage());
        setupButtonStyle(prevBtn,false); FrameLayout.LayoutParams prevP = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM|Gravity.RIGHT); prevP.rightMargin=20; prevP.bottomMargin=80; prevBtn.setLayoutParams(prevP);

        nextBtn = new Button(this); nextBtn.setText("下一页"); nextBtn.setBackgroundColor(getButtonBackgroundColor()); nextBtn.setTextColor(getButtonTextColor()); nextBtn.setTextSize(12); nextBtn.setAllCaps(false); nextBtn.setOnClickListener(v->goToNextPage());
        setupButtonStyle(nextBtn,false); FrameLayout.LayoutParams nextP = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM|Gravity.LEFT); nextP.leftMargin=20; nextP.bottomMargin=80; nextBtn.setLayoutParams(nextP);

        Button jumpBtn = new Button(this); jumpBtn.setText("跳转"); jumpBtn.setBackgroundColor(getSpecialButtonBackgroundColor()); jumpBtn.setTextColor(getSpecialButtonTextColor()); jumpBtn.setTextSize(12); jumpBtn.setAllCaps(false); jumpBtn.setOnClickListener(v->showJumpPageDialog());
        FrameLayout.LayoutParams jumpP = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); jumpP.bottomMargin=80; jumpBtn.setLayoutParams(jumpP);

        readerContainer.addView(pdfImageView); readerContainer.addView(topBar); readerContainer.addView(prevBtn); readerContainer.addView(nextBtn); readerContainer.addView(jumpBtn); readerContainer.addView(pageTextView);
        mainLayout.addView(readerContainer);
        pdfImageView.postDelayed(()->displayCurrentPage(),100);
    }

    private boolean handleZoomMode(ImageView view, MotionEvent event) {
        isClickCandidate=isSwiping=false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN: oldDistance=spacing(event); if(oldDistance>10){savedMatrix.set(matrix); midPoint(midPoint,event); mode=ZOOM;} break;
            case MotionEvent.ACTION_MOVE: if(mode==ZOOM){ float newDist=spacing(event); if(newDist>10){matrix.set(savedMatrix); float s=newDist/oldDistance; scaleFactor*=s; if(scaleFactor<minScale){s=minScale/(scaleFactor/s); scaleFactor=minScale;} else if(scaleFactor>maxScale){s=maxScale/(scaleFactor/s); scaleFactor=maxScale;} matrix.postScale(s,s,midPoint.x,midPoint.y); limitDragWithBoundary();}} break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: mode=NONE; if(scaleFactor>1.01f)limitDragWithBoundary(); else centerImage(); break;
        }
        view.setImageMatrix(matrix); return true;
    }

    private boolean handleTouchDown(ImageView view, MotionEvent event) {
        touchStartX=event.getX(); touchStartY=event.getY(); touchStartTime=System.currentTimeMillis();
        if(scaleFactor>1.01f){ isClickCandidate=isSwiping=false; savedMatrix.set(matrix); startPoint.set(touchStartX,touchStartY); mode=DRAG; } else { isClickCandidate=true; isSwiping=false; mode=NONE; } return true;
    }

    private boolean handleTouchMove(ImageView view, MotionEvent event) {
        if(scaleFactor>1.01f && mode==DRAG){ matrix.set(savedMatrix); float dx=event.getX()-startPoint.x, dy=event.getY()-startPoint.y; matrix.postTranslate(dx,dy); limitDragWithBoundary(); view.setImageMatrix(matrix); return true; }
        float dx=Math.abs(event.getX()-touchStartX), dy=Math.abs(event.getY()-touchStartY);
        if(dx>SWIPE_MIN_DISTANCE && dx>dy*SWIPE_VS_SCROLL_RATIO){ isClickCandidate=false; isSwiping=true; } return true;
    }

    private boolean handleTouchUp(ImageView view, MotionEvent event) {
        if(scaleFactor>1.01f && mode==DRAG){ mode=NONE; return true; }
        float ex=event.getX(), ey=event.getY(); long dur=System.currentTimeMillis()-touchStartTime; float dist=(float)Math.hypot(ex-touchStartX, ey-touchStartY);
        if(isClickCandidate && dur<CLICK_MAX_TIME && dist<CLICK_MAX_DISTANCE) handleClick(ex,ey,view.getWidth(),view.getHeight());
        else if(isSwiping) handleSwipe(touchStartX, ex);
        isClickCandidate=isSwiping=false; mode=NONE; return true;
    }

    private void handleClick(float x, float y, float w, float h) {
        if(isRotated){
            float t=h/3; if(y<t) goToNextPage(); else if(y>2*t) goToPrevPage(); else toggleControls();
        } else {
            float t=w/3; if(x<t) goToNextPage(); else if(x>2*t) goToPrevPage(); else toggleControls();
        }
    }

    private void handleSwipe(float sx, float ex) { if(ex-sx>SWIPE_MIN_DISTANCE) goToNextPage(); else if(sx-ex>SWIPE_MIN_DISTANCE) goToPrevPage(); }
    private float spacing(MotionEvent e) { float x=e.getX(0)-e.getX(1), y=e.getY(0)-e.getY(1); return (float)Math.sqrt(x*x+y*y); }
    private void midPoint(PointF p, MotionEvent e) { p.set((e.getX(0)+e.getX(1))/2, (e.getY(0)+e.getY(1))/2); }

    private void limitDragWithBoundary() {
        float[] v=new float[9]; matrix.getValues(v);
        float s=v[Matrix.MSCALE_X], tx=v[Matrix.MTRANS_X], ty=v[Matrix.MTRANS_Y];
        int vw=pdfImageView.getWidth(), vh=pdfImageView.getHeight();
        BitmapDrawable d=(BitmapDrawable)pdfImageView.getDrawable(); if(d==null) return;
        Bitmap b=d.getBitmap(); if(b==null) return;
        float sw=b.getWidth()*s, sh=b.getHeight()*s;
        float minTx=0,maxTx=0,minTy=0,maxTy=0;
        if(sw>vw){ minTx=vw-sw; maxTx=0; } else { minTx=maxTx=(vw-sw)/2; }
        if(sh>vh){ minTy=vh-sh; maxTy=0; } else { minTy=maxTy=(vh-sh)/2; }
        if(tx<minTx) tx=minTx; else if(tx>maxTx) tx=maxTx;
        if(ty<minTy) ty=minTy; else if(ty>maxTy) ty=maxTy;
        v[Matrix.MTRANS_X]=tx; v[Matrix.MTRANS_Y]=ty; matrix.setValues(v);
    }

    private void centerImage() {
        if(pdfImageView==null) return;
        if(pdfImageView.getWidth()==0||pdfImageView.getHeight()==0){ pdfImageView.postDelayed(()->centerImage(),50); return; }
        BitmapDrawable d=(BitmapDrawable)pdfImageView.getDrawable(); if(d==null) return;
        Bitmap b=d.getBitmap(); if(b==null) return;
        int vw=pdfImageView.getWidth(), vh=pdfImageView.getHeight(), bw=b.getWidth(), bh=b.getHeight();
        float[] v=new float[9]; matrix.getValues(v); float s=v[Matrix.MSCALE_X];
        if(Math.abs(scaleFactor-1.0f)<0.01f){
            s=Math.min((float)vw/bw, (float)vh/bh); matrix.postScale(s,s); scaleFactor=s; matrix.getValues(v);
        }
        float sw=bw*s, sh=bh*s;
        float dx=(vw-sw)/2, dy=(vh-sh)/2;
        v[Matrix.MTRANS_X]=dx; v[Matrix.MTRANS_Y]=dy; matrix.setValues(v);
        limitDragWithBoundary(); pdfImageView.setImageMatrix(matrix); pdfImageView.invalidate();
    }

    private void resetScale() { scaleFactor=1.0f; matrix.reset(); centerImage(); pdfImageView.invalidate(); Toast.makeText(this,"已恢复原始大小",Toast.LENGTH_SHORT).show(); }

    private LinearLayout createReaderTopBar() {
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setBackgroundColor(getStatusBarColor()); bar.setPadding(0,8,0,8);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP); bar.setLayoutParams(p);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

        Button backBtn = new Button(this); backBtn.setText("返回"); backBtn.setBackgroundColor(getButtonBackgroundColor()); backBtn.setTextColor(getButtonTextColor()); backBtn.setTextSize(11); backBtn.setAllCaps(false); backBtn.setOnClickListener(v->goBackToFileList()); setupButtonStyle(backBtn,false); backBtn.setLayoutParams(bp);
        Button nightBtn = new Button(this); nightBtn.setText(nightMode?"日间":"夜间"); nightBtn.setBackgroundColor(getButtonBackgroundColor()); nightBtn.setTextColor(getButtonTextColor()); nightBtn.setTextSize(11); nightBtn.setAllCaps(false); nightBtn.setOnClickListener(v->toggleNightMode()); nightBtn.setLayoutParams(bp);
        halfPageBtn = new Button(this); halfPageBtn.setText(halfPageMode?"整页":"半页"); halfPageBtn.setBackgroundColor(getButtonBackgroundColor()); halfPageBtn.setTextColor(getButtonTextColor()); halfPageBtn.setTextSize(11); halfPageBtn.setAllCaps(false); halfPageBtn.setOnClickListener(v->toggleHalfPageMode()); halfPageBtn.setLayoutParams(bp);
        pageModeBtn = new Button(this); pageModeBtn.setText(doublePageMode?"单页":"双页"); pageModeBtn.setBackgroundColor(getButtonBackgroundColor()); pageModeBtn.setTextColor(getButtonTextColor()); pageModeBtn.setTextSize(11); pageModeBtn.setAllCaps(false); pageModeBtn.setOnClickListener(v->toggleDoublePageMode()); pageModeBtn.setLayoutParams(bp);
        rotateBtn = new Button(this); rotateBtn.setText(isRotated?"转回":"旋转"); rotateBtn.setBackgroundColor(getButtonBackgroundColor()); rotateBtn.setTextColor(getButtonTextColor()); rotateBtn.setTextSize(11); rotateBtn.setAllCaps(false); rotateBtn.setOnClickListener(v->toggleRotation()); rotateBtn.setLayoutParams(bp);
        flipModeBtn = new Button(this); flipModeBtn.setText(flipPageMode?"平滑":"预载"); flipModeBtn.setBackgroundColor(getButtonBackgroundColor()); flipModeBtn.setTextColor(getButtonTextColor()); flipModeBtn.setTextSize(11); flipModeBtn.setAllCaps(false); flipModeBtn.setOnClickListener(v->toggleFlipPageMode()); flipModeBtn.setLayoutParams(bp);

        bar.addView(backBtn); bar.addView(nightBtn); bar.addView(halfPageBtn); bar.addView(pageModeBtn); bar.addView(rotateBtn); bar.addView(flipModeBtn);
        return bar;
    }

    private void toggleControls() { /* 控制栏显隐逻辑保持不变 */ }

    private void showJumpPageDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this); b.setTitle("跳转到指定页面").setMessage("输入页码 (1 - "+totalPages+")");
        EditText et = new EditText(this); et.setInputType(InputType.TYPE_CLASS_NUMBER); et.setText(String.valueOf(currentPage+1)); b.setView(et);
        b.setPositiveButton("确定",(d,w)->{
            try {
                int p = Integer.parseInt(et.getText().toString().trim());
                if(p>=1 && p<=totalPages){ currentPage=p-1; if(halfPageMode) leftPage=true; clearPageCache(); displayCurrentPage(); }
                else Toast.makeText(this,"范围1-"+totalPages,Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this,"请输入数字",Toast.LENGTH_SHORT).show(); }
        });
        b.setNegativeButton("取消",null).show();
    }

    private void updatePageNumberText() {
        if(halfPageMode) pageTextView.setText((currentPage+1)+"/"+totalPages+" "+(leftPage?"左":"右"));
        else pageTextView.setText((currentPage+1)+"/"+totalPages);
    }

    private Bitmap invertColors(Bitmap b) {
        if(b==null) return null;
        Bitmap inv = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(inv);
        ColorMatrix cm = new ColorMatrix(new float[]{-1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0});
        Paint p = new Paint(); p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(b,0,0,p); return inv;
    }

    private Bitmap rotateBitmap90(Bitmap b) {
        if(b==null) return null;
        Matrix m = new Matrix(); m.postRotate(90);
        return Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);
    }

    private void closePdf() {
        clearPageCache();
        if(pdfRenderer!=null) pdfRenderer.close();
        if(fileDescriptor!=null) try{fileDescriptor.close();}catch(IOException e){}
    }

    @Override protected void onActivityResult(int req, int res, Intent data) { /* 文件选择返回逻辑保持不变 */ }
    @Override protected void onDestroy() { super.onDestroy(); closePdf(); }
}
