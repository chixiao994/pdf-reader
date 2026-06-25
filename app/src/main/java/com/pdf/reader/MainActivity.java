package com.pdf.reader;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.ContentResolver;
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
import android.graphics.Rect;
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

    // ==================== 原视图变量 ====================
    private LinearLayout mainLayout, fileListLayout;
    private FrameLayout readerContainer;
    private ImageView pdfImageView;
    private Button nightModeBtn, halfPageBtn, pageModeBtn, openFileBtn, refreshBtn, rotateBtn, flipModeBtn;

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
    private boolean flipPageMode = false;
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

    // 点击和滑动
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

    private SharedPreferences prefs;
    private static final String LAST_OPENED_FILE = "last_opened_file";
    private static final String AUTO_OPEN_LAST_FILE = "auto_open_last_file";
    private static final String FIRST_RUN = "first_run";

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
    private static final int DAY_STATUS_BAR_COLOR = Color.parseColor("#5D4037");
    private static final int NIGHT_STATUS_BAR_COLOR = Color.parseColor("#2C2C2C");

    // 底部栏
    private LinearLayout bottomBar;
    private Button bottomPrevButton;
    private Button bottomNextButton;
    private TextView bottomPageText;

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

    private void loadSettings() {
        nightMode = prefs.getBoolean("night_mode", false);
        halfPageMode = prefs.getBoolean("half_page", false);
        doublePageMode = prefs.getBoolean("double_page", false);
        isRotated = prefs.getBoolean("is_rotated", false);
        flipPageMode = prefs.getBoolean("flip_page_mode", false);
    }

    private void saveSettings() {
        prefs.edit().putBoolean("night_mode", nightMode)
                .putBoolean("half_page", halfPageMode)
                .putBoolean("double_page", doublePageMode)
                .putBoolean("is_rotated", isRotated)
                .putBoolean("flip_page_mode", flipPageMode).apply();
    }

    private void saveLastOpenedFile(String filePath) {
        if (filePath != null) prefs.edit().putString(LAST_OPENED_FILE, filePath).apply();
    }

    private void saveReadingPosition() {
        if (currentFilePath != null) {
            prefs.edit().putInt(currentFilePath + "_page", currentPage)
                    .putInt(currentFilePath + "_half_page_left", leftPage ? 1 : 0).apply();
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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        updateThemeColors();
        setContentView(mainLayout);
    }

    private void updateThemeColors() {
        if (mainLayout != null)
            mainLayout.setBackgroundColor(nightMode ? NIGHT_MODE_BG : DAY_MODE_BG);
    }

    private int getStatusBarColor() { return nightMode ? NIGHT_STATUS_BAR_COLOR : DAY_STATUS_BAR_COLOR; }
    private int getTextColor() { return nightMode ? NIGHT_MODE_TEXT : DAY_MODE_TEXT; }
    private int getBackgroundColor() { return nightMode ? NIGHT_MODE_BG : DAY_MODE_BG; }
    private int getButtonBackgroundColor() { return nightMode ? ANCIENT_GREEN : ANCIENT_BROWN; }
    private int getButtonTextColor() { return nightMode ? ANCIENT_BEIGE : ANCIENT_GOLD; }
    private int getSpecialButtonBackgroundColor() { return ANCIENT_RED; }
    private int getSpecialButtonTextColor() { return Color.WHITE; }

    private void requestPermissionsOnFirstRun() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("简帙阅读器 v1.0.24 需要访问您的存储空间来扫描和读取PDF文件。")
                        .setPositiveButton("允许", (d, w) -> requestPermissions(
                                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                                PERMISSION_REQUEST_CODE))
                        .setNegativeButton("不允许", (d, w) -> showFileListWithoutScan())
                        .setCancelable(false).show();
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                showFileList();
            else showFileListWithoutScan();
        }
    }

    private void checkAutoOpenLastFile() {
        String last = prefs.getString(LAST_OPENED_FILE, null);
        boolean auto = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true);
        if (auto && last != null && !last.isEmpty()) {
            File f = new File(last);
            if (f.exists() && f.canRead()) {
                createMainLayout();
                new android.os.Handler().postDelayed(() -> openPdfFile(last), 100);
                return;
            } else prefs.edit().remove(LAST_OPENED_FILE).apply();
        }
        createMainLayout();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) showFileListWithoutScan();
            else showFileList();
        } else showFileList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pdfRenderer == null) {
            createMainLayout();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) showFileListWithoutScan();
                else showFileList();
            } else showFileList();
        } else {
            if (pdfImageView != null) {
                pdfImageView.postDelayed(() -> { pdfImageView.invalidate(); centerImage(); }, 200);
            }
            if (flipPageMode && !cacheInitialized) pdfImageView.postDelayed(this::initPageCache, 500);
        }
    }

    // ==================== 文件列表相关 ====================
    private void showFileListWithoutScan() {
        mainLayout.removeAllViews();
        LinearLayout topBar = createTopBar();
        fileListLayout = new LinearLayout(this);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        fileListLayout.setPadding(20, 20, 20, 20);
        TextView noPerm = new TextView(this);
        noPerm.setText("存储权限未授予\n\n无法自动扫描PDF文件\n\n请点击下方手动选择PDF文件");
        noPerm.setTextSize(16); noPerm.setGravity(Gravity.CENTER);
        noPerm.setTextColor(getTextColor()); noPerm.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noPerm);
        openFileBtn = new Button(this);
        openFileBtn.setText("选择PDF文件");
        openFileBtn.setBackgroundColor(getSpecialButtonBackgroundColor());
        openFileBtn.setTextColor(getSpecialButtonTextColor());
        openFileBtn.setTextSize(14); openFileBtn.setAllCaps(false);
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
        String last = prefs.getString(LAST_OPENED_FILE, null);
        if (last != null && !last.isEmpty()) {
            File f = new File(last);
            if (f.exists() && f.canRead()) {
                Button btn = new Button(this);
                btn.setText("继续阅读: " + getShortFileName(f.getName()));
                btn.setBackgroundColor(getSpecialButtonBackgroundColor());
                btn.setTextColor(getSpecialButtonTextColor());
                btn.setTextSize(14); btn.setAllCaps(false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = 20;
                btn.setLayoutParams(params);
                setupButtonStyle(btn, true);
                btn.setOnClickListener(v -> openPdfFile(last));
                fileListLayout.addView(btn);
            }
        }
    }

    private String getShortFileName(String name) {
        return name.length() > 20 ? name.substring(0, 17) + "..." : name;
    }

    private LinearLayout createTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(20, 15, 20, 15);
        TextView title = new TextView(this);
        title.setText("简帙阅读器 v1.0.24");
        title.setTextColor(nightMode ? ANCIENT_BEIGE : ANCIENT_GOLD);
        title.setTextSize(18);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        nightModeBtn = new Button(this);
        nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        nightModeBtn.setBackgroundColor(getButtonBackgroundColor());
        nightModeBtn.setTextColor(getButtonTextColor());
        nightModeBtn.setTextSize(12); nightModeBtn.setAllCaps(false);
        setupButtonStyle(nightModeBtn, false);
        refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setBackgroundColor(getButtonBackgroundColor());
        refreshBtn.setTextColor(getButtonTextColor());
        refreshBtn.setTextSize(12); refreshBtn.setAllCaps(false);
        setupButtonStyle(refreshBtn, false);
        topBar.addView(title);
        topBar.addView(nightModeBtn);
        topBar.addView(refreshBtn);
        return topBar;
    }

    private void setupButtonStyle(Button btn, boolean large) {
        btn.setPadding(large ? 30 : 15, large ? 20 : 10, large ? 30 : 15, large ? 20 : 10);
        btn.setTextSize(large ? 14 : 12);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) btn.setElevation(4);
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
            File downDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downDir.exists() && downDir.isDirectory()) {
                File[] files = downDir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
                if (files != null && files.length > 0) {
                    for (File f : files) addFileButton(f);
                } else showNoFilesMessage();
            } else showNoFilesMessage();
        } catch (SecurityException e) {
            showNoFilesMessage();
        }
        addFileChooserOptions();
    }

    private void addFileButton(File file) {
        Button btn = new Button(this);
        String name = getShortFileName(file.getName());
        int lastPage = getReadingPosition(file.getAbsolutePath());
        if (lastPage > 0) name += " (读到第" + (lastPage + 1) + "页)";
        btn.setText(name);
        btn.setBackgroundColor(getButtonBackgroundColor());
        btn.setTextColor(getButtonTextColor());
        btn.setTextSize(14); btn.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 10;
        btn.setLayoutParams(params);
        setupButtonStyle(btn, true);
        btn.setOnClickListener(v -> openPdfFile(file.getAbsolutePath()));
        fileListLayout.addView(btn);
    }

    private void showNoFilesMessage() {
        TextView tv = new TextView(this);
        tv.setText("未找到PDF文件\n\n请将PDF文件放置在：\n手机存储 → Download文件夹\n\n或者使用下方选项选择文件");
        tv.setTextSize(16); tv.setGravity(Gravity.CENTER);
        tv.setTextColor(getTextColor()); tv.setPadding(0, 50, 0, 50);
        fileListLayout.addView(tv);
    }

    private void addFileChooserOptions() {
        LinearLayout optionsLayout = new LinearLayout(this);
        optionsLayout.setOrientation(LinearLayout.VERTICAL);
        optionsLayout.setPadding(0, 20, 0, 0);

        Button singleBtn = new Button(this);
        singleBtn.setText("选择单个PDF文件");
        singleBtn.setBackgroundColor(getButtonBackgroundColor());
        singleBtn.setTextColor(getButtonTextColor());
        singleBtn.setTextSize(14); singleBtn.setAllCaps(false);
        singleBtn.setOnClickListener(v -> choosePdfFile());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = 10; singleBtn.setLayoutParams(sp);
        setupButtonStyle(singleBtn, true);
        optionsLayout.addView(singleBtn);

        Button scanAllBtn = new Button(this);
        scanAllBtn.setText("扫描全盘PDF文件");
        scanAllBtn.setBackgroundColor(getButtonBackgroundColor());
        scanAllBtn.setTextColor(getButtonTextColor());
        scanAllBtn.setTextSize(14); scanAllBtn.setAllCaps(false);
        scanAllBtn.setOnClickListener(v -> scanAllPdfFiles());
        LinearLayout.LayoutParams sap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sap.bottomMargin = 10; scanAllBtn.setLayoutParams(sap);
        setupButtonStyle(scanAllBtn, true);
        optionsLayout.addView(scanAllBtn);

        Button tianlangBtn = new Button(this);
        tianlangBtn.setText("天朗裁切");
        tianlangBtn.setBackgroundColor(getButtonBackgroundColor());
        tianlangBtn.setTextColor(getButtonTextColor());
        tianlangBtn.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, TianLangActivity.class)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = 10; tianlangBtn.setLayoutParams(tlp);
        setupButtonStyle(tianlangBtn, true);
        optionsLayout.addView(tianlangBtn);

        Button hejuBtn = new Button(this);
        hejuBtn.setText("合矩");
        hejuBtn.setBackgroundColor(getButtonBackgroundColor());
        hejuBtn.setTextColor(getButtonTextColor());
        hejuBtn.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HejuActivity.class)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = 10; hejuBtn.setLayoutParams(hlp);
        setupButtonStyle(hejuBtn, true);
        optionsLayout.addView(hejuBtn);

        fileListLayout.addView(optionsLayout);
    }

    private void choosePdfFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    private void scanAllPdfFiles() {
        fileListLayout.removeAllViews();
        TextView scanningText = new TextView(this);
        scanningText.setText("正在扫描全盘PDF文件，请稍候...");
        scanningText.setTextSize(16); scanningText.setGravity(Gravity.CENTER);
        scanningText.setTextColor(getTextColor()); scanningText.setPadding(0, 50, 0, 50);
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
            } catch (Exception e) { Log.e("PDF_DEBUG", "扫描错误: " + e.getMessage()); }
            runOnUiThread(() -> {
                fileListLayout.removeAllViews();
                addContinueReadingButton();
                if (pdfFiles.isEmpty()) showNoFilesMessage();
                else for (File f : pdfFiles) addFileButton(f);
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
            if (!file.exists() || !file.canRead()) {
                Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show();
                return;
            }
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
        } catch (IOException e) {
            Toast.makeText(this, "打开PDF失败", Toast.LENGTH_SHORT).show();
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
                if (in == null) return;
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            }
            openPdfFile(tempFile.getAbsolutePath());
            if (currentFilePath != null && currentFilePath.contains("temp_pdf_")) new File(currentFilePath).delete();
            currentFilePath = tempFile.getAbsolutePath();
        } catch (IOException e) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show();
        }
    }

    private String getRealPathFromUri(Uri uri) {
        String filePath = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(this, uri)) {
                String wholeID = DocumentsContract.getDocumentId(uri);
                String[] split = wholeID.split(":");
                if (split.length > 1) {
                    String type = split[0], id = split[1];
                    if ("primary".equalsIgnoreCase(type)) filePath = Environment.getExternalStorageDirectory() + "/" + id;
                } else filePath = Environment.getExternalStorageDirectory() + "/" + wholeID;
            } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.Files.FileColumns.DATA}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA));
                }
            } else if ("file".equalsIgnoreCase(uri.getScheme())) filePath = uri.getPath();
        } catch (Exception e) { /* ignore */ }
        return filePath;
    }

    // ==================== 阅读器视图构建 ====================
    private void showReaderView() {
        mainLayout.removeAllViews();
        readerContainer = new FrameLayout(this);
        readerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        readerContainer.setBackgroundColor(getBackgroundColor());

        pdfImageView = new ImageView(this);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        pdfImageView.setLayoutParams(imageParams);
        pdfImageView.setScaleType(ImageView.ScaleType.MATRIX);

        scaleFactor = 1.0f; matrix.reset(); savedMatrix.reset();
        mode = NONE; isFlipping = false;

        pdfImageView.setOnTouchListener((v, e) -> {
            if (e.getPointerCount() == 2) return handleZoomMode((ImageView) v, e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: return handleTouchDown((ImageView) v, e);
                case MotionEvent.ACTION_MOVE: return handleTouchMove((ImageView) v, e);
                case MotionEvent.ACTION_UP: return handleTouchUp((ImageView) v, e);
            }
            return true;
        });

        LinearLayout topBar = createReaderTopBar();
        topBar.setId(View.generateViewId());

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#B05D4037"));
        bottomBar.setPadding(10, 8, 10, 8);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        bottomBar.setLayoutParams(bottomParams);

        bottomNextButton = new Button(this);
        bottomNextButton.setText("下一页");
        bottomNextButton.setBackgroundColor(getButtonBackgroundColor());
        bottomNextButton.setTextColor(getButtonTextColor());
        bottomNextButton.setTextSize(12); bottomNextButton.setAllCaps(false);
        setupButtonStyle(bottomNextButton, false);
        bottomNextButton.setOnClickListener(v -> goToNextPage());
        LinearLayout.LayoutParams btnParam = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        bottomNextButton.setLayoutParams(btnParam);

        bottomPageText = new TextView(this);
        bottomPageText.setTextColor(getTextColor());
        bottomPageText.setTextSize(14); bottomPageText.setGravity(Gravity.CENTER);
        bottomPageText.setPadding(10, 8, 10, 8);
        bottomPageText.setClickable(true);
        bottomPageText.setOnClickListener(v -> showJumpPageDialog());
        bottomPageText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f));

        bottomPrevButton = new Button(this);
        bottomPrevButton.setText("上一页");
        bottomPrevButton.setBackgroundColor(getButtonBackgroundColor());
        bottomPrevButton.setTextColor(getButtonTextColor());
        bottomPrevButton.setTextSize(12); bottomPrevButton.setAllCaps(false);
        setupButtonStyle(bottomPrevButton, false);
        bottomPrevButton.setLayoutParams(btnParam);
        bottomPrevButton.setOnClickListener(v -> goToPrevPage());

        bottomBar.addView(bottomNextButton);
        bottomBar.addView(bottomPageText);
        bottomBar.addView(bottomPrevButton);

        readerContainer.addView(pdfImageView);
        readerContainer.addView(topBar);
        readerContainer.addView(bottomBar);
        mainLayout.addView(readerContainer);

        pdfImageView.postDelayed(this::displayCurrentPage, 100);
    }

    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(0, 8, 0, 8);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        topBar.setLayoutParams(params);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

        Button backBtn = new Button(this);
        backBtn.setText("返回");
        backBtn.setBackgroundColor(getButtonBackgroundColor());
        backBtn.setTextColor(getButtonTextColor());
        backBtn.setTextSize(11); backBtn.setAllCaps(false);
        backBtn.setOnClickListener(v -> goBackToFileList());
        setupButtonStyle(backBtn, false);

        Button nightBtn = new Button(this);
        nightBtn.setText(nightMode ? "日间" : "夜间");
        nightBtn.setBackgroundColor(getButtonBackgroundColor());
        nightBtn.setTextColor(getButtonTextColor());
        nightBtn.setTextSize(11); nightBtn.setAllCaps(false);
        nightBtn.setOnClickListener(v -> toggleNightMode());
        setupButtonStyle(nightBtn, false);

        halfPageBtn = new Button(this);
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        halfPageBtn.setBackgroundColor(getButtonBackgroundColor());
        halfPageBtn.setTextColor(getButtonTextColor());
        halfPageBtn.setTextSize(11); halfPageBtn.setAllCaps(false);
        halfPageBtn.setOnClickListener(v -> toggleHalfPageMode());
        setupButtonStyle(halfPageBtn, false);

        pageModeBtn = new Button(this);
        pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        pageModeBtn.setBackgroundColor(getButtonBackgroundColor());
        pageModeBtn.setTextColor(getButtonTextColor());
        pageModeBtn.setTextSize(11); pageModeBtn.setAllCaps(false);
        pageModeBtn.setOnClickListener(v -> toggleDoublePageMode());
        setupButtonStyle(pageModeBtn, false);

        rotateBtn = new Button(this);
        rotateBtn.setText(isRotated ? "转回" : "旋转");
        rotateBtn.setBackgroundColor(getButtonBackgroundColor());
        rotateBtn.setTextColor(getButtonTextColor());
        rotateBtn.setTextSize(11); rotateBtn.setAllCaps(false);
        rotateBtn.setOnClickListener(v -> toggleRotation());
        setupButtonStyle(rotateBtn, false);

        flipModeBtn = new Button(this);
        flipModeBtn.setText(flipPageMode ? "平滑" : "预载");
        flipModeBtn.setBackgroundColor(getButtonBackgroundColor());
        flipModeBtn.setTextColor(getButtonTextColor());
        flipModeBtn.setTextSize(11); flipModeBtn.setAllCaps(false);
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

    // ==================== 翻页与显示 ====================
    private void goToPrevPage() {
        if (flipPageMode && cacheInitialized) performCachedFlip(false);
        else originalGoToPrevPage();
    }

    private void goToNextPage() {
        if (flipPageMode && cacheInitialized) performCachedFlip(true);
        else originalGoToNextPage();
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
                else Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
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

    private void updatePageNumberText() {
        if (halfPageMode) {
            bottomPageText.setText((currentPage + 1) + "/" + totalPages + (leftPage ? " (左)" : " (右)"));
        } else {
            bottomPageText.setText((currentPage + 1) + "/" + totalPages);
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
                int pageNum = Integer.parseInt(input.getText().toString().trim());
                if (pageNum >= 1 && pageNum <= totalPages) {
                    currentPage = pageNum - 1;
                    if (halfPageMode) leftPage = true;
                    if (flipPageMode) clearPageCache();
                    displayCurrentPage();
                } else {
                    Toast.makeText(this, "页面范围应为 1 - " + totalPages, Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
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
                renderSinglePage();
            }
            saveReadingPosition();
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败", Toast.LENGTH_SHORT).show();
        }
    }

    // 单页渲染：直接使用原始分辨率
    private void renderSinglePage() {
        try {
            PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
            int pw = page.getWidth(), ph = page.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            if (halfPageMode) {
                int halfW = pw / 2;
                if (leftPage) {
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, halfW, ph);
                } else {
                    bitmap = Bitmap.createBitmap(bitmap, halfW, 0, halfW, ph);
                }
            }
            if (nightMode) bitmap = invertColors(bitmap);
            if (isRotated) bitmap = rotateBitmap90(bitmap);
            pdfImageView.setImageBitmap(bitmap);
            scaleFactor = 1.0f; matrix.reset();
            updatePageNumberText();
            pdfImageView.postDelayed(this::centerImage, 100);
            if (flipPageMode && !cacheInitialized) pdfImageView.postDelayed(this::initPageCache, 300);
        } catch (Exception e) {
            Toast.makeText(this, "渲染页面失败", Toast.LENGTH_SHORT).show();
        }
    }

    // 双页渲染优化：原始尺寸 + Canvas 直接缩放绘制
    private void showDoublePage(int leftPageNum, int rightPageNum) {
        try {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            if (isRotated) { int t = screenW; screenW = screenH; screenH = t; }

            Bitmap doubleBitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(doubleBitmap);
            canvas.drawColor(getBackgroundColor());

            int lw = 0, lh = 0, rw = 0, rh = 0;
            if (leftPageNum < totalPages) {
                PdfRenderer.Page lp = pdfRenderer.openPage(leftPageNum);
                lw = lp.getWidth(); lh = lp.getHeight();
                lp.close();
            }
            if (rightPageNum < totalPages) {
                PdfRenderer.Page rp = pdfRenderer.openPage(rightPageNum);
                rw = rp.getWidth(); rh = rp.getHeight();
                rp.close();
            }
            int maxH = Math.max(lh, rh);
            float scaleH = (float) (screenH * 0.95) / maxH;
            int totalW = lw + rw;
            float scaleW = (float) (screenW * 0.95) / totalW;
            float scale = Math.min(scaleH, scaleW);
            int drawH = (int) (maxH * scale);
            int leftDrawW = (int) (lw * scale);
            int rightDrawW = (int) (rw * scale);
            int totalDrawW = leftDrawW + rightDrawW;
            int startX = (screenW - totalDrawW) / 2;
            int startY = (screenH - drawH) / 2;

            if (leftPageNum < totalPages && lw > 0 && lh > 0) {
                PdfRenderer.Page page = pdfRenderer.openPage(leftPageNum);
                Bitmap orig = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888);
                page.render(orig, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                if (nightMode) orig = invertColors(orig);
                canvas.drawBitmap(orig,
                        new Rect(0, 0, lw, lh),
                        new Rect(startX, startY, startX + leftDrawW, startY + drawH), null);
                orig.recycle();
            }
            if (rightPageNum < totalPages && rw > 0 && rh > 0) {
                PdfRenderer.Page page = pdfRenderer.openPage(rightPageNum);
                Bitmap orig = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                page.render(orig, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                if (nightMode) orig = invertColors(orig);
                canvas.drawBitmap(orig,
                        new Rect(0, 0, rw, rh),
                        new Rect(startX + leftDrawW, startY, startX + totalDrawW, startY + drawH), null);
                orig.recycle();
            }

            if (isRotated) doubleBitmap = rotateBitmap90(doubleBitmap);
            pdfImageView.setImageBitmap(doubleBitmap);
            if (leftPageNum < totalPages) {
                bottomPageText.setText((leftPageNum + 1) + "," + (rightPageNum + 1) + "/" + totalPages);
            } else {
                bottomPageText.setText((leftPageNum + 1) + "/" + totalPages);
            }
            pdfImageView.invalidate();
            pdfImageView.postDelayed(this::centerImage, 100);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== 图像处理工具 ====================
    private Bitmap invertColors(Bitmap bitmap) {
        if (bitmap == null) return null;
        Bitmap out = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        ColorMatrix cm = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return out;
    }

    private Bitmap rotateBitmap90(Bitmap bitmap) {
        if (bitmap == null) return null;
        Matrix m = new Matrix();
        m.postRotate(90);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
    }

    // ==================== 缓存与翻页效果 ====================
    private void initPageCache() {
        if (pdfRenderer == null) return;
        clearPageCache();
        int prev = Math.max(0, currentPage - 1);
        int next = Math.min(totalPages - 1, currentPage + 1);
        cachePage(prev, true);
        cachePage(currentPage, false);
        cachePage(next, true);
        cachedPrevPage = prev;
        cachedCurrentPage = currentPage;
        cachedNextPage = next;
        cacheInitialized = true;
    }

    private void cachePage(int index, boolean isAdjacent) {
        if (index < 0 || index >= totalPages) return;
        try {
            PdfRenderer.Page page = pdfRenderer.openPage(index);
            int w = page.getWidth(), h = page.getHeight();
            float scale = Math.min(400f / w, 600f / h);
            int sw = (int) (w * scale), sh = (int) (h * scale);
            Bitmap bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            if (nightMode) bmp = invertColors(bmp);
            if (isRotated) bmp = rotateBitmap90(bmp);
            if (index == currentPage) currentPageCache = bmp;
            else if (index == currentPage - 1) prevPageCache = bmp;
            else if (index == currentPage + 1) nextPageCache = bmp;
        } catch (Exception e) { /* ignore */ }
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
            int newNext = Math.min(totalPages - 1, currentPage + 1);
            if (newNext != cachedCurrentPage && newNext < totalPages) {
                cachePage(newNext, true);
                cachedNextPage = newNext;
            }
        } else {
            if (nextPageCache != null) { nextPageCache.recycle(); nextPageCache = null; }
            nextPageCache = currentPageCache;
            currentPageCache = prevPageCache;
            prevPageCache = null;
            cachedNextPage = cachedCurrentPage;
            cachedCurrentPage = cachedPrevPage;
            int newPrev = Math.max(0, currentPage - 1);
            if (newPrev != cachedCurrentPage && newPrev >= 0) {
                cachePage(newPrev, true);
                cachedPrevPage = newPrev;
            }
        }
        if (currentPageCache != null) pdfImageView.setImageBitmap(currentPageCache);
    }

    private void performCachedFlip(boolean forward) {
        if (isFlipping) return;
        if (forward) {
            if (currentPage >= totalPages - 1) return;
        } else {
            if (currentPage <= 0) return;
        }
        isFlipping = true;
        if (forward && nextPageCache != null) {
            pdfImageView.setImageBitmap(nextPageCache);
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
            currentPage--;
            if (halfPageMode) leftPage = false;
            updatePageNumberText();
            new android.os.Handler().postDelayed(() -> {
                updatePageCacheAfterFlip(false);
                saveReadingPosition();
                isFlipping = false;
            }, 50);
        } else {
            if (forward) originalGoToNextPage();
            else originalGoToPrevPage();
            isFlipping = false;
        }
    }

    // ==================== 触摸与缩放 ====================
    private boolean handleZoomMode(ImageView view, MotionEvent event) {
        isClickCandidate = false;
        isSwiping = false;
        switch (event.getActionMasked()) {
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
                        scaleFactor = Math.max(minScale, Math.min(maxScale, scaleFactor));
                        matrix.postScale(scale, scale, midPoint.x, midPoint.y);
                        limitDragWithBoundary();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
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
        float dx = Math.abs(event.getX() - touchStartX);
        float dy = Math.abs(event.getY() - touchStartY);
        if (dx > SWIPE_MIN_DISTANCE && dx > dy * SWIPE_VS_SCROLL_RATIO) {
            isClickCandidate = false;
            isSwiping = true;
        }
        return true;
    }

    private boolean handleTouchUp(ImageView view, MotionEvent event) {
        if (scaleFactor > 1.01f && mode == DRAG) {
            mode = NONE;
            return true;
        }
        float endX = event.getX(), endY = event.getY();
        long duration = System.currentTimeMillis() - touchStartTime;
        float distance = (float) Math.hypot(endX - touchStartX, endY - touchStartY);
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

    private void handleClick(float x, float y, float viewW, float viewH) {
        if (isRotated) {
            float third = viewH / 3;
            if (y < third) goToNextPage();
            else if (y > 2 * third) goToPrevPage();
            else toggleControls();
        } else {
            float third = viewW / 3;
            if (x < third) goToNextPage();
            else if (x > 2 * third) goToPrevPage();
            else toggleControls();
        }
    }

    private void handleSwipe(float startX, float endX) {
        if (endX - startX > SWIPE_MIN_DISTANCE) goToNextPage();
        else if (startX - endX > SWIPE_MIN_DISTANCE) goToPrevPage();
    }

    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF point, MotionEvent event) {
        point.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2);
    }

    private void limitDragWithBoundary() {
        float[] values = new float[9];
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        int viewW = pdfImageView.getWidth(), viewH = pdfImageView.getHeight();
        if (viewW == 0 || viewH == 0) return;
        BitmapDrawable drawable = (BitmapDrawable) pdfImageView.getDrawable();
        if (drawable == null) return;
        Bitmap bmp = drawable.getBitmap();
        if (bmp == null) return;
        float scaledW = bmp.getWidth() * scale, scaledH = bmp.getHeight() * scale;
        float minX, maxX, minY, maxY;
        if (scaledW > viewW) { minX = viewW - scaledW; maxX = 0; }
        else { minX = maxX = (viewW - scaledW) / 2; }
        if (scaledH > viewH) { minY = viewH - scaledH; maxY = 0; }
        else { minY = maxY = (viewH - scaledH) / 2; }
        transX = Math.max(minX, Math.min(maxX, transX));
        transY = Math.max(minY, Math.min(maxY, transY));
        values[Matrix.MTRANS_X] = transX;
        values[Matrix.MTRANS_Y] = transY;
        matrix.setValues(values);
    }

    private void centerImage() {
        if (pdfImageView == null || pdfImageView.getWidth() == 0) return;
        BitmapDrawable drawable = (BitmapDrawable) pdfImageView.getDrawable();
        if (drawable == null) return;
        Bitmap bmp = drawable.getBitmap();
        if (bmp == null) return;
        float[] values = new float[9];
        matrix.getValues(values);
        float currentScale = values[Matrix.MSCALE_X];
        if (Math.abs(scaleFactor - 1.0f) < 0.01f) {
            float scaleX = (float) pdfImageView.getWidth() / bmp.getWidth();
            float scaleY = (float) pdfImageView.getHeight() / bmp.getHeight();
            float scale = Math.min(scaleX, scaleY);
            matrix.postScale(scale, scale);
            scaleFactor = scale;
            currentScale = scale;
        }
        float scaledW = bmp.getWidth() * currentScale;
        float scaledH = bmp.getHeight() * currentScale;
        float dx = (pdfImageView.getWidth() - scaledW) / 2f;
        float dy = (pdfImageView.getHeight() - scaledH) / 2f;
        values[Matrix.MTRANS_X] = dx;
        values[Matrix.MTRANS_Y] = dy;
        matrix.setValues(values);
        limitDragWithBoundary();
        pdfImageView.setImageMatrix(matrix);
    }

    private void resetScale() {
        scaleFactor = 1.0f;
        matrix.reset();
        centerImage();
        Toast.makeText(this, "已恢复原始大小", Toast.LENGTH_SHORT).show();
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        View topBar = readerContainer.getChildAt(1);
        if (topBar != null) topBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
    }

    private void toggleNightMode() {
        nightMode = !nightMode;
        saveSettings();
        updateThemeColors();
        if (flipPageMode && cacheInitialized) clearPageCache();
        if (pdfRenderer != null) displayCurrentPage();
    }

    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        if (halfPageBtn != null) halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) displayCurrentPage();
    }

    private void toggleDoublePageMode() {
        doublePageMode = !doublePageMode;
        if (pageModeBtn != null) pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) displayCurrentPage();
    }

    private void toggleRotation() {
        isRotated = !isRotated;
        if (rotateBtn != null) rotateBtn.setText(isRotated ? "转回" : "旋转");
        saveSettings();
        if (flipPageMode) clearPageCache();
        if (pdfRenderer != null) displayCurrentPage();
    }

    private void toggleFlipPageMode() {
        flipPageMode = !flipPageMode;
        if (flipModeBtn != null) flipModeBtn.setText(flipPageMode ? "平滑" : "预载");
        saveSettings();
        if (!flipPageMode) clearPageCache();
        else if (pdfRenderer != null && !cacheInitialized) initPageCache();
    }

    private void goBackToFileList() {
        closePdf();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) showFileListWithoutScan();
            else showFileList();
        } else showFileList();
    }

    private void closePdf() {
        if (pdfRenderer != null) { pdfRenderer.close(); pdfRenderer = null; }
        if (fileDescriptor != null) { try { fileDescriptor.close(); } catch (IOException e) {} }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
    }
}
