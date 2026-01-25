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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import java.io.FileInputStream;
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
    private Button nightModeBtn, halfPageBtn, pageModeBtn, prevBtn, nextBtn, openFileBtn, refreshBtn, rotateBtn;
    
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
    
    // 缩放相关变量 - 增强版
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
    private static final int DOUBLE_TAP_TIME_THRESHOLD = 300; // 双击时间阈值（毫秒）
    
    // 存储
    private SharedPreferences prefs;
    private static final String LAST_OPENED_FILE = "last_opened_file"; // 存储最后打开的文件路径
    private static final String AUTO_OPEN_LAST_FILE = "auto_open_last_file"; // 是否自动打开最后文件
    private static final String FIRST_RUN = "first_run"; // 是否首次运行
    
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;
    
    // 颜色常量
    private static final int DAY_MODE_BG = Color.WHITE;
    private static final int DAY_MODE_TEXT = Color.BLACK;
    private static final int NIGHT_MODE_BG = Color.BLACK;
    private static final int NIGHT_MODE_TEXT = Color.WHITE;
    private static final int DAY_STATUS_BAR_COLOR = Color.parseColor("#F0E68C"); // 卡其色（日间）
    private static final int NIGHT_STATUS_BAR_COLOR = Color.BLACK; // 黑色（夜间）
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 全屏显示
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        // 初始化存储
        prefs = getSharedPreferences("pdf_reader", MODE_PRIVATE);
        loadSettings();
        
        // 检查是否是首次运行
        boolean firstRun = prefs.getBoolean(FIRST_RUN, true);
        
        if (firstRun) {
            // 首次运行，标记为非首次运行
            prefs.edit().putBoolean(FIRST_RUN, false).apply();
            
            // 创建界面
            createMainLayout();
            
            // 首次运行时请求权限
            requestPermissionsOnFirstRun();
        } else {
            // 不是首次运行，直接检查是否有上次阅读的文件
            checkAutoOpenLastFile();
        }
    }
    
    private void requestPermissionsOnFirstRun() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                // 显示解释对话框
                new AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("PDF阅读器需要访问您的存储空间来扫描和读取PDF文件。\n\n" +
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
                        // 显示文件列表
                        showFileList();
                    })
                    .setCancelable(false)
                    .show();
            } else {
                // 已经有权限，显示文件列表
                showFileList();
            }
        } else {
            // Android 6.0以下直接显示文件列表
            showFileList();
        }
    }
    
    private void goBackToFileList() {
        closePdf();
        showFileList();
    }
    
    @Override
    public void onBackPressed() {
        if (pdfRenderer != null) {
            // 正在阅读PDF，返回文件列表
            goBackToFileList();
        } else {
            // 已经在文件列表界面，退出应用
            super.onBackPressed();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，显示文件列表
                showFileList();
            } else {
                // 权限被拒绝，显示文件列表
                showFileList();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 检查是否应该自动打开上次阅读的文件（从后台返回时）
        if (pdfRenderer == null) { // 如果不在阅读界面
            checkAutoOpenLastFile();
        }
        
        // 如果正在阅读界面，重新显示当前页面（解决从后台返回时显示空白的问题）
        if (pdfRenderer != null && pdfImageView != null) {
            // 延迟一小段时间确保布局完成
            pdfImageView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 强制重新绘制
                    pdfImageView.invalidate();
                    // 重新居中显示
                    centerImage();
                }
            }, 200);
        }
    }
    
    private void checkAutoOpenLastFile() {
        // 获取上次打开的文件路径
        String lastOpenedFile = prefs.getString(LAST_OPENED_FILE, null);
        boolean autoOpenLastFile = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true);
        
        if (autoOpenLastFile && lastOpenedFile != null && !lastOpenedFile.isEmpty()) {
            File file = new File(lastOpenedFile);
            if (file.exists() && file.canRead()) {
                // 延迟一小段时间打开，确保UI已经加载完成
                new android.os.Handler().postDelayed(() -> {
                    // 检查当前是否已经在阅读界面
                    if (pdfRenderer == null) {
                        // 先创建主布局
                        createMainLayout();
                        // 打开上次阅读的文件
                        openPdfFile(lastOpenedFile);
                    }
                }, 100);
                return; // 如果自动打开了文件，就不显示文件列表
            } else {
                // 文件不存在或不可读，清除记录
                prefs.edit().remove(LAST_OPENED_FILE).apply();
                Log.d("PDF_DEBUG", "上次打开的文件不存在或不可读: " + lastOpenedFile);
            }
        }
        
        // 如果没有自动打开文件，显示文件列表
        createMainLayout();
        showFileList();
    }
    
    private void loadSettings() {
        nightMode = prefs.getBoolean("night_mode", false);
        halfPageMode = prefs.getBoolean("half_page", false);
        doublePageMode = prefs.getBoolean("double_page", false);
        isRotated = prefs.getBoolean("is_rotated", false);
    }
    
    private void saveSettings() {
        prefs.edit()
            .putBoolean("night_mode", nightMode)
            .putBoolean("half_page", halfPageMode)
            .putBoolean("double_page", doublePageMode)
            .putBoolean("is_rotated", isRotated)
            .apply();
    }
    
    private void saveLastOpenedFile(String filePath) {
        if (filePath != null) {
            prefs.edit()
                .putString(LAST_OPENED_FILE, filePath)
                .apply();
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
                continueBtn.setBackgroundColor(Color.parseColor("#FF5722"));
                continueBtn.setTextColor(Color.WHITE);
                continueBtn.setPadding(20, 30, 20, 30);
                continueBtn.setAllCaps(false);
                
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = 20;
                continueBtn.setLayoutParams(params);
                
                continueBtn.setOnClickListener(v -> openPdfFile(lastOpenedFile));
                
                fileListLayout.addView(continueBtn);
            }
        }
    }
    
    private String getShortFileName(String fileName) {
        if (fileName.length() > 25) {
            return fileName.substring(0, 22) + "...";
        }
        return fileName;
    }
    
    private LinearLayout createTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(20, 20, 20, 20);
        
        TextView title = new TextView(this);
        title.setText("PDF阅读器 v1.0.15");
        title.setTextColor(nightMode ? Color.WHITE : Color.BLACK);
        title.setTextSize(20);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        
        nightModeBtn = new Button(this);
        nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        nightModeBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        nightModeBtn.setTextColor(Color.WHITE);
        nightModeBtn.setOnClickListener(v -> toggleNightMode());
        
        refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setOnClickListener(v -> scanPdfFiles());
        refreshBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        topBar.addView(title);
        topBar.addView(nightModeBtn);
        topBar.addView(refreshBtn);
        
        return topBar;
    }
    
    private void scanPdfFiles() {
        fileListLayout.removeAllViews();
        
        addContinueReadingButton();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                addFileChooserOptions();
                return;
            }
        }
        
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            
            if (downloadDir.exists() && downloadDir.isDirectory()) {
                File[] files = downloadDir.listFiles((dir, name) -> 
                    name.toLowerCase().endsWith(".pdf"));
                
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        addFileButton(file);
                    }
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
        if (lastPage > 0) {
            fileName += " (读到第" + (lastPage + 1) + "页)";
        }
        
        fileBtn.setText(fileName);
        fileBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        fileBtn.setTextColor(Color.WHITE);
        fileBtn.setPadding(20, 30, 20, 30);
        fileBtn.setAllCaps(false);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 10;
        fileBtn.setLayoutParams(params);
        
        String filePath = file.getAbsolutePath();
        fileBtn.setOnClickListener(v -> openPdfFile(filePath));
        
        fileListLayout.addView(fileBtn);
    }
    
    private void showNoFilesMessage() {
        TextView noFilesText = new TextView(this);
        noFilesText.setText("📂 未找到PDF文件\n\n" +
                           "请将PDF文件放置在：\n" +
                           "手机存储 → Download文件夹\n\n" +
                           "或者使用下方选项选择文件");
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
        singleFileBtn.setText("选择PDF文件");
        singleFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        singleFileBtn.setTextColor(Color.WHITE);
        singleFileBtn.setPadding(20, 30, 20, 30);
        singleFileBtn.setOnClickListener(v -> choosePdfFile());
        
        LinearLayout.LayoutParams singleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        singleParams.bottomMargin = 10;
        singleFileBtn.setLayoutParams(singleParams);
        
        optionsLayout.addView(singleFileBtn);
        fileListLayout.addView(optionsLayout);
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
    
    private void openPdfFile(String filePath) {
        try {
            File file = new File(filePath);
            
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在: " + filePath, Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (!file.canRead()) {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show();
                return;
            }
            
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            
            currentFilePath = filePath;
            totalPages = pdfRenderer.getPageCount();
            
            currentPage = getReadingPosition(filePath);
            leftPage = getHalfPageLeftState(filePath);
            
            if (currentPage >= totalPages) {
                currentPage = totalPages - 1;
            }
            if (currentPage < 0) {
                currentPage = 0;
            }
            
            saveLastOpenedFile(filePath);
            
            showReaderView();
            
        } catch (Exception e) {
            Toast.makeText(this, "无法打开PDF文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void openPdfFromUri(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            
            String displayName = null;
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex);
                    }
                }
            }
            
            String tempFileName = displayName != null ? displayName : 
                "temp_pdf_" + System.currentTimeMillis() + ".pdf";
            File tempFile = new File(getCacheDir(), tempFileName);
            
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                
                if (in == null) {
                    Toast.makeText(this, "无法读取文件内容", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                
                openPdfFile(tempFile.getAbsolutePath());
                
                if (currentFilePath != null && currentFilePath.contains("temp_pdf_")) {
                    new File(currentFilePath).delete();
                }
                currentFilePath = tempFile.getAbsolutePath();
                
            } catch (IOException e) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
            
        } catch (Exception e) {
            Toast.makeText(this, "无法打开PDF文件", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
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
                            } catch (Exception e) {
                                Log.e("PDF_DEBUG", "获取外部存储路径失败", e);
                            }
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
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                filePath = uri.getPath();
            }
            
            if (filePath == null) {
                filePath = uri.getPath();
            }
            
            if (filePath != null) {
                File file = new File(filePath);
                if (!file.exists()) {
                    Log.d("PDF_DEBUG", "文件不存在: " + filePath);
                    return null;
                }
            }
            
        } catch (Exception e) {
            Log.e("PDF_DEBUG", "获取真实路径失败", e);
        }
        
        return filePath;
    }
    
    private void showReaderView() {
        mainLayout.removeAllViews();
        
        readerContainer = new FrameLayout(this);
        readerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        readerContainer.setBackgroundColor(getBackgroundColor());
        
        pdfImageView = new ImageView(this);
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
        
        pdfImageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ImageView view = (ImageView) v;
                
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        savedMatrix.set(matrix);
                        startPoint.set(event.getX(), event.getY());
                        mode = DRAG;
                        
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastClickTime < DOUBLE_TAP_TIME_THRESHOLD) {
                            resetScale();
                            return true;
                        }
                        lastClickTime = currentTime;
                        break;
                        
                    case MotionEvent.ACTION_POINTER_DOWN:
                        oldDistance = spacing(event);
                        if (oldDistance > 10f) {
                            savedMatrix.set(matrix);
                            midPoint(midPoint, event);
                            mode = ZOOM;
                        }
                        break;
                        
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        mode = NONE;
                        
                        centerImage();
                        
                        if (scaleFactor <= 1.01f) {
                            float x = event.getX();
                            float width = v.getWidth();
                            
                            if (isRotated) {
                                float height = v.getHeight();
                                float y = event.getY();
                                
                                if (y < height / 3) {
                                    goToNextPage();
                                }
                                else if (y > height * 2 / 3) {
                                    goToPrevPage();
                                }
                                else {
                                    toggleControls();
                                }
                            } else {
                                if (x < width / 3) {
                                    goToNextPage();
                                }
                                else if (x > width * 2 / 3) {
                                    goToPrevPage();
                                }
                                else {
                                    toggleControls();
                                }
                            }
                        }
                        break;
                        
                    case MotionEvent.ACTION_MOVE:
                        if (mode == DRAG) {
                            matrix.set(savedMatrix);
                            float dx = event.getX() - startPoint.x;
                            float dy = event.getY() - startPoint.y;
                            
                            matrix.postTranslate(dx, dy);
                            limitDrag();
                        } else if (mode == ZOOM) {
                            float newDist = spacing(event);
                            if (newDist > 10f) {
                                matrix.set(savedMatrix);
                                float scale = newDist / oldDistance;
                                
                                scaleFactor *= scale;
                                
                                if (scaleFactor < minScale) {
                                    scaleFactor = minScale;
                                    scale = minScale / (scaleFactor / scale);
                                } else if (scaleFactor > maxScale) {
                                    scaleFactor = maxScale;
                                    scale = maxScale / (scaleFactor / scale);
                                }
                                
                                matrix.postScale(scale, scale, midPoint.x, midPoint.y);
                                
                                limitDrag();
                            }
                        }
                        break;
                }
                
                view.setImageMatrix(matrix);
                return true;
            }
        });
        
        LinearLayout topBar = createReaderTopBar();
        topBar.setId(View.generateViewId());
        
        pageTextView = new TextView(this);
        pageTextView.setId(View.generateViewId());
        pageTextView.setTextColor(getTextColor());
        pageTextView.setTextSize(14);
        pageTextView.setBackgroundColor(Color.parseColor("#80000000"));
        pageTextView.setPadding(10, 5, 10, 5);
        pageTextView.setGravity(Gravity.CENTER);
        
        prevBtn = new Button(this);
        prevBtn.setText("上一页");
        prevBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setOnClickListener(v -> goToPrevPage());
        
        FrameLayout.LayoutParams prevParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        prevParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        prevParams.rightMargin = 20;
        prevParams.bottomMargin = 80;
        prevBtn.setLayoutParams(prevParams);
        
        nextBtn = new Button(this);
        nextBtn.setText("下一页");
        nextBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        nextBtn.setTextColor(Color.WHITE);
        nextBtn.setOnClickListener(v -> goToNextPage());
        
        FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        nextParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        nextParams.leftMargin = 20;
        nextParams.bottomMargin = 80;
        nextBtn.setLayoutParams(nextParams);
        
        Button jumpBtn = new Button(this);
        jumpBtn.setText("跳转");
        jumpBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        jumpBtn.setTextColor(Color.WHITE);
        jumpBtn.setOnClickListener(v -> showJumpPageDialog());
        
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        jumpParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        jumpParams.bottomMargin = 80;
        jumpBtn.setLayoutParams(jumpParams);
        
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pageParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pageParams.bottomMargin = 20;
        pageTextView.setLayoutParams(pageParams);
        
        readerContainer.addView(pdfImageView);
        readerContainer.addView(topBar);
        readerContainer.addView(prevBtn);
        readerContainer.addView(nextBtn);
        readerContainer.addView(jumpBtn);
        readerContainer.addView(pageTextView);
        
        mainLayout.addView(readerContainer);
        
        pdfImageView.postDelayed(new Runnable() {
            @Override
            public void run() {
                displayCurrentPage();
            }
        }, 100);
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
    
    private void centerImage() {
        if (pdfImageView == null) return;
        
        if (pdfImageView.getWidth() == 0 || pdfImageView.getHeight() == 0) {
            pdfImageView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    centerImage();
                }
            }, 50);
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
        
        pdfImageView.setImageMatrix(matrix);
        pdfImageView.invalidate();
    }
    
    private void limitDrag() {
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
        
        if (scaledWidth > viewWidth) {
            if (transX > 0) {
                transX = 0;
            } else if (transX < viewWidth - scaledWidth) {
                transX = viewWidth - scaledWidth;
            }
        } else {
            transX = (viewWidth - scaledWidth) / 2;
        }
        
        if (scaledHeight > viewHeight) {
            if (transY > 0) {
                transY = 0;
            } else if (transY < viewHeight - scaledHeight) {
                transY = viewHeight - scaledHeight;
            }
        } else {
            transY = (viewHeight - scaledHeight) / 2;
        }
        
        values[Matrix.MTRANS_X] = transX;
        values[Matrix.MTRANS_Y] = transY;
        matrix.setValues(values);
        
        pdfImageView.setImageMatrix(matrix);
    }
    
    private void resetScale() {
        scaleFactor = 1.0f;
        matrix.reset();
        centerImage();
    }
    
    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(0, 5, 0, 5);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        topBar.setLayoutParams(params);
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        
        Button backBtn = new Button(this);
        backBtn.setText("返回");
        backBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(12);
        backBtn.setPadding(0, 5, 0, 5);
        backBtn.setAllCaps(false);
        backBtn.setLayoutParams(btnParams);
        backBtn.setOnClickListener(v -> goBackToFileList());
        
        Button nightBtn = new Button(this);
        nightBtn.setText(nightMode ? "日间" : "夜间");
        nightBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        nightBtn.setTextColor(Color.WHITE);
        nightBtn.setTextSize(12);
        nightBtn.setPadding(0, 5, 0, 5);
        nightBtn.setAllCaps(false);
        nightBtn.setLayoutParams(btnParams);
        nightBtn.setOnClickListener(v -> toggleNightMode());
        
        halfPageBtn = new Button(this);
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        halfPageBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        halfPageBtn.setTextColor(Color.WHITE);
        halfPageBtn.setTextSize(12);
        halfPageBtn.setPadding(0, 5, 0, 5);
        halfPageBtn.setAllCaps(false);
        halfPageBtn.setLayoutParams(btnParams);
        halfPageBtn.setOnClickListener(v -> toggleHalfPageMode());
        
        pageModeBtn = new Button(this);
        pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        pageModeBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        pageModeBtn.setTextColor(Color.WHITE);
        pageModeBtn.setTextSize(12);
        pageModeBtn.setPadding(0, 5, 0, 5);
        pageModeBtn.setAllCaps(false);
        pageModeBtn.setLayoutParams(btnParams);
        pageModeBtn.setOnClickListener(v -> toggleDoublePageMode());
        
        rotateBtn = new Button(this);
        rotateBtn.setText(isRotated ? "转回" : "旋转");
        rotateBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        rotateBtn.setTextColor(Color.WHITE);
        rotateBtn.setTextSize(12);
        rotateBtn.setPadding(0, 5, 0, 5);
        rotateBtn.setAllCaps(false);
        rotateBtn.setLayoutParams(btnParams);
        rotateBtn.setOnClickListener(v -> toggleRotation());
        
        topBar.addView(backBtn);
        topBar.addView(nightBtn);
        topBar.addView(halfPageBtn);
        topBar.addView(pageModeBtn);
        topBar.addView(rotateBtn);
        
        return topBar;
    }
    
    private void toggleRotation() {
        isRotated = !isRotated;
        
        if (rotateBtn != null) {
            rotateBtn.setText(isRotated ? "转回" : "旋转");
        }
        
        saveSettings();
        
        if (pdfRenderer != null) {
            displayCurrentPage();
        }
    }
    
    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        
        if (halfPageBtn != null) {
            halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        }
        
        saveSettings();
        
        if (pdfRenderer != null) {
            displayCurrentPage();
        }
    }
    
    private void toggleDoublePageMode() {
        doublePageMode = !doublePageMode;
        
        if (pageModeBtn != null) {
            pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        }
        
        saveSettings();
        
        if (pdfRenderer != null) {
            displayCurrentPage();
        }
    }
    
    private void toggleControls() {
        controlsVisible = !controlsVisible;
        
        View topBar = readerContainer.findViewById(readerContainer.getChildAt(1).getId());
        View prevBtn = readerContainer.getChildAt(2);
        View nextBtn = readerContainer.getChildAt(3);
        View jumpBtn = readerContainer.getChildAt(4);
        View pageText = readerContainer.getChildAt(5);
        
        if (controlsVisible) {
            topBar.setVisibility(View.VISIBLE);
            prevBtn.setVisibility(View.VISIBLE);
            nextBtn.setVisibility(View.VISIBLE);
            jumpBtn.setVisibility(View.VISIBLE);
            pageText.setVisibility(View.VISIBLE);
        } else {
            topBar.setVisibility(View.GONE);
            prevBtn.setVisibility(View.GONE);
            nextBtn.setVisibility(View.GONE);
            jumpBtn.setVisibility(View.GONE);
            pageText.setVisibility(View.GONE);
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
                        if (halfPageMode) {
                            leftPage = true;
                        }
                        displayCurrentPage();
                    } else {
                        Toast.makeText(MainActivity.this, 
                                "页面范围应为 1 - " + totalPages, 
                                Toast.LENGTH_SHORT).show();
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
        
        Bitmap rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        
        return rotatedBitmap;
    }
    
    private Bitmap createDoublePageBitmap(int leftPageNum, int rightPageNum) {
        try {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            
            if (isRotated) {
                int temp = screenWidth;
                screenWidth = screenHeight;
                screenHeight = temp;
            }
            
            Bitmap doubleBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(doubleBitmap);
            
            canvas.drawColor(getBackgroundColor());
            
            float unifiedScale = 1.0f;
            int unifiedScaledHeight = 0;
            int leftScaledWidth = 0;
            int rightScaledWidth = 0;
            
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
                    (int)(leftPageWidth * unifiedScale * 2),
                    (int)(leftPageHeight * unifiedScale * 2),
                    Bitmap.Config.ARGB_8888
                );
                leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                leftPage.close();
                
                leftBitmap = Bitmap.createScaledBitmap(leftBitmap, leftScaledWidth, unifiedScaledHeight, true);
                
                if (nightMode) {
                    leftBitmap = invertColors(leftBitmap);
                }
                
                canvas.drawBitmap(leftBitmap, startX, startY, null);
            }
            
            if (rightPageNum < totalPages) {
                PdfRenderer.Page rightPage = pdfRenderer.openPage(rightPageNum);
                Bitmap rightBitmap = Bitmap.createBitmap(
                    (int)(rightPageWidth * unifiedScale * 2),
                    (int)(rightPageHeight * unifiedScale * 2),
                    Bitmap.Config.ARGB_8888
                );
                rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                rightPage.close();
                
                rightBitmap = Bitmap.createScaledBitmap(rightBitmap, rightScaledWidth, unifiedScaledHeight, true);
                
                if (nightMode) {
                    rightBitmap = invertColors(rightBitmap);
                }
                
                canvas.drawBitmap(rightBitmap, startX + leftScaledWidth, startY, null);
            }
            
            if (isRotated) {
                doubleBitmap = rotateBitmap90(doubleBitmap);
            }
            
            return doubleBitmap;
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }           

    private void displayCurrentPage() {
        if (pdfRenderer == null) return;
        
        try {
            if (doublePageMode) {
                int basePage = currentPage;
                if (basePage % 2 != 0) {
                    basePage--;
                }
                
                int rightPageNum = basePage;
                int leftPageNum = basePage + 1;
                
                if (rightPageNum >= totalPages) {
                    rightPageNum = totalPages - 1;
                }
                if (leftPageNum >= totalPages) {
                    leftPageNum = totalPages - 1;
                }
                
                Bitmap doubleBitmap = createDoublePageBitmap(leftPageNum, rightPageNum);
                
                if (doubleBitmap != null) {
                    pdfImageView.setImageBitmap(doubleBitmap);
                    
                    if (leftPageNum < totalPages) {
                        pageTextView.setText((leftPageNum + 1) + "," + (rightPageNum + 1) + "/" + totalPages);
                    } else {
                        pageTextView.setText((leftPageNum + 1) + "/" + totalPages);
                    }
                    
                    pdfImageView.invalidate();
                    
                    pdfImageView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            centerImage();
                        }
                    }, 100);
                }
                
            } else {
                PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
                
                int pageWidth = page.getWidth();
                int pageHeight = page.getHeight();
                
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                
                if (isRotated) {
                    int temp = screenWidth;
                    screenWidth = screenHeight;
                    screenHeight = temp;
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
                
                int highResWidth = (int)(pageWidth * scale * 2);
                int highResHeight = (int)(pageHeight * scale * 2);
                
                Bitmap highResBitmap = Bitmap.createBitmap(
                    Math.max(highResWidth, 1),
                    Math.max(highResHeight, 1),
                    Bitmap.Config.ARGB_8888
                );
                
                page.render(highResBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                
                int scaledWidth = (int) (pageWidth * scale);
                int scaledHeight = (int) (pageHeight * scale);
                
                Bitmap bitmap = Bitmap.createScaledBitmap(highResBitmap, scaledWidth, scaledHeight, true);
                
                if (!highResBitmap.isRecycled() && highResBitmap != bitmap) {
                    highResBitmap.recycle();
                }
                
                if (halfPageMode) {
                    if (leftPage) {
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, scaledWidth / 2, scaledHeight);
                        pageTextView.setText((currentPage + 1) + "/" + totalPages + " (左)");
                    } else {
                        bitmap = Bitmap.createBitmap(bitmap, scaledWidth / 2, 0, scaledWidth / 2, scaledHeight);
                        pageTextView.setText((currentPage + 1) + "/" + totalPages + " (右)");
                    }
                } else {
                    pageTextView.setText((currentPage + 1) + "/" + totalPages);
                }
                
                if (nightMode) {
                    bitmap = invertColors(bitmap);
                }
                
                if (isRotated) {
                    bitmap = rotateBitmap90(bitmap);
                }
                
                pdfImageView.setImageBitmap(bitmap);
                pdfImageView.invalidate();
                
                scaleFactor = 1.0f;
                matrix.reset();
                
                pdfImageView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        centerImage();
                    }
                }, 100);
            }
            
            saveReadingPosition();
            
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void goToPrevPage() {
        if (scaleFactor > 1.01f) {
            resetScale();
        }
        
        if (doublePageMode) {
            if (currentPage > 1) {
                currentPage -= 2;
            } else {
                currentPage = 0;
                Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
            }
        } else if (halfPageMode) {
            if (leftPage) {
                leftPage = false;
            } else {
                if (currentPage > 0) {
                    currentPage--;
                    leftPage = true;
                } else {
                    Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            if (currentPage > 0) {
                currentPage--;
            }
        }
        displayCurrentPage();
    }
    
    private void goToNextPage() {
        if (scaleFactor > 1.01f) {
            resetScale();
        }
        
        if (doublePageMode) {
            if (currentPage < totalPages - 1) {
                currentPage += 2;
                if (currentPage >= totalPages) {
                    currentPage = totalPages - 1;
                }
            } else {
                Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
            }
        } else if (halfPageMode) {
            if (leftPage) {
                if (currentPage < totalPages - 1) {
                    currentPage++;
                    leftPage = false;
                } else {
                    Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
                }
            } else {
                leftPage = true;
            }
        } else {
            if (currentPage < totalPages - 1) {
                currentPage++;
            }
        }
        displayCurrentPage();
    }
    
    private void toggleNightMode() {
        nightMode = !nightMode;
        
        if (nightModeBtn != null) {
            nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        }
        
        saveSettings();
        
        updateThemeColors();
        
        if (pdfRenderer != null) {
            if (readerContainer != null) {
                readerContainer.setBackgroundColor(getBackgroundColor());
            }
            if (pdfImageView != null) {
                pdfImageView.setBackgroundColor(getBackgroundColor());
            }
            if (pageTextView != null) {
                pageTextView.setTextColor(getTextColor());
            }
            if (readerContainer.getChildAt(1) != null) {
                readerContainer.getChildAt(1).setBackgroundColor(getStatusBarColor());
            }
            displayCurrentPage();
        }
    }
    
    private void closePdf() {
        if (pdfRenderer != null) {
            pdfRenderer.close();
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Log.d("PDF_DEBUG", "URI Scheme: " + uri.getScheme());
                Log.d("PDF_DEBUG", "URI Path: " + uri.getPath());
                
                if (requestCode == FILE_PICKER_REQUEST_CODE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        try {
                            final int takeFlags = data.getFlags() & 
                                (Intent.FLAG_GRANT_READ_URI_PERMISSION | 
                                 Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        } catch (SecurityException e) {
                            Log.e("PDF_DEBUG", "无法获取持久化权限", e);
                        }
                    }
                    
                    String filePath = getRealPathFromUri(uri);
                    Log.d("PDF_DEBUG", "Real Path: " + filePath);
                    
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
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
