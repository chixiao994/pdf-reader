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
    
    private void goBackToFileList() {
        closePdf();
        
        // 根据权限状态显示文件列表
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
            // 正在阅读PDF，返回文件列表
            goBackToFileList();
        } else {
            // 已经在文件列表界面，退出应用
            super.onBackPressed();
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
                        // 显示文件列表（只能通过文件选择器选择文件）
                        showFileListWithoutScan();
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
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，显示文件列表
                showFileList();
            } else {
                // 权限被拒绝，显示无权限的文件列表
                showFileListWithoutScan();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 如果从后台返回时不在阅读界面，检查是否应该自动打开上次阅读的文件
        if (pdfRenderer == null) {
            // 重新创建主布局
            createMainLayout();
            
            // 检查权限并显示相应的文件列表
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
            // 如果正在阅读界面，重新显示当前页面（解决从后台返回时显示空白的问题）
            if (pdfImageView != null) {
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
    }
    
    private void checkAutoOpenLastFile() {
        // 获取上次打开的文件路径
        String lastOpenedFile = prefs.getString(LAST_OPENED_FILE, null);
        boolean autoOpenLastFile = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true); // 默认开启自动打开
        
        if (autoOpenLastFile && lastOpenedFile != null && !lastOpenedFile.isEmpty()) {
            File file = new File(lastOpenedFile);
            if (file.exists() && file.canRead()) {
                // 先创建主布局
                createMainLayout();
                // 延迟一小段时间打开，确保UI已经加载完成
                new android.os.Handler().postDelayed(() -> {
                    // 打开上次阅读的文件
                    openPdfFile(lastOpenedFile);
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
        
        // 检查权限并显示相应的文件列表
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
        isRotated = prefs.getBoolean("is_rotated", false); // 加载旋转状态
    }
    
    private void saveSettings() {
        prefs.edit()
            .putBoolean("night_mode", nightMode)
            .putBoolean("half_page", halfPageMode)
            .putBoolean("double_page", doublePageMode)
            .putBoolean("is_rotated", isRotated) // 保存旋转状态
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
                .putInt(currentFilePath + "_half_page_left", leftPage ? 1 : 0) // 保存半页状态
                .apply();
            
            // 同时保存为最后打开的文件
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
        
        // 设置主题颜色
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
    
    private void showFileListWithoutScan() {
        mainLayout.removeAllViews();
        
        // 创建顶部栏
        LinearLayout topBar = createTopBar();
        
        // 创建文件列表区域
        fileListLayout = new LinearLayout(this);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        fileListLayout.setPadding(20, 20, 20, 20);
        
        TextView noPermissionText = new TextView(this);
        noPermissionText.setText("📂 存储权限未授予\n\n" +
                               "无法自动扫描PDF文件\n\n" +
                               "请点击下方手动选择PDF文件");
        noPermissionText.setTextSize(16);
        noPermissionText.setGravity(android.view.Gravity.CENTER);
        noPermissionText.setTextColor(getTextColor());
        noPermissionText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noPermissionText);
        
        // 添加选择文件
        openFileBtn = new Button(this);
        openFileBtn.setText("选择PDF文件");
        openFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        openFileBtn.setTextColor(Color.WHITE);
        openFileBtn.setOnClickListener(v -> choosePdfFile());
        fileListLayout.addView(openFileBtn);
        
        // 设置文件列表背景
        fileListLayout.setBackgroundColor(getBackgroundColor());
        
        mainLayout.addView(topBar);
        mainLayout.addView(fileListLayout);
    }
    
    private void showFileList() {
        mainLayout.removeAllViews();
        
        // 创建顶部栏
        LinearLayout topBar = createTopBar();
        
        // 创建文件列表区域
        fileListLayout = new LinearLayout(this);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        fileListLayout.setPadding(20, 20, 20, 20);
        fileListLayout.setBackgroundColor(getBackgroundColor());
        
        // 添加"继续阅读"（如果存在上次阅读的文件）
        addContinueReadingButton();
        
        // 扫描PDF文件
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
                continueBtn.setBackgroundColor(Color.parseColor("#FF5722")); // 橙色
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
        topBar.setBackgroundColor(getStatusBarColor()); // 日间卡其色，夜间黑色
        topBar.setPadding(20, 20, 20, 20);
        
        TextView title = new TextView(this);
        title.setText("PDF阅读器 v1.0.15"); // 版本号改为1.0.15
        title.setTextColor(nightMode ? Color.WHITE : Color.BLACK); // 根据夜间模式调整文字颜色
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
        
        // 添加"继续阅读"
        addContinueReadingButton();
        
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要存储权限来扫描文件", Toast.LENGTH_SHORT).show();
            showFileListWithoutScan();
            return;
        }
        
        try {
            // 使用标准路径获取Download文件夹
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
        
        // 添加更多文件选择选项
        addFileChooserOptions();
    }
    
    private void addFileButton(File file) {
        Button fileBtn = new Button(this);
        String fileName = getShortFileName(file.getName());
        
        // 显示阅读进度
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
        
        // 设置点击事件
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
        
        // 选项1：选择单个PDF文件
        Button singleFileBtn = new Button(this);
        singleFileBtn.setText("选择单个PDF文件");
        singleFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        singleFileBtn.setTextColor(Color.WHITE);
        singleFileBtn.setPadding(20, 30, 20, 30);
        singleFileBtn.setOnClickListener(v -> choosePdfFile());
        
        LinearLayout.LayoutParams singleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        singleParams.bottomMargin = 10;
        singleFileBtn.setLayoutParams(singleParams);
        
        // 选项2：扫描全盘PDF文件（Android 11+需要特殊权限）
        Button scanAllBtn = new Button(this);
        scanAllBtn.setText("扫描全盘PDF文件");
        scanAllBtn.setBackgroundColor(Color.parseColor("#2196F3"));
        scanAllBtn.setTextColor(Color.WHITE);
        scanAllBtn.setPadding(20, 30, 20, 30);
        scanAllBtn.setOnClickListener(v -> scanAllPdfFiles());
        
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scanParams.bottomMargin = 10;
        scanAllBtn.setLayoutParams(scanParams);
        
        optionsLayout.addView(singleFileBtn);
        optionsLayout.addView(scanAllBtn);
        
        fileListLayout.addView(optionsLayout);
    }
    
    private void choosePdfFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // 对于Android 11+，尝试使用ACTION_OPEN_DOCUMENT以获得更好的文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            
            // 添加标志以持久化访问权限
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
        
        // 在新线程中扫描文件，避免阻塞UI
        new Thread(() -> {
            List<File> pdfFiles = new ArrayList<>();
            
            try {
                // 从常见的几个目录开始扫描
                String[] scanPaths = {
                    Environment.getExternalStorageDirectory().getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath(),
                    Environment.getDataDirectory().getAbsolutePath()
                };
                
                for (String path : scanPaths) {
                    try {
                        scanDirectoryForPdf(new File(path), pdfFiles);
                    } catch (SecurityException e) {
                        Log.e("PDF_DEBUG", "无法访问目录: " + path);
                    }
                }
                
            } catch (Exception e) {
                Log.e("PDF_DEBUG", "扫描错误: " + e.getMessage());
            }
            
            // 回到UI线程显示结果
            runOnUiThread(() -> {
                fileListLayout.removeAllViews();
                
                // 添加"继续阅读"
                addContinueReadingButton();
                
                if (pdfFiles.isEmpty()) {
                    showNoFilesMessage();
                } else {
                    for (File file : pdfFiles) {
                        addFileButton(file);
                    }
                }
                
                addFileChooserOptions();
            });
            
        }).start();
    }
    
    private void scanDirectoryForPdf(File directory, List<File> pdfFiles) {
        if (directory == null || !directory.exists() || !directory.canRead()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归扫描子目录，但避免系统目录和隐藏目录
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
            
            // 恢复阅读位置
            currentPage = getReadingPosition(filePath);
            leftPage = getHalfPageLeftState(filePath);
            
            // 确保页码在有效范围内
            if (currentPage >= totalPages) {
                currentPage = totalPages - 1;
            }
            if (currentPage < 0) {
                currentPage = 0;
            }
            
            // 保存为最后打开的文件
            saveLastOpenedFile(filePath);
            
            // 切换到阅读界面
            showReaderView();
            
        } catch (SecurityException e) {
            Toast.makeText(this, "权限不足，无法访问文件", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        } catch (IOException e) {
            Toast.makeText(this, "无法打开PDF文件，可能文件已损坏", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        } catch (Exception e) {
            Toast.makeText(this, "未知错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void openPdfFromUri(Uri uri) {
        try {
            // 获取ContentResolver
            ContentResolver resolver = getContentResolver();
            
            // 尝试获取文件信息
            String displayName = null;
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex);
                    }
                }
            }
            
            // 创建临时文件名
            String tempFileName = displayName != null ? displayName : 
                "temp_pdf_" + System.currentTimeMillis() + ".pdf";
            File tempFile = new File(getCacheDir(), tempFileName);
            
            // 复制文件到临时目录
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
                
                // 打开临时文件
                openPdfFile(tempFile.getAbsolutePath());
                
                // 清理旧的临时文件
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
                // DocumentProvider
                String wholeID = DocumentsContract.getDocumentId(uri);
                
                if (wholeID != null) {
                    String[] split = wholeID.split(":");
                    if (split.length > 1) {
                        String type = split[0];
                        String id = split[1];
                        
                        if ("primary".equalsIgnoreCase(type)) {
                            // 主存储
                            filePath = Environment.getExternalStorageDirectory() + "/" + id;
                        } else {
                            // 外部存储或SD卡
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
                        // 有些设备返回的ID不带冒号
                        filePath = Environment.getExternalStorageDirectory() + "/" + wholeID;
                    }
                }
            } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                // MediaStore (and general content:// URIs)
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
                // 文件URI
                filePath = uri.getPath();
            }
            
            // 最后尝试直接获取路径
            if (filePath == null) {
                filePath = uri.getPath();
            }
            
            // 验证文件是否存在
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
        
        // 使用FrameLayout作为阅读器容器
        readerContainer = new FrameLayout(this);
        readerContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        readerContainer.setBackgroundColor(getBackgroundColor());
        
        // PDF显示区域
        pdfImageView = new ImageView(this);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        pdfImageView.setLayoutParams(imageParams);
        pdfImageView.setScaleType(ImageView.ScaleType.MATRIX); // 改为MATRIX以支持缩放
        pdfImageView.setBackgroundColor(getBackgroundColor());
        
        // 重置缩放参数
        scaleFactor = 1.0f;
        matrix.reset();
        savedMatrix.reset();
        mode = NONE;
        
        // 添加触摸监听器 - 增强版，支持任意位置缩放和拖动
        pdfImageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ImageView view = (ImageView) v;
                
                // 处理触摸事件
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        // 单指触摸
                        savedMatrix.set(matrix);
                        startPoint.set(event.getX(), event.getY());
                        mode = DRAG;
                        
                        // 检查双击
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastClickTime < DOUBLE_TAP_TIME_THRESHOLD) {
                            // 双击事件 - 恢复原始大小
                            resetScale();
                            return true;
                        }
                        lastClickTime = currentTime;
                        break;
                        
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // 两指触摸开始
                        oldDistance = spacing(event);
                        if (oldDistance > 10f) {
                            savedMatrix.set(matrix);
                            midPoint(midPoint, event);
                            mode = ZOOM;
                        }
                        break;
                        
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        // 触摸结束
                        mode = NONE;
                        
                        // 居中显示（如果图片小于视图）
                        centerImage();
                        
                        // 如果不是缩放状态，处理单指点击翻页
                        if (scaleFactor <= 1.01f) { // 基本没有缩放时
                            float x = event.getX();
                            float width = v.getWidth();
                            
                            // 原有的翻页逻辑
                            if (isRotated) {
                                // 旋转后，原来的左右变成了上下
                                float height = v.getHeight();
                                float y = event.getY();
                                
                                // 点击上部区域 (高度1/3)：下一页
                                if (y < height / 3) {
                                    goToNextPage();
                                }
                                // 点击下部区域 (高度2/3-3/3)：上一页
                                else if (y > height * 2 / 3) {
                                    goToPrevPage();
                                }
                                // 点击中间区域：切换控制栏显示/隐藏
                                else {
                                    toggleControls();
                                }
                            } else {
                                // 正常竖屏模式
                                // 点击左侧区域 (宽度1/3)：下一页
                                if (x < width / 3) {
                                    goToNextPage();
                                }
                                // 点击右侧区域 (宽度2/3-3/3)：上一页
                                else if (x > width * 2 / 3) {
                                    goToPrevPage();
                                }
                                // 点击中间区域：切换控制栏显示/隐藏
                                else {
                                    toggleControls();
                                }
                            }
                        }
                        break;
                        
                    case MotionEvent.ACTION_MOVE:
                        if (mode == DRAG) {
                            // 单指拖动
                            matrix.set(savedMatrix);
                            float dx = event.getX() - startPoint.x;
                            float dy = event.getY() - startPoint.y;
                            
                            // 限制拖动范围，防止拖出边界
                            matrix.postTranslate(dx, dy);
                            limitDrag();
                        } else if (mode == ZOOM) {
                            // 两指缩放
                            float newDist = spacing(event);
                            if (newDist > 10f) {
                                matrix.set(savedMatrix);
                                float scale = newDist / oldDistance;
                                
                                // 更新缩放比例
                                scaleFactor *= scale;
                                
                                // 限制缩放范围
                                if (scaleFactor < minScale) {
                                    scaleFactor = minScale;
                                    scale = minScale / (scaleFactor / scale);
                                } else if (scaleFactor > maxScale) {
                                    scaleFactor = maxScale;
                                    scale = maxScale / (scaleFactor / scale);
                                }
                                
                                // 以两指中心点为缩放中心
                                matrix.postScale(scale, scale, midPoint.x, midPoint.y);
                                
                                // 缩放后限制位置
                                limitDrag();
                            }
                        }
                        break;
                }
                
                // 应用矩阵变化
                view.setImageMatrix(matrix);
                return true;
            }
        });
        
        // 创建顶部控制栏
        LinearLayout topBar = createReaderTopBar();
        topBar.setId(View.generateViewId());
        
        // 创建底部页码显示
        pageTextView = new TextView(this);
        pageTextView.setId(View.generateViewId());
        pageTextView.setTextColor(getTextColor());
        pageTextView.setTextSize(14);
        pageTextView.setBackgroundColor(Color.parseColor("#80000000")); // 半透明背景
        pageTextView.setPadding(10, 5, 10, 5);
        pageTextView.setGravity(Gravity.CENTER);
        
        // 上一页 (右下角)
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
        
        // 下一页按钮 (左下角)
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
        
        // 跳转按钮 (中间)
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
        
        // 底部页码显示布局参数
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pageParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pageParams.bottomMargin = 20;
        pageTextView.setLayoutParams(pageParams);
        
        // 添加所有视图到容器
        readerContainer.addView(pdfImageView);
        readerContainer.addView(topBar);
        readerContainer.addView(prevBtn);
        readerContainer.addView(nextBtn);
        readerContainer.addView(jumpBtn);
        readerContainer.addView(pageTextView);
        
        mainLayout.addView(readerContainer);
        
        // 延迟显示当前页面，确保布局完成
        pdfImageView.postDelayed(new Runnable() {
            @Override
            public void run() {
                displayCurrentPage();
            }
        }, 100);
    }
    
    // 计算两指距离
    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }
    
    // 计算两指中点
    private void midPoint(PointF point, MotionEvent event) {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }
    
    // 居中图片方法
    private void centerImage() {
        if (pdfImageView == null) return;
        
        // 检查视图是否已经测量完成
        if (pdfImageView.getWidth() == 0 || pdfImageView.getHeight() == 0) {
            // 视图还没有测量完成，延迟重试
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
        
        // 获取当前矩阵值
        float[] values = new float[9];
        matrix.getValues(values);
        float currentScale = values[Matrix.MSCALE_X];
        
        // 如果是初始状态（scaleFactor = 1.0），计算合适的缩放比例
        if (Math.abs(scaleFactor - 1.0f) < 0.01f) {
            float scaleX = (float) viewWidth / bitmapWidth;
            float scaleY = (float) viewHeight / bitmapHeight;
            float scale = Math.min(scaleX, scaleY);
            
            // 应用缩放
            matrix.postScale(scale, scale);
            scaleFactor = scale;
            
            // 重新获取矩阵值
            matrix.getValues(values);
            currentScale = values[Matrix.MSCALE_X];
        }
        
        // 计算缩放后的尺寸
        float scaledWidth = bitmapWidth * currentScale;
        float scaledHeight = bitmapHeight * currentScale;
        
        // 计算居中偏移
        float dx = (viewWidth - scaledWidth) / 2f;
        float dy = (viewHeight - scaledHeight) / 2f;
        
        // 应用居中
        values[Matrix.MTRANS_X] = dx;
        values[Matrix.MTRANS_Y] = dy;
        matrix.setValues(values);
        
        pdfImageView.setImageMatrix(matrix);
        pdfImageView.invalidate();
    }
    
    // 限制拖动范围，防止图片被拖出边界
    private void limitDrag() {
        // 获取图片的实际边界
        float[] values = new float[9];
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        
        // 获取ImageView的边界
        int viewWidth = pdfImageView.getWidth();
        int viewHeight = pdfImageView.getHeight();
        
        // 获取图片的原始尺寸
        BitmapDrawable drawable = (BitmapDrawable) pdfImageView.getDrawable();
        if (drawable == null) return;
        Bitmap bitmap = drawable.getBitmap();
        if (bitmap == null) return;
        
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        
        // 计算缩放后的图片尺寸
        float scaledWidth = bitmapWidth * scale;
        float scaledHeight = bitmapHeight * scale;
        
        // 限制横向位置
        if (scaledWidth > viewWidth) {
            // 图片宽度大于视图宽度，限制左右边界
            if (transX > 0) {
                transX = 0; // 不能向右拖出左边界
            } else if (transX < viewWidth - scaledWidth) {
                transX = viewWidth - scaledWidth; // 不能向左拖出右边界
            }
        } else {
            // 图片宽度小于视图宽度，居中对齐
            transX = (viewWidth - scaledWidth) / 2;
        }
        
        // 限制纵向位置
        if (scaledHeight > viewHeight) {
            // 图片高度大于视图高度，限制上下边界
            if (transY > 0) {
                transY = 0; // 不能向下拖出上边界
            } else if (transY < viewHeight - scaledHeight) {
                transY = viewHeight - scaledHeight; // 不能向上拖出下边界
            }
        } else {
            // 图片高度小于视图高度，居中对齐
            transY = (viewHeight - scaledHeight) / 2;
        }
        
        // 应用限制后的位置
        values[Matrix.MTRANS_X] = transX;
        values[Matrix.MTRANS_Y] = transY;
        matrix.setValues(values);
        
        // 更新ImageView
        pdfImageView.setImageMatrix(matrix);
    }
    
    private void resetScale() {
        // 恢复原始大小和位置
        scaleFactor = 1.0f;
        matrix.reset();
        
        // 居中显示
        centerImage();
    }
    
    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor()); // 日间卡其色，夜间黑色
        topBar.setPadding(0, 5, 0, 5); // 去除左右内边距，让按钮完全占满
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        topBar.setLayoutParams(params);
        
        // 创建所有按钮的平均分配参数
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f); // 使用权重平均分配
        
        // 返回按钮 - 使用 goBackToFileList() 方法
        Button backBtn = new Button(this);
        backBtn.setText("返回");
        backBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(12);
        backBtn.setPadding(0, 5, 0, 5); // 减少内边距
        backBtn.setAllCaps(false); // 禁用大写转换
        backBtn.setLayoutParams(btnParams);
        backBtn.setOnClickListener(v -> goBackToFileList());
        
        // 夜间模式按钮
        Button nightBtn = new Button(this);
        nightBtn.setText(nightMode ? "日间" : "夜间");
        nightBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        nightBtn.setTextColor(Color.WHITE);
        nightBtn.setTextSize(12);
        nightBtn.setPadding(0, 5, 0, 5);
        nightBtn.setAllCaps(false);
        nightBtn.setLayoutParams(btnParams);
        nightBtn.setOnClickListener(v -> toggleNightMode());
        
        // 整页/半页按钮
        halfPageBtn = new Button(this);
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        halfPageBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        halfPageBtn.setTextColor(Color.WHITE);
        halfPageBtn.setTextSize(12);
        halfPageBtn.setPadding(0, 5, 0, 5);
        halfPageBtn.setAllCaps(false);
        halfPageBtn.setLayoutParams(btnParams);
        halfPageBtn.setOnClickListener(v -> toggleHalfPageMode());
        
        // 单页/双页按钮
        pageModeBtn = new Button(this);
        pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        pageModeBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        pageModeBtn.setTextColor(Color.WHITE);
        pageModeBtn.setTextSize(12);
        pageModeBtn.setPadding(0, 5, 0, 5);
        pageModeBtn.setAllCaps(false);
        pageModeBtn.setLayoutParams(btnParams);
        pageModeBtn.setOnClickListener(v -> toggleDoublePageMode());
        
        // 旋转按钮
        rotateBtn = new Button(this);
        rotateBtn.setText(isRotated ? "转回" : "旋转");
        rotateBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        rotateBtn.setTextColor(Color.WHITE);
        rotateBtn.setTextSize(12);
        rotateBtn.setPadding(0, 5, 0, 5);
        rotateBtn.setAllCaps(false);
        rotateBtn.setLayoutParams(btnParams);
        rotateBtn.setOnClickListener(v -> toggleRotation());
        
        // 将所有按钮添加到顶部栏
        topBar.addView(backBtn);
        topBar.addView(nightBtn);
        topBar.addView(halfPageBtn);
        topBar.addView(pageModeBtn);
        topBar.addView(rotateBtn);
        
        return topBar;
    }
    
    private void toggleRotation() {
        // 切换旋转状态
        isRotated = !isRotated;
        
        // 更新旋转按钮文本
        if (rotateBtn != null) {
            rotateBtn.setText(isRotated ? "转回" : "旋转");
        }
        
        // 保存设置
        saveSettings();
        
        // 重新显示当前页面以应用旋转
        if (pdfRenderer != null) {
            displayCurrentPage();
        }
    }
    
    private void toggleHalfPageMode() {
        // 切换整页/半页模式
        halfPageMode = !halfPageMode;
        
        // 更新按钮文本
        if (halfPageBtn != null) {
            halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        }
        
        // 保存设置
        saveSettings();
        
        // 重新显示当前页面以应用新的页面模式
        if (pdfRenderer != null) {
            displayCurrentPage();
        }
    }
    
    private void toggleDoublePageMode() {
        // 切换单页/双页模式
        doublePageMode = !doublePageMode;
        
        // 更新按钮文本
        if (pageModeBtn != null) {
            pageModeBtn.setText(doublePageMode ? "单页" : "双页");
        }
        
        // 保存设置
        saveSettings();
        
        // 重新显示当前页面以应用新的页面模式
        if (pdfRenderer != null) {
            displayCurrentPage();
        }
    }
    
    private void toggleControls() {
        controlsVisible = !controlsVisible;
        
        // 获取所有控制元素
        View topBar = readerContainer.findViewById(readerContainer.getChildAt(1).getId());
        View prevBtn = readerContainer.getChildAt(2);
        View nextBtn = readerContainer.getChildAt(3);
        View jumpBtn = readerContainer.getChildAt(4);
        View pageText = readerContainer.getChildAt(5);
        
        if (controlsVisible) {
            // 显示控制元素
            topBar.setVisibility(View.VISIBLE);
            prevBtn.setVisibility(View.VISIBLE);
            nextBtn.setVisibility(View.VISIBLE);
            jumpBtn.setVisibility(View.VISIBLE);
            pageText.setVisibility(View.VISIBLE);
        } else {
            // 隐藏控制元素
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
                        // 如果是半页模式，从新页面的左半页开始
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
    
    // 反转图片黑白颜色的方法
    private Bitmap invertColors(Bitmap bitmap) {
        if (bitmap == null) return null;
        
        Bitmap invertedBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(invertedBitmap);
        
        // 创建颜色矩阵来反转颜色
        ColorMatrix colorMatrix = new ColorMatrix(new float[] {
            -1, 0, 0, 0, 255,  // 红色通道反转
            0, -1, 0, 0, 255,  // 绿色通道反转
            0, 0, -1, 0, 255,  // 蓝色通道反转
            0, 0, 0, 1, 0      // 透明度不变
        });
        
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        
        // 绘制原始图片并应用颜色反转
        canvas.drawBitmap(bitmap, 0, 0, paint);
        
        return invertedBitmap;
    }
    
    // 旋转图片90度的方法
    private Bitmap rotateBitmap90(Bitmap bitmap) {
        if (bitmap == null) return null;
        
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        
        // 创建旋转后的Bitmap
        Bitmap rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        
        return rotatedBitmap;
    }
    
    // 创建双页Bitmap的方法（居中显示，两页间不留空隙）
    private Bitmap createDoublePageBitmap(int leftPageNum, int rightPageNum) {
        try {
            // 获取屏幕尺寸
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            
            // 如果旋转了90度，交换宽高
            if (isRotated) {
                int temp = screenWidth;
                screenWidth = screenHeight;
                screenHeight = temp;
            }
            
            // 创建一个足够大的Bitmap来容纳两页（居中显示）
            Bitmap doubleBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(doubleBitmap);
            
            // 设置画布背景色
            canvas.drawColor(getBackgroundColor());
            
            // 计算统一的缩放比例，确保两页高度一致
            float unifiedScale = 1.0f;
            int unifiedScaledHeight = 0;
            int leftScaledWidth = 0;
            int rightScaledWidth = 0;
            
            // 获取两页的原始尺寸
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
            
            // 计算统一的缩放比例
            // 原则：1. 两页高度一致 2. 总宽度不超过屏幕宽度 3. 高度不超过屏幕高度
            
            // 计算两页的平均高度
            int maxPageHeight = Math.max(leftPageHeight, rightPageHeight);
            
            // 计算缩放比例：高度不超过屏幕高度的95%
            float scaleByHeight = (float) (screenHeight * 0.95) / maxPageHeight;
            
            // 计算缩放比例：总宽度不超过屏幕宽度的95%
            int totalPageWidth = leftPageWidth + rightPageWidth;
            float scaleByWidth = (float) (screenWidth * 0.95) / totalPageWidth;
            
            // 取较小的缩放比例，确保两页都能完整显示
            unifiedScale = Math.min(scaleByHeight, scaleByWidth);
            
            // 计算缩放后的尺寸
            unifiedScaledHeight = (int) (maxPageHeight * unifiedScale);
            leftScaledWidth = (int) (leftPageWidth * unifiedScale);
            rightScaledWidth = (int) (rightPageWidth * unifiedScale);
            
            // 计算居中位置
            int totalScaledWidth = leftScaledWidth + rightScaledWidth;
            int startX = (screenWidth - totalScaledWidth) / 2;
            int startY = (screenHeight - unifiedScaledHeight) / 2;
            
            // 绘制左页
            if (leftPageNum < totalPages) {
                PdfRenderer.Page leftPage = pdfRenderer.openPage(leftPageNum);
                // 提高渲染质量：使用更大的Bitmap（4倍分辨率）
                Bitmap leftBitmap = Bitmap.createBitmap(
                    Math.max((int)(leftPageWidth * unifiedScale * 4), 1),  // 4倍分辨率，确保高质量
                    Math.max((int)(leftPageHeight * unifiedScale * 4), 1),
                    Bitmap.Config.ARGB_8888
                );
                leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                leftPage.close();
                
                // 高质量缩放
                leftBitmap = Bitmap.createScaledBitmap(leftBitmap, leftScaledWidth, unifiedScaledHeight, true);
                
                // 夜间模式下反转图片颜色
                if (nightMode) {
                    leftBitmap = invertColors(leftBitmap);
                }
                
                canvas.drawBitmap(leftBitmap, startX, startY, null);
            }
            
            // 绘制右页（紧贴左页，不留空隙）
            if (rightPageNum < totalPages) {
                PdfRenderer.Page rightPage = pdfRenderer.openPage(rightPageNum);
                // 提高渲染质量：使用更大的Bitmap（4倍分辨率）
                Bitmap rightBitmap = Bitmap.createBitmap(
                    Math.max((int)(rightPageWidth * unifiedScale * 4), 1),  // 4倍分辨率，确保高质量
                    Math.max((int)(rightPageHeight * unifiedScale * 4), 1),
                    Bitmap.Config.ARGB_8888
                );
                rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                rightPage.close();
                
                // 高质量缩放
                rightBitmap = Bitmap.createScaledBitmap(rightBitmap, rightScaledWidth, unifiedScaledHeight, true);
                
                // 夜间模式下反转图片颜色
                if (nightMode) {
                    rightBitmap = invertColors(rightBitmap);
                }
                
                canvas.drawBitmap(rightBitmap, startX + leftScaledWidth, startY, null);
            }
            
            // 如果旋转了90度，旋转整个双页图
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
            if (doublePageMode) { // 双页模式
                // 双页模式下，currentPage代表当前显示的右页页码
                // 确保页码为偶数，以保证右1左2，右3左4，右5左6的顺序
                int basePage = currentPage;
                if (basePage % 2 != 0) {
                    basePage--; // 如果是奇数页，减1变成偶数页
                }
                
                int rightPageNum = basePage;     // 右页
                int leftPageNum = basePage + 1;  // 左页
                
                // 确保页码在有效范围内
                if (rightPageNum >= totalPages) {
                    rightPageNum = totalPages - 1;
                }
                if (leftPageNum >= totalPages) {
                    leftPageNum = totalPages - 1;
                }
                
                // 创建双页Bitmap
                Bitmap doubleBitmap = createDoublePageBitmap(leftPageNum, rightPageNum);
                
                if (doubleBitmap != null) {
                    // 设置图片到ImageView
                    pdfImageView.setImageBitmap(doubleBitmap);
                    
                    // 更新页码显示
                    if (leftPageNum < totalPages) {
                        pageTextView.setText((leftPageNum + 1) + "," + (rightPageNum + 1) + "/" + totalPages);
                    } else {
                        pageTextView.setText((leftPageNum + 1) + "/" + totalPages);
                    }
                    
                    // 立即显示图片，然后延迟居中
                    pdfImageView.invalidate();
                    
                    // 延迟执行居中，确保布局完成
                    pdfImageView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            centerImage();
                        }
                    }, 100);
                }
                
            } else { // 单页模式（可能包含半页模式）
                PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
                
                // 获取页面原始尺寸
                int pageWidth = page.getWidth();
                int pageHeight = page.getHeight();
                
                // 获取屏幕尺寸
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                
                // 如果旋转了90度，交换宽高
                if (isRotated) {
                    int temp = screenWidth;
                    screenWidth = screenHeight;
                    screenHeight = temp;
                }
                
                // 计算保持长宽比的缩放比例
                float scale = Math.min(
                    (float) screenWidth / pageWidth,
                    (float) screenHeight / pageHeight
                );
                
                // 半页模式下，重新计算缩放比例使宽度撑满
                if (halfPageMode) {
                    // 半页模式：半页宽度应该撑满屏幕宽度
                    scale = Math.min(
                        (float) screenWidth / (pageWidth / 2),  // 半页宽度撑满
                        (float) screenHeight / pageHeight
                    );
                }
                
                // 提高渲染质量：使用更高的分辨率（4倍）确保放大后清晰
                int highResWidth = Math.max((int)(pageWidth * scale * 4), 1);
                int highResHeight = Math.max((int)(pageHeight * scale * 4), 1);
                
                // 创建高分辨率的Bitmap - 使用ARGB_8888确保最高质量
                Bitmap highResBitmap = Bitmap.createBitmap(
                    highResWidth,
                    highResHeight,
                    Bitmap.Config.ARGB_8888
                );
                
                // 渲染页面到高分辨率Bitmap - 使用最高渲染模式
                page.render(highResBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                
                // 计算最终显示尺寸
                int scaledWidth = (int) (pageWidth * scale);
                int scaledHeight = (int) (pageHeight * scale);
                
                // 高质量缩放：使用双线性插值提高缩放质量
                Bitmap bitmap = Bitmap.createScaledBitmap(highResBitmap, scaledWidth, scaledHeight, true);
                
                // 释放高分辨率Bitmap的内存
                if (!highResBitmap.isRecycled() && highResBitmap != bitmap) {
                    highResBitmap.recycle();
                }
                
                // 半页模式下，进行高质量裁剪
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
                
                // 夜间模式下反转图片颜色（黑白反转）
                if (nightMode) {
                    bitmap = invertColors(bitmap);
                }
                
                // 如果旋转了90度，旋转图片
                if (isRotated) {
                    bitmap = rotateBitmap90(bitmap);
                }
                
                // 设置图片到ImageView
                pdfImageView.setImageBitmap(bitmap);
                
                // 启用硬件加速以提高绘制性能
                pdfImageView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                
                // 立即显示图片
                pdfImageView.invalidate();
                
                // 重置缩放参数
                scaleFactor = 1.0f;
                matrix.reset();
                
                // 延迟执行居中，确保布局完成
                pdfImageView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        centerImage();
                    }
                }, 100);
            }
            
            // 保存阅读位置
            saveReadingPosition();
            
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void goToPrevPage() {
        // 翻页时重置缩放
        if (scaleFactor > 1.01f) {
            resetScale();
        }
        
        if (doublePageMode) { // 双页模式
            // 双页模式下，一次后退两页
            if (currentPage > 1) {
                currentPage -= 2;
            } else {
                currentPage = 0;
                Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
            }
        } else if (halfPageMode) { // 半页模式
            if (leftPage) {
                // 当前是左半页，上一页应该是同页的右半部分
                leftPage = false;
            } else {
                // 当前是右半页，上一页应该是上一页的左半部分
                if (currentPage > 0) {
                    currentPage--;
                    leftPage = true;
                } else {
                    // 已经是第0页的右半页，没有上一页了
                    Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
                }
            }
        } else { // 单页模式
            if (currentPage > 0) {
                currentPage--;
            }
        }
        displayCurrentPage();
    }
    
    private void goToNextPage() {
        // 翻页时重置缩放
        if (scaleFactor > 1.01f) {
            resetScale();
        }
        
        if (doublePageMode) { // 双页模式
            // 双页模式下，一次前进两页
            if (currentPage < totalPages - 1) {
                currentPage += 2;
                if (currentPage >= totalPages) {
                    currentPage = totalPages - 1;
                }
            } else {
                Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
            }
        } else if (halfPageMode) { // 半页模式
            if (leftPage) {
                // 当前是左半页，下一页应该是下一页的右半部分
                if (currentPage < totalPages - 1) {
                    currentPage++;
                    leftPage = false;
                } else {
                    // 已经是最后一页的左半页，没有下一页了
                    Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
                }
            } else {
                // 当前是右半页，下一页应该是同页的左半部分
                leftPage = true;
            }
        } else { // 单页模式
            if (currentPage < totalPages - 1) {
                currentPage++;
            }
        }
        displayCurrentPage();
    }
    
    private void toggleNightMode() {
        nightMode = !nightMode;
        
        // 更新按钮文本
        if (nightModeBtn != null) {
            nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        }
        
        saveSettings();
        
        // 更新主题颜色
        updateThemeColors();
        
        // 如果正在阅读，重新显示当前页面以应用夜间模式
        if (pdfRenderer != null) {
            // 更新所有相关视图
            if (readerContainer != null) {
                readerContainer.setBackgroundColor(getBackgroundColor());
            }
            if (pdfImageView != null) {
                pdfImageView.setBackgroundColor(getBackgroundColor());
            }
            // 更新页码文字颜色
            if (pageTextView != null) {
                pageTextView.setTextColor(getTextColor());
            }
            // 更新顶部状态栏背景色
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
                // 调试信息
                Log.d("PDF_DEBUG", "URI Scheme: " + uri.getScheme());
                Log.d("PDF_DEBUG", "URI Path: " + uri.getPath());
                
                if (requestCode == FILE_PICKER_REQUEST_CODE) {
                    // 对于Android 11+，尝试获取持久化访问权限
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
                    
                    // 方法1：尝试获取真实路径
                    String filePath = getRealPathFromUri(uri);
                    Log.d("PDF_DEBUG", "Real Path: " + filePath);
                    
                    if (filePath != null && new File(filePath).exists()) {
                        openPdfFile(filePath);
                    } else {
                        // 方法2：使用URI直接打开（复制临时文件）
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
        
        // 清理临时文件
        if (currentFilePath != null && currentFilePath.contains("temp_pdf_")) {
            File tempFile = new File(currentFilePath);
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
