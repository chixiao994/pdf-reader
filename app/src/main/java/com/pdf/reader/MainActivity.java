package com.pdf.reader;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Camera;
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
import android.view.animation.AccelerateDecelerateInterpolator;
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
    private TextView pageTextView; // 保留声明，但使用底部栏中的 bottomPageText
    private Button nightModeBtn, halfPageBtn, pageModeBtn, openFileBtn, refreshBtn, rotateBtn, flipModeBtn;
    
    // PDF相关
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int currentPage = 0;
    private int totalPages = 0;
    private String currentFilePath;
    
    // 设置
    private boolean nightMode = false;
    private boolean halfPageMode = false; // 半页模式
    private boolean doublePageMode = false; // 双页模式
    private boolean leftPage = false;
    private boolean controlsVisible = true; // 控制栏是否可见
    private boolean isRotated = false; // 是否旋转90度
    
    // 翻页效果相关变量
    private boolean flipPageMode = false; // 默认启用平滑模式（false）
    private boolean isFlipping = false; // 是否正在翻页
    
    // 页面缓存相关变量
    private Bitmap prevPageCache;
    private Bitmap currentPageCache;
    private Bitmap nextPageCache;
    private int cachedPrevPage = -1;
    private int cachedCurrentPage = -1;
    private int cachedNextPage = -1;
    private boolean cacheInitialized = false;
    
    // 缩放相关变量
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
    
    // 点击和滑动相关变量
    private float touchStartX, touchStartY;
    private long touchStartTime;
    private boolean isClickCandidate = false;
    private boolean isSwiping = false;
    private static final int CLICK_MAX_DISTANCE = 20;
    private static final int CLICK_MAX_TIME = 200;
    private static final int SWIPE_MIN_DISTANCE = 60;
    private static final float SWIPE_VS_SCROLL_RATIO = 1.5f;
    
    // 双击相关变量
    private float lastTapX, lastTapY;
    private static final int DOUBLE_TAP_MAX_DISTANCE = 50;
    
    // 存储
    private SharedPreferences prefs;
    private static final String LAST_OPENED_FILE = "last_opened_file";
    private static final String AUTO_OPEN_LAST_FILE = "auto_open_last_file";
    private static final String FIRST_RUN = "first_run";
    
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;
    
    // 颜色常量
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
    
    // 底部栏控件
    private LinearLayout bottomBar;
    private Button bottomPrevButton;
    private Button bottomNextButton;
    private TextView bottomPageText;
    
    // 自定义ImageView
    private class FlipImageView extends ImageView {
        public FlipImageView(Context context) {
            super(context);
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
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
    
    private void initPageCache() {
        if (pdfRenderer == null) return;
        clearPageCache();
        int prevPage = Math.max(0, currentPage - 1);
        int nextPage = Math.min(totalPages - 1, currentPage + 1);
        cachePage(prevPage, true);
        cachePage(currentPage, false);
        cachePage(nextPage, true);
        cachedPrevPage = prevPage;
        cachedCurrentPage = currentPage;
        cachedNextPage = nextPage;
        cacheInitialized = true;
    }
    
    private void cachePage(int pageIndex, boolean isAdjacent) {
        if (pageIndex < 0 || pageIndex >= totalPages) return;
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
            float scaleQuality = isAdjacent ? 2.0f : 4.0f;
            float scale = Math.min(
                (float) viewWidth / pageWidth,
                (float) viewHeight / pageHeight
            );
            if (halfPageMode && !isAdjacent) {
                scale = Math.min(
                    (float) viewWidth / (pageWidth / 2),
                    (float) viewHeight / pageHeight
                );
            }
            int scaledWidth = (int) (pageWidth * scale * scaleQuality);
            int scaledHeight = (int) (pageHeight * scale * scaleQuality);
            scaledWidth = Math.max(scaledWidth, 1);
            scaledHeight = Math.max(scaledHeight, 1);
            Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            if (nightMode) {
                bitmap = invertColors(bitmap);
            }
            if (isRotated) {
                bitmap = rotateBitmap90(bitmap);
            }
            if (halfPageMode && !isAdjacent) {
                int finalWidth = (int) (pageWidth * scale);
                int finalHeight = (int) (pageHeight * scale);
                bitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true);
                if (leftPage) {
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, finalWidth / 2, finalHeight);
                } else {
                    bitmap = Bitmap.createBitmap(bitmap, finalWidth / 2, 0, finalWidth / 2, finalHeight);
                }
            } else {
                int finalWidth = (int) (pageWidth * scale);
                int finalHeight = (int) (pageHeight * scale);
                bitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true);
            }
            if (pageIndex == currentPage) {
                currentPageCache = bitmap;
            } else if (pageIndex == currentPage - 1) {
                prevPageCache = bitmap;
            } else if (pageIndex == currentPage + 1) {
                nextPageCache = bitmap;
            }
        } catch (Exception e) {
            Log.e("PDF_DEBUG", "Cache page failed: " + pageIndex, e);
        }
    }
    
    private void clearPageCache() {
        if (prevPageCache != null) { prevPageCache.recycle(); prevPageCache = null; }
        if (currentPageCache != null) { currentPageCache.recycle(); currentPageCache = null; }
        if (nextPageCache != null) { nextPageCache.recycle(); nextPageCache = null; }
        cachedPrevPage = -1; cachedCurrentPage = -1; cachedNextPage = -1;
        cacheInitialized = false;
    }
    
    private void updatePageCacheAfterFlip(boolean forward) {
        if (!cacheInitialized || !flipPageMode) return;
        if (forward) {
            if (prevPageCache != null) { prevPageCache.recycle(); prevPageCache = null; }
            prevPageCache = currentPageCache;
            currentPageCache = nextPageCache;
            nextPageCache = null;
            cachedPrevPage = cachedCurrentPage;
            cachedCurrentPage = cachedNextPage;
            int newNextPage = Math.min(totalPages - 1, currentPage + 1);
            if (newNextPage != cachedCurrentPage && newNextPage < totalPages) {
                cachePage(newNextPage, true);
                cachedNextPage = newNextPage;
            }
        } else {
            if (nextPageCache != null) { nextPageCache.recycle(); nextPageCache = null; }
            nextPageCache = currentPageCache;
            currentPageCache = prevPageCache;
            prevPageCache = null;
            cachedNextPage = cachedCurrentPage;
            cachedCurrentPage = cachedPrevPage;
            int newPrevPage = Math.max(0, currentPage - 1);
            if (newPrevPage != cachedCurrentPage && newPrevPage >= 0) {
                cachePage(newPrevPage, true);
                cachedPrevPage = newPrevPage;
            }
        }
        if (currentPageCache != null) {
            pdfImageView.setImageBitmap(currentPageCache);
            centerImage();
            pdfImageView.invalidate();
        }
    }
    
    private void goBackToFileList() {
        closePdf();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                showFileListWithoutScan();
            } else {
                showFileList();
            }
        } else {
            showFileList();
        }
    }
    
    @Override
    public void onBackPressed() {
        if (pdfRenderer != null) {
            goBackToFileList();
        } else {
            super.onBackPressed();
        }
    }
    
    private void requestPermissionsOnFirstRun() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("简帙阅读器需要访问您的存储空间来扫描和读取PDF文件。\n\n" +
                               "权限将用于：\n" +
                               "• 扫描PDF文件\n" +
                               "• 打开您选择的PDF文件\n" +
                               "• 保存您的阅读进度")
                    .setPositiveButton("允许", (dialog, which) -> {
                        requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }, PERMISSION_REQUEST_CODE);
                    })
                    .setNegativeButton("不允许", (dialog, which) -> {
                        showFileListWithoutScan();
                    })
                    .setCancelable(false)
                    .show();
            } else {
                showFileList();
            }
        } else {
            showFileList();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showFileList();
            } else {
                showFileListWithoutScan();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (pdfRenderer == null) {
            createMainLayout();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    showFileListWithoutScan();
                } else {
                    showFileList();
                }
            } else {
                showFileList();
            }
        } else {
            if (pdfImageView != null) {
                pdfImageView.postDelayed(() -> {
                    pdfImageView.invalidate();
                    centerImage();
                }, 200);
            }
            if (flipPageMode && !cacheInitialized) {
                pdfImageView.postDelayed(() -> initPageCache(), 500);
            }
        }
    }
    
    private void checkAutoOpenLastFile() {
        String lastOpenedFile = prefs.getString(LAST_OPENED_FILE, null);
        boolean autoOpenLastFile = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true);
        if (autoOpenLastFile && lastOpenedFile != null && !lastOpenedFile.isEmpty()) {
            File file = new File(lastOpenedFile);
            if (file.exists() && file.canRead()) {
                createMainLayout();
                new android.os.Handler().postDelayed(() -> openPdfFile(lastOpenedFile), 100);
                return;
            } else {
                prefs.edit().remove(LAST_OPENED_FILE).apply();
            }
        }
        createMainLayout();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                showFileListWithoutScan();
            } else {
                showFileList();
            }
        } else {
            showFileList();
        }
    }
    
    private void loadSettings() {
        nightMode = prefs.getBoolean("night_mode", false);
        halfPageMode = prefs.getBoolean("half_page", false);
        doublePageMode = prefs.getBoolean("double_page", false);
        isRotated = prefs.getBoolean("is_rotated", false);
        flipPageMode = prefs.getBoolean("flip_page_mode", false); // 默认平滑模式
    }
    
    private void saveSettings() {
        prefs.edit()
            .putBoolean("night_mode", nightMode)
            .putBoolean("half_page", halfPageMode)
            .putBoolean("double_page", doublePageMode)
            .putBoolean("is_rotated", isRotated)
            .putBoolean("flip_page_mode", flipPageMode)
            .apply();
    }
    
    private void saveLastOpenedFile(String filePath) {
        if (filePath != null) {
            prefs.edit().putString(LAST_OPENED_FILE, filePath).apply();
        }
    }
    
    private void saveReadingPosition() {
        if (currentFilePath != null) {
            prefs.edit()
                .putInt(currentFilePath + "_page", currentPage)
                .putInt(currentFilePath + "_half_page_left", leftPage ? 1 : 0)
                .apply();
            saveLastOpenedFile(currentFilePath);
        }
    }
    
    private int getReadingPosition(String filePath) {
        return prefs.getInt(filePath + "_page", 0);
    }
    
    private boolean getHalfPageLeftState(String filePath) {
        return prefs.getInt(filePath + "_half_page_left", 0) == 1;
    }
    
    private void createMainLayout() {
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        updateThemeColors();
        setContentView(mainLayout);
    }
    
    private void updateThemeColors() {
        if (nightMode) {
            mainLayout.setBackgroundColor(NIGHT_MODE_BG);
        } else {
            mainLayout.setBackgroundColor(DAY_MODE_BG);
        }
    }
    
    private int getStatusBarColor() {
        return nightMode ? NIGHT_STATUS_BAR_COLOR : DAY_STATUS_BAR_COLOR;
    }
    
    private int getTextColor() {
        return nightMode ? NIGHT_MODE_TEXT : DAY_MODE_TEXT;
    }
    
    private int getBackgroundColor() {
        return nightMode ? NIGHT_MODE_BG : DAY_MODE_BG;
    }
    
    private int getButtonBackgroundColor() {
        return nightMode ? ANCIENT_GREEN : ANCIENT_BROWN;
    }
    
    private int getButtonTextColor() {
        return nightMode ? ANCIENT_BEIGE : ANCIENT_GOLD;
    }
    
    private int getSpecialButtonBackgroundColor() {
        return nightMode ? ANCIENT_RED : ANCIENT_RED;
    }
    
    private int getSpecialButtonTextColor() {
        return Color.WHITE;
    }
    
    private void showFileListWithoutScan() {
        mainLayout.removeAllViews();
        LinearLayout topBar = createTopBar();
        fileListLayout = new LinearLayout(this);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        fileListLayout.setPadding(20, 20, 20, 20);
        TextView noPermissionText = new TextView(this);
        noPermissionText.setText("存储权限未授予\n\n无法自动扫描PDF文件\n\n请点击下方手动选择PDF文件");
        noPermissionText.setTextSize(16);
        noPermissionText.setGravity(android.view.Gravity.CENTER);
        noPermissionText.setTextColor(getTextColor());
        noPermissionText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noPermissionText);
        openFileBtn = new Button(this);
        openFileBtn.setText("选择PDF文件");
        openFileBtn.setBackgroundColor(getSpecialButtonBackgroundColor());
        openFileBtn.setTextColor(getSpecialButtonTextColor());
        openFileBtn.setTextSize(14);
        openFileBtn.setAllCaps(false);
        openFileBtn.setOnClickListener(v -> choosePdfFile());
        setupButtonStyle(openFileBtn, true);
        fileListLayout.addView(openFileBtn);
        fileListLayout.setBackgroundColor(getBackgroundColor());
        mainLayout.addView(topBar);
        mainLayout.addView(fileListLayout);
    }
    
    private void showFileList() {
        mainLayout.removeAllViews();
        LinearLayout topBar = createTopBar();
        fileListLayout = new LinearLayout(this);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        fileListLayout.setPadding(20, 20, 20, 20);
        fileListLayout.setBackgroundColor(getBackgroundColor());
        addContinueReadingButton();
        scanPdfFiles();
        mainLayout.addView(topBar);
        mainLayout.addView(fileListLayout);
    }
    
    private void addContinueReadingButton() {
        String lastOpenedFile = prefs.getString(LAST_OPENED_FILE, null);
        if (lastOpenedFile != null && !lastOpenedFile.isEmpty()) {
            File file = new File(lastOpenedFile);
            if (file.exists() && file.canRead()) {
                Button continueBtn = new Button(this);
                continueBtn.setText("继续阅读: " + getShortFileName(file.getName()));
                continueBtn.setBackgroundColor(getSpecialButtonBackgroundColor());
                continueBtn.setTextColor(getSpecialButtonTextColor());
                continueBtn.setTextSize(14);
                continueBtn.setAllCaps(false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = 20;
                continueBtn.setLayoutParams(params);
                setupButtonStyle(continueBtn, true);
                continueBtn.setOnClickListener(v -> openPdfFile(lastOpenedFile));
                fileListLayout.addView(continueBtn);
            }
        }
    }
    
    private String getShortFileName(String fileName) {
        if (fileName.length() > 20) return fileName.substring(0, 17) + "...";
        return fileName;
    }
    
    private LinearLayout createTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(20, 15, 20, 15);
        TextView title = new TextView(this);
        title.setText("简帙阅读器 v1.0.22");
        title.setTextColor(nightMode ? ANCIENT_BEIGE : ANCIENT_GOLD);
        title.setTextSize(18);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        nightModeBtn = new Button(this);
        nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        nightModeBtn.setBackgroundColor(getButtonBackgroundColor());
        nightModeBtn.setTextColor(getButtonTextColor());
        nightModeBtn.setTextSize(12);
        nightModeBtn.setAllCaps(false);
        setupButtonStyle(nightModeBtn, false);
        refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setBackgroundColor(getButtonBackgroundColor());
        refreshBtn.setTextColor(getButtonTextColor());
        refreshBtn.setTextSize(12);
        refreshBtn.setAllCaps(false);
        refreshBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setupButtonStyle(refreshBtn, false);
        topBar.addView(title);
        topBar.addView(nightModeBtn);
        topBar.addView(refreshBtn);
        return topBar;
    }
    
    private void setupButtonStyle(Button button, boolean isLarge) {
        if (isLarge) {
            button.setPadding(30, 20, 30, 20);
            button.setTextSize(14);
        } else {
            button.setPadding(15, 10, 15, 10);
            button.setTextSize(12);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setElevation(4);
        }
    }
    
    private void scanPdfFiles() {
        fileListLayout.removeAllViews();
        addContinueReadingButton();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要存储权限来扫描文件", Toast.LENGTH_SHORT).show();
            showFileListWithoutScan();
            return;
        }
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir.exists() && downloadDir.isDirectory()) {
                File[] files = downloadDir.listFiles((dir, name) -> 
                    name.toLowerCase().endsWith(".pdf"));
                if (files != null && files.length > 0) {
                    for (File file : files) addFileButton(file);
                } else {
                    showNoFilesMessage();
                }
            } else {
                showNoFilesMessage();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "没有访问存储的权限", Toast.LENGTH_SHORT).show();
            showNoFilesMessage();
        }
        addFileChooserOptions();
    }
    
    private void addFileButton(File file) {
        Button fileBtn = new Button(this);
        String fileName = getShortFileName(file.getName());
        int lastPage = getReadingPosition(file.getAbsolutePath());
        if (lastPage > 0) fileName += " (读到第" + (lastPage + 1) + "页)";
        fileBtn.setText(fileName);
        fileBtn.setBackgroundColor(getButtonBackgroundColor());
        fileBtn.setTextColor(getButtonTextColor());
        fileBtn.setTextSize(14);
        fileBtn.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 10;
        fileBtn.setLayoutParams(params);
        setupButtonStyle(fileBtn, true);
        String filePath = file.getAbsolutePath();
        fileBtn.setOnClickListener(v -> openPdfFile(filePath));
        fileListLayout.addView(fileBtn);
    }
    
    private void showNoFilesMessage() {
        TextView noFilesText = new TextView(this);
        noFilesText.setText("未找到PDF文件\n\n请将PDF文件放置在：\n手机存储 → Download文件夹\n\n或者使用下方选项选择文件");
        noFilesText.setTextSize(16);
        noFilesText.setGravity(android.view.Gravity.CENTER);
        noFilesText.setTextColor(getTextColor());
        noFilesText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noFilesText);
    }
    
    private void addFileChooserOptions() {
        LinearLayout optionsLayout = new LinearLayout(this);
        optionsLayout.setOrientation(LinearLayout.VERTICAL);
        optionsLayout.setPadding(0, 20, 0, 0);
        Button singleFileBtn = new Button(this);
        singleFileBtn.setText("选择单个PDF文件");
        singleFileBtn.setBackgroundColor(getButtonBackgroundColor());
        singleFileBtn.setTextColor(getButtonTextColor());
        singleFileBtn.setTextSize(14);
        singleFileBtn.setAllCaps(false);
        singleFileBtn.setOnClickListener(v -> choosePdfFile());
        LinearLayout.LayoutParams singleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        singleParams.bottomMargin = 10;
        singleFileBtn.setLayoutParams(singleParams);
        setupButtonStyle(singleFileBtn, true);
        Button scanAllBtn = new Button(this);
        scanAllBtn.setText("扫描全盘PDF文件");
        scanAllBtn.setBackgroundColor(getButtonBackgroundColor());
        scanAllBtn.setTextColor(getButtonTextColor());
        scanAllBtn.setTextSize(14);
        scanAllBtn.setAllCaps(false);
        scanAllBtn.setOnClickListener(v -> scanAllPdfFiles());
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scanParams.bottomMargin = 10;
        scanAllBtn.setLayoutParams(scanParams);
        setupButtonStyle(scanAllBtn, true);
        optionsLayout.addView(singleFileBtn);
        optionsLayout.addView(scanAllBtn);
        fileListLayout.addView(optionsLayout);
// 在 addFileChooserOptions() 的 
        optionsLayout 中添加：
        Button tianlangBtn = new Button(this);
        tianlangBtn.setText("天朗裁切");
        tianlangBtn.setBackgroundColor(getSpecialButtonBackgroundColor());
        tianlangBtn.setTextColor(getSpecialButtonTextColor());
        tianlangBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, TianLangActivity.class);
            startActivity(intent);
        });
        optionsLayout.addView(tianlangBtn);
    }
    
    private void choosePdfFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择PDF文件"), 
                    FILE_PICKER_REQUEST_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "未找到文件管理器", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void scanAllPdfFiles() {
        fileListLayout.removeAllViews();
        TextView scanningText = new TextView(this);
        scanningText.setText("正在扫描全盘PDF文件，请稍候...");
        scanningText.setTextSize(16);
        scanningText.setGravity(android.view.Gravity.CENTER);
        scanningText.setTextColor(getTextColor());
        scanningText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(scanningText);
        new Thread(() -> {
            List<File> pdfFiles = new ArrayList<>();
            try {
                String[] scanPaths = {
                    Environment.getExternalStorageDirectory().getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath(),
                    Environment.getDataDirectory().getAbsolutePath()
                };
                for (String path : scanPaths) {
                    try { scanDirectoryForPdf(new File(path), pdfFiles); } catch (SecurityException e) {}
                }
            } catch (Exception e) {
                Log.e("PDF_DEBUG", "扫描错误: " + e.getMessage());
            }
            runOnUiThread(() -> {
                fileListLayout.removeAllViews();
                addContinueReadingButton();
                if (pdfFiles.isEmpty()) {
                    showNoFilesMessage();
                } else {
                    for (File file : pdfFiles) addFileButton(file);
                }
                addFileChooserOptions();
            });
        }).start();
    }
    
    private void scanDirectoryForPdf(File directory, List<File> pdfFiles) {
        if (directory == null || !directory.exists() || !directory.canRead()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().startsWith(".") && 
                    !file.getName().equals("Android") &&
                    !file.getName().equals("lost+found")) {
                    scanDirectoryForPdf(file, pdfFiles);
                }
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".pdf")) {
                pdfFiles.add(file);
            }
        }
    }
    
    private void openPdfFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { Toast.makeText(this, "文件不存在: " + filePath, Toast.LENGTH_SHORT).show(); return; }
            if (!file.canRead()) { Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show(); return; }
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            currentFilePath = filePath;
            totalPages = pdfRenderer.getPageCount();
            currentPage = getReadingPosition(filePath);
            leftPage = getHalfPageLeftState(filePath);
            if (currentPage >= totalPages) currentPage = totalPages - 1;
            if (currentPage < 0) currentPage = 0;
            saveLastOpenedFile(filePath);
            showReaderView();
        } catch (SecurityException e) {
            Toast.makeText(this, "权限不足，无法访问文件", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "无法打开PDF文件，可能文件已损坏", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "未知错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void openPdfFromUri(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            String displayName = null;
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex);
                }
            } catch (Exception e) {}
            String tempFileName = displayName != null ? displayName : "temp_pdf_" + System.currentTimeMillis() + ".pdf";
            File tempFile = new File(getCacheDir(), tempFileName);
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                if (in == null) { Toast.makeText(this, "无法读取文件内容", Toast.LENGTH_SHORT).show(); return; }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) out.write(buffer, 0, bytesRead);
                openPdfFile(tempFile.getAbsolutePath());
                if (currentFilePath != null && currentFilePath.contains("temp_pdf_")) new File(currentFilePath).delete();
                currentFilePath = tempFile.getAbsolutePath();
            } catch (IOException e) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开PDF文件", Toast.LENGTH_SHORT).show();
        }
    }
    
    private String getRealPathFromUri(Uri uri) {
        String filePath = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && 
                    DocumentsContract.isDocumentUri(this, uri)) {
                String wholeID = DocumentsContract.getDocumentId(uri);
                if (wholeID != null) {
                    String[] split = wholeID.split(":");
                    if (split.length > 1) {
                        String type = split[0];
                        String id = split[1];
                        if ("primary".equalsIgnoreCase(type)) {
                            filePath = Environment.getExternalStorageDirectory() + "/" + id;
                        } else {
                            try {
                                File externalDir = Environment.getExternalStorageDirectory();
                                if (externalDir != null && externalDir.getParent() != null) {
                                    filePath = externalDir.getParent() + "/" + type + "/" + id;
                                }
                            } catch (Exception e) {}
                        }
                    } else {
                        filePath = Environment.getExternalStorageDirectory() + "/" + wholeID;
                    }
                }
            } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                String[] projection = {MediaStore.Files.FileColumns.DATA};
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(uri, projection, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
                        filePath = cursor.getString(columnIndex);
                    }
                } finally { if (cursor != null) cursor.close(); }
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                filePath = uri.getPath();
            }
            if (filePath == null) filePath = uri.getPath();
            if (filePath != null) {
                File file = new File(filePath);
                if (!file.exists()) return null;
            }
        } catch (Exception e) {}
        return filePath;
    }
    
    private boolean checkDoubleTap(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            long currentTime = System.currentTimeMillis();
            float currentX = event.getX();
            float currentY = event.getY();
            if (currentTime - lastClickTime < DOUBLE_TAP_TIME_THRESHOLD) {
                float distance = (float) Math.sqrt(
                    Math.pow(currentX - lastTapX, 2) + Math.pow(currentY - lastTapY, 2));
                if (distance < DOUBLE_TAP_MAX_DISTANCE) {
                    resetScale();
                    lastClickTime = 0;
                    return true;
                }
            }
            lastClickTime = currentTime;
            lastTapX = currentX;
            lastTapY = currentY;
        }
        return false;
    }
    
    private void showReaderView() {
        mainLayout.removeAllViews();
        readerContainer = new FrameLayout(this);
        readerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        readerContainer.setBackgroundColor(getBackgroundColor());
        
        pdfImageView = new FlipImageView(this);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        pdfImageView.setLayoutParams(imageParams);
        pdfImageView.setScaleType(ImageView.ScaleType.MATRIX);
        pdfImageView.setBackgroundColor(getBackgroundColor());
        
        scaleFactor = 1.0f;
        matrix.reset();
        savedMatrix.reset();
        mode = NONE;
        touchStartX = 0; touchStartY = 0; touchStartTime = 0;
        isClickCandidate = false; isSwiping = false;
        lastTapX = 0; lastTapY = 0; lastClickTime = 0;
        isFlipping = false;
        
        pdfImageView.setOnTouchListener((v, event) -> {
            ImageView view = (ImageView) v;
            if (checkDoubleTap(event)) return true;
            if (event.getPointerCount() == 2) {
                return handleZoomMode(view, event);
            }
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    return handleTouchDown(view, event);
                case MotionEvent.ACTION_MOVE:
                    return handleTouchMove(view, event);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    return handleTouchUp(view, event);
            }
            return true;
        });
        
        LinearLayout topBar = createReaderTopBar();
        topBar.setId(View.generateViewId());
        
        // ===== 构建底部统一控制栏 =====
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#B05D4037"));
        bottomBar.setPadding(10, 8, 10, 8);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        bottomBar.setLayoutParams(bottomParams);
        
        // 下一页按钮（左侧）
        bottomNextButton = new Button(this);
        bottomNextButton.setText("下一页");
        bottomNextButton.setBackgroundColor(getButtonBackgroundColor());
        bottomNextButton.setTextColor(getButtonTextColor());
        bottomNextButton.setTextSize(12);
        bottomNextButton.setAllCaps(false);
        setupButtonStyle(bottomNextButton, false);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        bottomNextButton.setLayoutParams(nextParams);
        bottomNextButton.setOnClickListener(v -> goToNextPage());
        
        // 中间页码（可点击跳转）
        bottomPageText = new TextView(this);
        bottomPageText.setTextColor(getTextColor());
        bottomPageText.setTextSize(14);
        bottomPageText.setGravity(Gravity.CENTER);
        bottomPageText.setPadding(10, 8, 10, 8);
        bottomPageText.setClickable(true);
        bottomPageText.setFocusable(true);
        bottomPageText.setOnClickListener(v -> showJumpPageDialog());
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f);
        bottomPageText.setLayoutParams(pageParams);
        
        // 上一页按钮（右侧）
        bottomPrevButton = new Button(this);
        bottomPrevButton.setText("上一页");
        bottomPrevButton.setBackgroundColor(getButtonBackgroundColor());
        bottomPrevButton.setTextColor(getButtonTextColor());
        bottomPrevButton.setTextSize(12);
        bottomPrevButton.setAllCaps(false);
        setupButtonStyle(bottomPrevButton, false);
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        bottomPrevButton.setLayoutParams(prevParams);
        bottomPrevButton.setOnClickListener(v -> goToPrevPage());
        
        bottomBar.addView(bottomNextButton);
        bottomBar.addView(bottomPageText);
        bottomBar.addView(bottomPrevButton);
        
        readerContainer.addView(pdfImageView);
        readerContainer.addView(topBar);
        readerContainer.addView(bottomBar);
        mainLayout.addView(readerContainer);
        
        pdfImageView.postDelayed(() -> displayCurrentPage(), 100);
    }
    
    private boolean handleZoomMode(ImageView view, MotionEvent event) {
        isClickCandidate = false;
        isSwiping = false;
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_POINTER_DOWN:
                oldDistance = spacing(event);
                if (oldDistance > 10f) {
                    savedMatrix.set(matrix);
                    midPoint(midPoint, event);
                    mode = ZOOM;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == ZOOM) {
                    float newDist = spacing(event);
                    if (newDist > 10f) {
                        matrix.set(savedMatrix);
                        float scale = newDist / oldDistance;
                        scaleFactor *= scale;
                        if (scaleFactor < minScale) { scaleFactor = minScale; scale = minScale / (scaleFactor / scale); }
                        else if (scaleFactor > maxScale) { scaleFactor = maxScale; scale = maxScale / (scaleFactor / scale); }
                        matrix.postScale(scale, scale, midPoint.x, midPoint.y);
                        limitDragWithBoundary();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
                if (scaleFactor > 1.01f) limitDragWithBoundary();
                else centerImage();
                break;
        }
        view.setImageMatrix(matrix);
        return true;
    }
    
    private boolean handleTouchDown(ImageView view, MotionEvent event) {
        touchStartX = event.getX();
        touchStartY = event.getY();
        touchStartTime = System.currentTimeMillis();
        if (scaleFactor > 1.01f) {
            isClickCandidate = false;
            isSwiping = false;
            savedMatrix.set(matrix);
            startPoint.set(touchStartX, touchStartY);
            mode = DRAG;
            return true;
        } else {
            isClickCandidate = true;
            isSwiping = false;
            mode = NONE;
        }
        return true;
    }
    
    private boolean handleTouchMove(ImageView view, MotionEvent event) {
        if (scaleFactor > 1.01f && mode == DRAG) {
            matrix.set(savedMatrix);
            float dx = event.getX() - startPoint.x;
            float dy = event.getY() - startPoint.y;
            matrix.postTranslate(dx, dy);
            limitDragWithBoundary();
            view.setImageMatrix(matrix);
            return true;
        }
        float currentX = event.getX();
        float currentY = event.getY();
        float dx = Math.abs(currentX - touchStartX);
        float dy = Math.abs(currentY - touchStartY);
        if (dx > SWIPE_MIN_DISTANCE && dx > dy * SWIPE_VS_SCROLL_RATIO) {
            isClickCandidate = false;
            isSwiping = true;
        }
        return true;
    }
    
    private boolean handleTouchUp(ImageView view, MotionEvent event) {
        float endX = event.getX();
        float endY = event.getY();
        long duration = System.currentTimeMillis() - touchStartTime;
        float distance = (float) Math.sqrt(
            Math.pow(endX - touchStartX, 2) + Math.pow(endY - touchStartY, 2));
        if (scaleFactor > 1.01f && mode == DRAG) {
            mode = NONE;
            return true;
        }
        if (isClickCandidate && duration < CLICK_MAX_TIME && distance < CLICK_MAX_DISTANCE) {
            handleClick(endX, endY, view.getWidth(), view.getHeight());
        } else if (isSwiping) {
            handleSwipe(touchStartX, endX);
        }
        isClickCandidate = false;
        isSwiping = false;
        mode = NONE;
        return true;
    }
    
    private void handleClick(float clickX, float clickY, float viewWidth, float viewHeight) {
        if (isRotated) {
            float third = viewHeight / 3;
            if (clickY < third) {
                goToNextPage();
            } else if (clickY > 2 * third) {
                goToPrevPage();
            } else {
                toggleControls();
            }
        } else {
            float third = viewWidth / 3;
            if (clickX < third) {
                goToNextPage();
            } else if (clickX > 2 * third) {
                goToPrevPage();
            } else {
                toggleControls();
            }
        }
    }
    
    private void handleSwipe(float startX, float endX) {
        float dx = endX - startX;
        if (dx > SWIPE_MIN_DISTANCE) {
            goToNextPage();
        } else if (dx < -SWIPE_MIN_DISTANCE) {
            goToPrevPage();
        }
    }
    
    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }
    
    private void midPoint(PointF point, MotionEvent event) {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }
    
    private void limitDragWithBoundary() {
        float[] values = new float[9];
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        int viewWidth = pdfImageView.getWidth();
        int viewHeight = pdfImageView.getHeight();
        BitmapDrawable drawable = (BitmapDrawable) pdfImageView.getDrawable();
        if (drawable == null) return;
        Bitmap bitmap = drawable.getBitmap();
        if (bitmap == null) return;
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        float scaledWidth = bitmapWidth * scale;
        float scaledHeight = bitmapHeight * scale;
        float minTransX, maxTransX, minTransY, maxTransY;
        if (scaledWidth > viewWidth) {
            minTransX = viewWidth - scaledWidth;
            maxTransX = 0;
        } else {
            minTransX = maxTransX = (viewWidth - scaledWidth) / 2;
        }
        if (scaledHeight > viewHeight) {
            minTransY = viewHeight - scaledHeight;
            maxTransY = 0;
        } else {
            minTransY = maxTransY = (viewHeight - scaledHeight) / 2;
        }
        if (transX < minTransX) transX = minTransX;
        else if (transX > maxTransX) transX = maxTransX;
        if (transY < minTransY) transY = minTransY;
        else if (transY > maxTransY) transY = maxTransY;
        values[Matrix.MTRANS_X] = transX;
        values[Matrix.MTRANS_Y] = transY;
        matrix.setValues(values);
    }
    
    private void centerImage() {
        if (pdfImageView == null) return;
        if (pdfImageView.getWidth() == 0 || pdfImageView.getHeight() == 0) {
            pdfImageView.postDelayed(() -> centerImage(), 50);
            return;
        }
        BitmapDrawable drawable = (BitmapDrawable) pdfImageView.getDrawable();
        if (drawable == null) return;
        Bitmap bitmap = drawable.getBitmap();
        if (bitmap == null) return;
        int viewWidth = pdfImageView.getWidth();
        int viewHeight = pdfImageView.getHeight();
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        float[] values = new float[9];
        matrix.getValues(values);
        float currentScale = values[Matrix.MSCALE_X];
        if (Math.abs(scaleFactor - 1.0f) < 0.01f) {
            float scaleX = (float) viewWidth / bitmapWidth;
            float scaleY = (float) viewHeight / bitmapHeight;
            float scale = Math.min(scaleX, scaleY);
            matrix.postScale(scale, scale);
            scaleFactor = scale;
            matrix.getValues(values);
            currentScale = values[Matrix.MSCALE_X];
        }
        float scaledWidth = bitmapWidth * currentScale;
        float scaledHeight = bitmapHeight * currentScale;
        float dx = (viewWidth - scaledWidth) / 2f;
        float dy = (viewHeight - scaledHeight) / 2f;
        values[Matrix.MTRANS_X] = dx;
        values[Matrix.MTRANS_Y] = dy;
        matrix.setValues(values);
        limitDragWithBoundary();
        pdfImageView.setImageMatrix(matrix);
        pdfImageView.invalidate();
    }
    
    private void resetScale() {
        scaleFactor = 1.0f;
        matrix.reset();
        centerImage();
        pdfImageView.invalidate();
        Toast.makeText(this, "已恢复原始大小", Toast.LENGTH_SHORT).show();
    }
    
    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(0, 8, 0, 8);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        topBar.setLayoutParams(params);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        
        Button backBtn = new Button(this);
        backBtn.setText("返回");
        backBtn.setBackgroundColor(getButtonBackgroundColor());
        backBtn.setTextColor(getButtonTextColor());
        backBtn.setTextSize(11);
        backBtn.setAllCaps(false);
        backBtn.setLayoutParams(btnParams);
        backBtn.setOnClickListener(v -> goBackToFileList());
        setupButtonStyle(backBtn, false);
        
        Button nightBtn = new Button(this);
        nightBtn.setText(nightMode ? "日间" : "夜间");
        nightBtn.setBackgroundColor(getButtonBackgroundColor());
        nightBtn.setTextColor(getButtonTextColor());
        nightBtn.setTextSize(11);
        nightBtn.setAllCaps(false);
        nightBtn.setLayoutParams(btnParams);
        nightBtn.setOnClickListener(v -> toggleNightMode());
        setupButtonStyle(nightBtn, false);
        
        halfPageBtn = new Button(this);
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        halfPageBtn.setBackgroundColor(getButtonBackgroundColor());
        halfPageBtn.setTextColor(getButtonTextColor());
        halfPageBtn.setTextSize(11);
        halfPageBtn.setAllCaps(false);
        halfPageBtn.setLayoutParams(btnParams);
        halfPageBtn.setOnClickListener(v -> toggleHalfPageMode());
        setupButtonStyle(halfPageBtn, false);
        
        pageModeBtn = new Button(this);
        pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        pageModeBtn.setBackgroundColor(getButtonBackgroundColor());
        pageModeBtn.setTextColor(getButtonTextColor());
        pageModeBtn.setTextSize(11);
        pageModeBtn.setAllCaps(false);
        pageModeBtn.setLayoutParams(btnParams);
        pageModeBtn.setOnClickListener(v -> toggleDoublePageMode());
        setupButtonStyle(pageModeBtn, false);
        
        rotateBtn = new Button(this);
        rotateBtn.setText(isRotated ? "转回" : "旋转");
        rotateBtn.setBackgroundColor(getButtonBackgroundColor());
        rotateBtn.setTextColor(getButtonTextColor());
        rotateBtn.setTextSize(11);
        rotateBtn.setAllCaps(false);
        rotateBtn.setLayoutParams(btnParams);
        rotateBtn.setOnClickListener(v -> toggleRotation());
        setupButtonStyle(rotateBtn, false);
        
        flipModeBtn = new Button(this);
        flipModeBtn.setText(flipPageMode ? "平滑" : "预载");
        flipModeBtn.setBackgroundColor(getButtonBackgroundColor());
        flipModeBtn.setTextColor(getButtonTextColor());
        flipModeBtn.setTextSize(11);
        flipModeBtn.setAllCaps(false);
        flipModeBtn.setLayoutParams(btnParams);
        flipModeBtn.setOnClickListener(v -> toggleFlipPageMode());
        setupButtonStyle(flipModeBtn, false);
        
        topBar.addView(backBtn);
        topBar.addView(nightBtn);
        topBar.addView(halfPageBtn);
        topBar.addView(pageModeBtn);
        topBar.addView(rotateBtn);
        topBar.addView(flipModeBtn);
        return topBar;
    }
    
    private void toggleFlipPageMode() {
        flipPageMode = !flipPageMode;
        isFlipping = false;
        if (flipModeBtn != null) flipModeBtn.setText(flipPageMode ? "平滑" : "预载");
        if (!flipPageMode) {
            clearPageCache();
        } else if (pdfRenderer != null && !cacheInitialized) {
            initPageCache();
        }
        saveSettings();
        Toast.makeText(this, flipPageMode ? "已启用预载模式（快速翻页）" : "已启用平滑翻页", Toast.LENGTH_SHORT).show();
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
            centerImage();
            pdfImageView.invalidate();
            currentPage++;
            if (halfPageMode) leftPage = true;
            updatePageNumberText();
            new android.os.Handler().postDelayed(() -> {
                updatePageCacheAfterFlip(true);
                saveReadingPosition();
                isFlipping = false;
            }, 50);
        } else if (!forward && prevPageCache != null) {
            pdfImageView.setImageBitmap(prevPageCache);
            centerImage();
            pdfImageView.invalidate();
            currentPage--;
            if (halfPageMode) leftPage = false;
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
    
    private void goToPrevPage() {
        if (flipPageMode && cacheInitialized) {
            performCachedFlip(false);
        } else {
            originalGoToPrevPage();
        }
    }
    
    private void goToNextPage() {
        if (flipPageMode && cacheInitialized) {
            performCachedFlip(true);
        } else {
            originalGoToNextPage();
        }
    }
    
    private void originalGoToPrevPage() {
        if (scaleFactor > 1.01f) resetScale();
        if (doublePageMode) {
            if (currentPage > 1) currentPage -= 2;
            else { currentPage = 0; Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show(); }
        } else if (halfPageMode) {
            if (leftPage) leftPage = false;
            else {
                if (currentPage > 0) { currentPage--; leftPage = true; }
                else { Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show(); }
            }
        } else {
            if (currentPage > 0) currentPage--;
        }
        displayCurrentPage();
    }
    
    private void originalGoToNextPage() {
        if (scaleFactor > 1.01f) resetScale();
        if (doublePageMode) {
            if (currentPage < totalPages - 1) {
                currentPage += 2;
                if (currentPage >= totalPages) currentPage = totalPages - 1;
            } else Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
        } else if (halfPageMode) {
            if (leftPage) {
                if (currentPage < totalPages - 1) { currentPage++; leftPage = false; }
                else Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
            } else leftPage = true;
        } else {
            if (currentPage < totalPages - 1) currentPage++;
        }
        displayCurrentPage();
    }
    
    private void toggleRotation() {
        isRotated = !isRotated;
        if (rotateBtn != null) rotateBtn.setText(isRotated ? "转回" : "旋转");
        saveSettings();
        if (flipPageMode) {
            clearPageCache();
            pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
        if (pdfRenderer != null) displayCurrentPage();
    }
    
    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        if (halfPageBtn != null) halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        saveSettings();
        if (flipPageMode) {
            clearPageCache();
            pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
        if (pdfRenderer != null) displayCurrentPage();
    }
    
    private void toggleDoublePageMode() {
        doublePageMode = !doublePageMode;
        if (pageModeBtn != null) pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) displayCurrentPage();
    }
    
    private void toggleControls() {
        controlsVisible = !controlsVisible;
        View topBar = readerContainer.getChildAt(1); // 顶部栏索引1
        if (controlsVisible) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
        } else {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
        }
    }
    
    private void showJumpPageDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("跳转到指定页面");
        builder.setMessage("输入页面 (1 - " + totalPages + "):");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentPage + 1));
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                String pageStr = input.getText().toString().trim();
                if (!pageStr.isEmpty()) {
                    int pageNum = Integer.parseInt(pageStr);
                    if (pageNum >= 1 && pageNum <= totalPages) {
                        currentPage = pageNum - 1;
                        if (halfPageMode) leftPage = true;
                        if (flipPageMode) clearPageCache();
                        displayCurrentPage();
                    } else {
                        Toast.makeText(MainActivity.this, "页面范围应为 1 - " + totalPages, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (NumberFormatException e) {
                Toast.makeText(MainActivity.this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }
    
    private Bitmap invertColors(Bitmap bitmap) {
        if (bitmap == null) return null;
        Bitmap invertedBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(invertedBitmap);
        ColorMatrix colorMatrix = new ColorMatrix(new float[] {
            -1, 0, 0, 0, 255,
            0, -1, 0, 0, 255,
            0, 0, -1, 0, 255,
            0, 0, 0, 1, 0
        });
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return invertedBitmap;
    }
    
    private Bitmap rotateBitmap90(Bitmap bitmap) {
        if (bitmap == null) return null;
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }
    
    private void updatePageNumberText() {
        if (halfPageMode) {
            if (leftPage) bottomPageText.setText((currentPage + 1) + "/" + totalPages + " (左)");
            else bottomPageText.setText((currentPage + 1) + "/" + totalPages + " (右)");
        } else {
            bottomPageText.setText((currentPage + 1) + "/" + totalPages);
        }
    }
    
    private void displayCurrentPage() {
        if (pdfRenderer == null) return;
        try {
            if (doublePageMode) {
                int basePage = currentPage;
                if (basePage % 2 != 0) basePage--;
                int rightPageNum = basePage;
                int leftPageNum = basePage + 1;
                if (rightPageNum >= totalPages) rightPageNum = totalPages - 1;
                if (leftPageNum >= totalPages) leftPageNum = totalPages - 1;
                showDoublePage(leftPageNum, rightPageNum);
            } else {
                if (flipPageMode && cacheInitialized && cachedCurrentPage == currentPage) {
                    if (currentPageCache != null) {
                        pdfImageView.setImageBitmap(currentPageCache);
                        centerImage();
                        pdfImageView.invalidate();
                        updatePageNumberText();
                        saveReadingPosition();
                        return;
                    }
                }
                PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
                int pageWidth = page.getWidth();
                int pageHeight = page.getHeight();
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                if (isRotated) {
                    int temp = screenWidth; screenWidth = screenHeight; screenHeight = temp;
                }
                float scale = Math.min(
                    (float) screenWidth / pageWidth,
                    (float) screenHeight / pageHeight
                );
                if (halfPageMode) {
                    scale = Math.min(
                        (float) screenWidth / (pageWidth / 2),
                        (float) screenHeight / pageHeight
                    );
                }
                int highResWidth = Math.max((int)(pageWidth * scale * 4), 1);
                int highResHeight = Math.max((int)(pageHeight * scale * 4), 1);
                Bitmap highResBitmap = Bitmap.createBitmap(highResWidth, highResHeight, Bitmap.Config.ARGB_8888);
                page.render(highResBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                int scaledWidth = (int) (pageWidth * scale);
                int scaledHeight = (int) (pageHeight * scale);
                Bitmap bitmap = Bitmap.createScaledBitmap(highResBitmap, scaledWidth, scaledHeight, true);
                if (!highResBitmap.isRecycled() && highResBitmap != bitmap) highResBitmap.recycle();
                if (halfPageMode) {
                    if (leftPage) {
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, scaledWidth / 2, scaledHeight);
                    } else {
                        bitmap = Bitmap.createBitmap(bitmap, scaledWidth / 2, 0, scaledWidth / 2, scaledHeight);
                    }
                }
                if (nightMode) bitmap = invertColors(bitmap);
                if (isRotated) bitmap = rotateBitmap90(bitmap);
                pdfImageView.setImageBitmap(bitmap);
                pdfImageView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                pdfImageView.invalidate();
                scaleFactor = 1.0f;
                matrix.reset();
                updatePageNumberText();
                pdfImageView.postDelayed(() -> centerImage(), 100);
            }
            saveReadingPosition();
            if (flipPageMode && !cacheInitialized) {
                pdfImageView.postDelayed(() -> initPageCache(), 300);
            }
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showDoublePage(int leftPageNum, int rightPageNum) {
        try {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            if (isRotated) {
                int temp = screenWidth; screenWidth = screenHeight; screenHeight = temp;
            }
            Bitmap doubleBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(doubleBitmap);
            canvas.drawColor(getBackgroundColor());
            float unifiedScale = 1.0f;
            int unifiedScaledHeight = 0;
            int leftScaledWidth = 0, rightScaledWidth = 0;
            int leftPageWidth = 0, leftPageHeight = 0;
            int rightPageWidth = 0, rightPageHeight = 0;
            if (leftPageNum < totalPages) {
                PdfRenderer.Page leftPage = pdfRenderer.openPage(leftPageNum);
                leftPageWidth = leftPage.getWidth();
                leftPageHeight = leftPage.getHeight();
                leftPage.close();
            }
            if (rightPageNum < totalPages) {
                PdfRenderer.Page rightPage = pdfRenderer.openPage(rightPageNum);
                rightPageWidth = rightPage.getWidth();
                rightPageHeight = rightPage.getHeight();
                rightPage.close();
            }
            int maxPageHeight = Math.max(leftPageHeight, rightPageHeight);
            float scaleByHeight = (float) (screenHeight * 0.95) / maxPageHeight;
            int totalPageWidth = leftPageWidth + rightPageWidth;
            float scaleByWidth = (float) (screenWidth * 0.95) / totalPageWidth;
            unifiedScale = Math.min(scaleByHeight, scaleByWidth);
            unifiedScaledHeight = (int) (maxPageHeight * unifiedScale);
            leftScaledWidth = (int) (leftPageWidth * unifiedScale);
            rightScaledWidth = (int) (rightPageWidth * unifiedScale);
            int totalScaledWidth = leftScaledWidth + rightScaledWidth;
            int startX = (screenWidth - totalScaledWidth) / 2;
            int startY = (screenHeight - unifiedScaledHeight) / 2;
            if (leftPageNum < totalPages) {
                PdfRenderer.Page leftPage = pdfRenderer.openPage(leftPageNum);
                Bitmap leftBitmap = Bitmap.createBitmap(
                    Math.max((int)(leftPageWidth * unifiedScale * 4), 1),
                    Math.max((int)(leftPageHeight * unifiedScale * 4), 1),
                    Bitmap.Config.ARGB_8888
                );
                leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                leftPage.close();
                leftBitmap = Bitmap.createScaledBitmap(leftBitmap, leftScaledWidth, unifiedScaledHeight, true);
                if (nightMode) leftBitmap = invertColors(leftBitmap);
                canvas.drawBitmap(leftBitmap, startX, startY, null);
            }
            if (rightPageNum < totalPages) {
                PdfRenderer.Page rightPage = pdfRenderer.openPage(rightPageNum);
                Bitmap rightBitmap = Bitmap.createBitmap(
                    Math.max((int)(rightPageWidth * unifiedScale * 4), 1),
                    Math.max((int)(rightPageHeight * unifiedScale * 4), 1),
                    Bitmap.Config.ARGB_8888
                );
                rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                rightPage.close();
                rightBitmap = Bitmap.createScaledBitmap(rightBitmap, rightScaledWidth, unifiedScaledHeight, true);
                if (nightMode) rightBitmap = invertColors(rightBitmap);
                canvas.drawBitmap(rightBitmap, startX + leftScaledWidth, startY, null);
            }
            if (isRotated) doubleBitmap = rotateBitmap90(doubleBitmap);
            pdfImageView.setImageBitmap(doubleBitmap);
            if (leftPageNum < totalPages) {
                bottomPageText.setText((leftPageNum + 1) + "," + (rightPageNum + 1) + "/" + totalPages);
            } else {
                bottomPageText.setText((leftPageNum + 1) + "/" + totalPages);
            }
            pdfImageView.invalidate();
            pdfImageView.postDelayed(() -> centerImage(), 100);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void toggleNightMode() {
        nightMode = !nightMode;
        if (nightModeBtn != null) nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        saveSettings();
        updateThemeColors();
        if (flipPageMode && cacheInitialized) {
            clearPageCache();
            pdfImageView.postDelayed(() -> initPageCache(), 300);
        }
        if (pdfRenderer != null) {
            if (readerContainer != null) readerContainer.setBackgroundColor(getBackgroundColor());
            if (pdfImageView != null) pdfImageView.setBackgroundColor(getBackgroundColor());
            if (bottomPageText != null) bottomPageText.setTextColor(getTextColor());
            if (readerContainer.getChildAt(1) != null) readerContainer.getChildAt(1).setBackgroundColor(getStatusBarColor());
            displayCurrentPage();
        }
    }
    
    private void closePdf() {
        clearPageCache();
        if (pdfRenderer != null) pdfRenderer.close();
        if (fileDescriptor != null) {
            try { fileDescriptor.close(); } catch (IOException e) {}
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (requestCode == FILE_PICKER_REQUEST_CODE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        try {
                            final int takeFlags = data.getFlags() & 
                                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        } catch (SecurityException e) {}
                    }
                    String filePath = getRealPathFromUri(uri);
                    if (filePath != null && new File(filePath).exists()) {
                        openPdfFile(filePath);
                    } else {
                        openPdfFromUri(uri);
                    }
                }
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
        if (currentFilePath != null && currentFilePath.contains("temp_pdf_")) {
            File tempFile = new File(currentFilePath);
            if (tempFile.exists()) tempFile.delete();
        }
    }
}
