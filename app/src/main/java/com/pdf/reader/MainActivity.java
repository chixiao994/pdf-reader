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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import android.view.ViewGroup;
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
    private ImageView pdfImageView, leftPageImageView, rightPageImageView;
    private TextView pageTextView;
    private Button nightModeBtn, halfPageBtn, doublePageBtn, prevBtn, nextBtn, openFileBtn, refreshBtn, jumpBtn, rotateBtn;
    
    // PDF相关
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int currentPage = 0;
    private int totalPages = 0;
    private String currentFilePath;
    
    // 设置
    private boolean nightMode = false;
    private boolean halfPageMode = false;
    private boolean doublePageMode = false; // 新增：双页模式
    private boolean leftPage = false;
    private boolean controlsVisible = true; // 控制栏是否可见
    private int rotationAngle = 0; // 旋转角度：0, 90, 180, 270
    
    // 存储
    private SharedPreferences prefs;
    private static final String LAST_OPENED_FILE = "last_opened_file"; // 存储最后打开的文件路径
    private static final String AUTO_OPEN_LAST_FILE = "auto_open_last_file"; // 是否自动打开最后文件
    
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;
    private static final int DOCUMENT_TREE_REQUEST_CODE = 102; // 新增：访问文件夹权限
    
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
        
        // 创建界面
        createMainLayout();
        
        // 请求权限
        requestPermissions();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 检查是否应该自动打开上次阅读的文件
        checkAutoOpenLastFile();
    }
    
    private void checkAutoOpenLastFile() {
        // 获取上次打开的文件路径
        String lastOpenedFile = prefs.getString(LAST_OPENED_FILE, null);
        boolean autoOpenLastFile = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true); // 默认开启自动打开
        
        if (autoOpenLastFile && lastOpenedFile != null && !lastOpenedFile.isEmpty()) {
            File file = new File(lastOpenedFile);
            if (file.exists() && file.canRead()) {
                // 延迟一小段时间打开，确保UI已经加载完成
                new android.os.Handler().postDelayed(() -> {
                    // 检查当前是否已经在阅读界面
                    if (pdfRenderer == null) {
                        Toast.makeText(this, "正在打开上次阅读的文档...", Toast.LENGTH_SHORT).show();
                        openPdfFile(lastOpenedFile);
                    }
                }, 500);
            } else {
                // 文件不存在或不可读，清除记录
                prefs.edit().remove(LAST_OPENED_FILE).apply();
                Log.d("PDF_DEBUG", "上次打开的文件不存在或不可读: " + lastOpenedFile);
            }
        }
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                // 显示解释对话框
                if (shouldShowRequestPermissionRationale(
                        Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("需要存储权限来扫描PDF文件")
                        .setPositiveButton("确定", (dialog, which) -> {
                            requestPermissions(new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }, PERMISSION_REQUEST_CODE);
                        })
                        .setNegativeButton("取消", null)
                        .show();
                } else {
                    requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }, PERMISSION_REQUEST_CODE);
                }
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
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                showFileList();
            } else {
                Toast.makeText(this, "需要存储权限来扫描PDF文件", Toast.LENGTH_SHORT).show();
                // 即使没有权限，仍然显示基础界面，用户可以通过文件选择器选择文件
                showFileListWithoutScan();
            }
        }
    }
    
    private void loadSettings() {
        nightMode = prefs.getBoolean("night_mode", false);
        halfPageMode = prefs.getBoolean("half_page", false);
        doublePageMode = prefs.getBoolean("double_page", false); // 加载双页模式设置
        rotationAngle = prefs.getInt("rotation_angle", 0); // 加载旋转角度
    }
    
    private void saveSettings() {
        prefs.edit()
            .putBoolean("night_mode", nightMode)
            .putBoolean("half_page", halfPageMode)
            .putBoolean("double_page", doublePageMode) // 保存双页模式设置
            .putInt("rotation_angle", rotationAngle) // 保存旋转角度
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
                .putInt(currentFilePath + "_rotation", rotationAngle) // 保存旋转角度
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
    
    private int getSavedRotation(String filePath) {
        return prefs.getInt(filePath + "_rotation", 0);
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
                               "请点击下方按钮手动选择PDF文件");
        noPermissionText.setTextSize(16);
        noPermissionText.setGravity(android.view.Gravity.CENTER);
        noPermissionText.setTextColor(getTextColor());
        noPermissionText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noPermissionText);
        
        // 添加选择文件按钮
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
        
        // 添加"继续阅读"按钮（如果存在上次阅读的文件）
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
        title.setText("PDF阅读器");
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
        
        // 添加"继续阅读"按钮
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
                
                // 添加"继续阅读"按钮
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
            
            // 恢复阅读位置、半页状态和旋转角度
            currentPage = getReadingPosition(filePath);
            leftPage = getHalfPageLeftState(filePath);
            rotationAngle = getSavedRotation(filePath);
            
            // 确保页码在有效范围内
            if (currentPage >= totalPages) {
                currentPage = totalPages - 1;
            }
            if (currentPage < 0) {
                currentPage = 0;
            }
            
            // 确保旋转角度在有效范围内 (0, 90, 180, 270)
            rotationAngle = rotationAngle % 360;
            if (rotationAngle % 90 != 0) {
                rotationAngle = 0; // 如果不是90的倍数，重置为0
            }
            
            // 保存为最后打开的文件
            saveLastOpenedFile(filePath);
            
            // 切换到阅读界面
            showReaderView();
            
            Toast.makeText(this, "成功打开PDF: " + file.getName(), Toast.LENGTH_SHORT).show();
            
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
        
        // 创建一个容器来包含所有元素，这个容器会整体旋转
        FrameLayout rotatingContainer = new FrameLayout(this);
        FrameLayout.LayoutParams rotatingParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        rotatingContainer.setLayoutParams(rotatingParams);
        rotatingContainer.setRotation(rotationAngle); // 整个容器旋转
        rotatingContainer.setPivotX(0.5f); // 设置旋转中心为宽度的一半
        rotatingContainer.setPivotY(0.5f); // 设置旋转中心为高度的一半
        
        // 调整旋转容器的大小以适应旋转
        adjustContainerSizeForRotation(rotatingContainer);
        
        if (doublePageMode) {
            // 双页模式：创建水平布局显示两页
            LinearLayout doublePageLayout = new LinearLayout(this);
            doublePageLayout.setOrientation(LinearLayout.HORIZONTAL);
            doublePageLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            doublePageLayout.setBackgroundColor(getBackgroundColor());
            
            // 右侧页面（奇数页）
            rightPageImageView = new ImageView(this);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            rightPageImageView.setLayoutParams(rightParams);
            rightPageImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            rightPageImageView.setBackgroundColor(getBackgroundColor());
            
            // 左侧页面（偶数页）
            leftPageImageView = new ImageView(this);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            leftPageImageView.setLayoutParams(leftParams);
            leftPageImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            leftPageImageView.setBackgroundColor(getBackgroundColor());
            
            // 添加触摸监听器（使用已旋转的坐标）
            View.OnTouchListener doublePageTouchListener = new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        float x = event.getX();
                        float width = v.getWidth();
                        
                        // 调整触摸坐标以适应旋转
                        float[] adjustedCoords = adjustTouchCoordinates(x, event.getY(), width, v.getHeight());
                        float adjustedX = adjustedCoords[0];
                        float adjustedWidth = adjustedCoords[2];
                        
                        // 点击左侧区域 (宽度1/3)：下一页
                        if (adjustedX < adjustedWidth / 3) {
                            goToNextPage();
                        }
                        // 点击右侧区域 (宽度2/3-3/3)：上一页
                        else if (adjustedX > adjustedWidth * 2 / 3) {
                            goToPrevPage();
                        }
                        // 点击中间区域：切换控制栏显示/隐藏
                        else {
                            toggleControls();
                        }
                    }
                    return true;
                }
            };
            
            rightPageImageView.setOnTouchListener(doublePageTouchListener);
            leftPageImageView.setOnTouchListener(doublePageTouchListener);
            
            doublePageLayout.addView(rightPageImageView);
            doublePageLayout.addView(leftPageImageView);
            rotatingContainer.addView(doublePageLayout);
            
        } else {
            // 单页模式
            pdfImageView = new ImageView(this);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            pdfImageView.setLayoutParams(imageParams);
            pdfImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pdfImageView.setBackgroundColor(getBackgroundColor());
            
            // 添加触摸监听器（使用已旋转的坐标）
            pdfImageView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        float x = event.getX();
                        float width = v.getWidth();
                        
                        // 调整触摸坐标以适应旋转
                        float[] adjustedCoords = adjustTouchCoordinates(x, event.getY(), width, v.getHeight());
                        float adjustedX = adjustedCoords[0];
                        float adjustedWidth = adjustedCoords[2];
                        
                        // 点击左侧区域 (宽度1/3)：下一页
                        if (adjustedX < adjustedWidth / 3) {
                            goToNextPage();
                        }
                        // 点击右侧区域 (宽度2/3-3/3)：上一页
                        else if (adjustedX > adjustedWidth * 2 / 3) {
                            goToPrevPage();
                        }
                        // 点击中间区域：切换控制栏显示/隐藏
                        else {
                            toggleControls();
                        }
                    }
                    return true;
                }
            });
            
            rotatingContainer.addView(pdfImageView);
        }
        
        // 创建顶部控制栏
        LinearLayout topBar = createReaderTopBar();
        topBar.setId(View.generateViewId());
        
        // 创建底部页码显示
        TextView bottomPageText = new TextView(this);
        bottomPageText.setId(View.generateViewId());
        bottomPageText.setTextColor(getTextColor());
        bottomPageText.setTextSize(14);
        bottomPageText.setBackgroundColor(Color.parseColor("#80000000"));
        bottomPageText.setPadding(10, 5, 10, 5);
        bottomPageText.setGravity(Gravity.CENTER);
        
        // 根据旋转角度设置按钮位置
        setupControlButtons();
        
        // 底部页码显示布局参数
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        // 根据旋转角度调整页码显示位置
        setPageTextPosition(pageParams);
        bottomPageText.setLayoutParams(pageParams);
        
        // 将控制元素添加到旋转容器
        rotatingContainer.addView(topBar);
        rotatingContainer.addView(prevBtn);
        rotatingContainer.addView(nextBtn);
        rotatingContainer.addView(jumpBtn);
        rotatingContainer.addView(bottomPageText);
        
        // 设置页面显示
        pageTextView = bottomPageText;
        
        // 将旋转容器添加到主容器
        readerContainer.addView(rotatingContainer);
        
        mainLayout.addView(readerContainer);
        
        // 显示当前页面
        displayCurrentPage();
    }    
    // 调整容器大小以适应旋转
    private void adjustContainerSizeForRotation(View container) {
        ViewGroup.LayoutParams params = container.getLayoutParams();
        if (params == null) {
            return;
        }
        
        // 获取屏幕尺寸
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        
        // 当旋转90度或270度时，宽高交换
        if (rotationAngle == 90 || rotationAngle == 270) {
            // 创建新的参数，宽高交换
            FrameLayout.LayoutParams newParams = new FrameLayout.LayoutParams(
                screenHeight, screenWidth);
            newParams.gravity = Gravity.CENTER;
            container.setLayoutParams(newParams);
        } else {
            // 正常方向
            FrameLayout.LayoutParams newParams = new FrameLayout.LayoutParams(
                screenWidth, screenHeight);
            container.setLayoutParams(newParams);
        }
    }
    
    // 根据旋转角度调整触摸坐标
    private float[] adjustTouchCoordinates(float x, float y, float width, float height) {
        float adjustedX = x;
        float adjustedY = y;
        float adjustedWidth = width;
        float adjustedHeight = height;
        
        switch (rotationAngle) {
            case 90:
                // 顺时针旋转90度，需要调整坐标
                adjustedX = y;
                adjustedY = width - x;
                adjustedWidth = height;
                adjustedHeight = width;
                break;
            case 180:
                // 旋转180度
                adjustedX = width - x;
                adjustedY = height - y;
                break;
            case 270:
                // 顺时针旋转270度（或逆时针90度）
                adjustedX = height - y;
                adjustedY = x;
                adjustedWidth = height;
                adjustedHeight = width;
                break;
            // 0度不需要调整
        }
        
        return new float[]{adjustedX, adjustedY, adjustedWidth, adjustedHeight};
    }
    
    private void setupControlButtons() {
        // 根据旋转角度设置按钮位置
        FrameLayout.LayoutParams prevParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        
        switch (rotationAngle) {
            case 0:
                // 正常方向
                prevParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                prevParams.rightMargin = 20;
                prevParams.bottomMargin = 80;
                
                nextParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
                nextParams.leftMargin = 20;
                nextParams.bottomMargin = 80;
                
                jumpParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                jumpParams.bottomMargin = 80;
                break;
                
            case 90:
                // 顺时针旋转90度
                prevParams.gravity = Gravity.TOP | Gravity.LEFT;
                prevParams.leftMargin = 80;
                prevParams.topMargin = 20;
                
                nextParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
                nextParams.leftMargin = 80;
                nextParams.bottomMargin = 20;
                
                jumpParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                jumpParams.leftMargin = 80;
                break;
                
            case 180:
                // 旋转180度
                prevParams.gravity = Gravity.TOP | Gravity.LEFT;
                prevParams.leftMargin = 20;
                prevParams.topMargin = 80;
                
                nextParams.gravity = Gravity.TOP | Gravity.RIGHT;
                nextParams.rightMargin = 20;
                nextParams.topMargin = 80;
                
                jumpParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                jumpParams.topMargin = 80;
                break;
                
            case 270:
                // 顺时针旋转270度
                prevParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                prevParams.rightMargin = 80;
                prevParams.bottomMargin = 20;
                
                nextParams.gravity = Gravity.TOP | Gravity.RIGHT;
                nextParams.rightMargin = 80;
                nextParams.topMargin = 20;
                
                jumpParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                jumpParams.rightMargin = 80;
                break;
        }
        
        // 上一页按钮 - 不单独旋转
        prevBtn = new Button(this);
        prevBtn.setText("上一页");
        prevBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setOnClickListener(v -> goToPrevPage());
        prevBtn.setLayoutParams(prevParams);
        
        // 下一页按钮 - 不单独旋转
        nextBtn = new Button(this);
        nextBtn.setText("下一页");
        nextBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        nextBtn.setTextColor(Color.WHITE);
        nextBtn.setOnClickListener(v -> goToNextPage());
        nextBtn.setLayoutParams(nextParams);
        
        // 跳转按钮 - 不单独旋转
        jumpBtn = new Button(this);
        jumpBtn.setText("跳转");
        jumpBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        jumpBtn.setTextColor(Color.WHITE);
        jumpBtn.setOnClickListener(v -> showJumpPageDialog());
        jumpBtn.setLayoutParams(jumpParams);
    }
        
        // 调整按钮大小以适应旋转
        adjustButtonSizeForRotation(jumpBtn);
        jumpBtn.setLayoutParams(jumpParams);
    }
    
    // 调整按钮大小以适应旋转
    private void adjustButtonSizeForRotation(Button button) {
        // 当旋转90度或270度时，增加按钮宽度以适应垂直文字
        if (rotationAngle == 90 || rotationAngle == 270) {
            button.setMinimumWidth(150); // 增加最小宽度
        }
    }
    
    private void setPageTextPosition(FrameLayout.LayoutParams params) {
        switch (rotationAngle) {
            case 0:
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                params.bottomMargin = 20;
                break;
            case 90:
                params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                params.leftMargin = 20;
                break;
            case 180:
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.topMargin = 20;
                break;
            case 270:
                params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                params.rightMargin = 20;
                break;
        }
    }
    
    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        
        // 根据旋转角度设置布局方向
        if (rotationAngle == 0 || rotationAngle == 180) {
            topBar.setOrientation(LinearLayout.HORIZONTAL);
        } else {
            topBar.setOrientation(LinearLayout.VERTICAL);
        }
        
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(10, 10, 10, 10);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        
        // 根据旋转角度调整顶部栏位置
        switch (rotationAngle) {
            case 0:
                params.gravity = Gravity.TOP;
                params.width = FrameLayout.LayoutParams.MATCH_PARENT;
                break;
            case 90:
                params.gravity = Gravity.RIGHT;
                params.height = FrameLayout.LayoutParams.MATCH_PARENT;
                break;
            case 180:
                params.gravity = Gravity.BOTTOM;
                params.width = FrameLayout.LayoutParams.MATCH_PARENT;
                break;
            case 270:
                params.gravity = Gravity.LEFT;
                params.height = FrameLayout.LayoutParams.MATCH_PARENT;
                break;
        }
        
        topBar.setLayoutParams(params);
        
        // 创建按钮，但不要单独旋转
        Button createButton(String text, View.OnClickListener listener) {
            Button button = new Button(this);
            button.setText(text);
            button.setBackgroundColor(Color.parseColor("#3700B3"));
            button.setTextColor(Color.WHITE);
            
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            
            if (rotationAngle == 0 || rotationAngle == 180) {
                btnParams.rightMargin = 10;
            } else {
                btnParams.bottomMargin = 10;
            }
            
            button.setLayoutParams(btnParams);
            button.setOnClickListener(listener);
            return button;
        }
        
        // 返回按钮
        Button backBtn = createButton("返回", v -> {
            closePdf();
            showFileList();
        });
        
        // 夜间模式按钮
        Button nightBtn = createButton(nightMode ? "日间" : "夜间", v -> toggleNightMode());
        
        // 半页模式按钮
        halfPageBtn = createButton(halfPageMode ? "整页" : "半页", v -> toggleHalfPageMode());
        
        // 双页模式按钮
        doublePageBtn = createButton(doublePageMode ? "单页" : "双页", v -> toggleDoublePageMode());
        
        // 旋转按钮
        rotateBtn = createButton("旋转 " + rotationAngle + "°", v -> rotatePage());
        
        topBar.addView(backBtn);
        topBar.addView(nightBtn);
        topBar.addView(halfPageBtn);
        topBar.addView(doublePageBtn);
        topBar.addView(rotateBtn);
        
        return topBar;
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
    
    private void toggleDoublePageMode() {
        doublePageMode = !doublePageMode;
        
        // 更新按钮文本
        if (doublePageBtn != null) {
            doublePageBtn.setText(doublePageMode ? "单页" : "双页");
        }
        
        // 保存设置
        saveSettings();
        
        // 重新创建阅读界面以应用双页模式
        showReaderView();
    }
    
    private void rotatePage() {
        // 每次旋转90度
        rotationAngle = (rotationAngle + 90) % 360;
        
        // 更新旋转按钮文本
        if (rotateBtn != null) {
            rotateBtn.setText("旋转 " + rotationAngle + "°");
        }
        
        // 保存设置
        saveSettings();
        saveReadingPosition();
        
        // 重新创建阅读界面以应用旋转
        showReaderView();
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
    
    // 旋转图片的方法
    private Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0) {
            return bitmap;
        }
        
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        
        // 创建旋转后的Bitmap
        Bitmap rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
        );
        
        return rotatedBitmap;
    }
    
    // 获取当前显示的两页页码（用于双页模式）
    private int[] getDoublePageNumbers() {
        int[] pages = new int[2];
        
        // 古籍从右向左阅读：右页为奇数页，左页为偶数页
        // 显示顺序：右1左2，右3左4，右5左6...
        
        if (currentPage % 2 == 0) {
            // 当前是偶数页（左页），显示前一个奇数页（右页）和当前页（左页）
            pages[0] = currentPage;      // 左页（偶数页）
            pages[1] = currentPage + 1;  // 右页（下一个奇数页）
        } else {
            // 当前是奇数页（右页），显示当前页（右页）和下一个偶数页（左页）
            pages[0] = currentPage - 1;  // 左页（前一个偶数页）
            pages[1] = currentPage;      // 右页（奇数页）
        }
        
        // 确保页码在有效范围内
        if (pages[0] < 0) pages[0] = 0;
        if (pages[1] >= totalPages) pages[1] = totalPages - 1;
        
        return pages;
    }
    
    private void displayCurrentPage() {
        if (pdfRenderer == null) return;
        
        try {
            if (doublePageMode) {
                // 双页模式显示
                int[] pageNumbers = getDoublePageNumbers();
                int leftPageNum = pageNumbers[0];  // 左页（偶数页）
                int rightPageNum = pageNumbers[1]; // 右页（奇数页）
                
                // 获取屏幕尺寸
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                
                // 根据旋转角度调整显示的尺寸
                int displayWidth = screenWidth;
                int displayHeight = screenHeight;
                
                // 当旋转90度或270度时，宽高交换
                if (rotationAngle == 90 || rotationAngle == 270) {
                    displayWidth = screenHeight;
                    displayHeight = screenWidth;
                }
                
                // 每页宽度为屏幕宽度的一半
                int pageWidth = displayWidth / 2;
                int pageHeight = displayHeight;
                
                // 显示左页（偶数页）
                if (leftPageNum < totalPages && leftPageNum >= 0) {
                    PdfRenderer.Page leftPage = pdfRenderer.openPage(leftPageNum);
                    
                    // 获取左页原始尺寸
                    int originalLeftWidth = leftPage.getWidth();
                    int originalLeftHeight = leftPage.getHeight();
                    
                    // 计算左页缩放比例
                    float leftScale = Math.min(
                        (float) pageWidth / originalLeftWidth,
                        (float) pageHeight / originalLeftHeight
                    );
                    
                    // 计算左页缩放后的尺寸
                    int scaledLeftWidth = (int) (originalLeftWidth * leftScale);
                    int scaledLeftHeight = (int) (originalLeftHeight * leftScale);
                    
                    // 创建左页Bitmap
                    Bitmap leftBitmap = Bitmap.createBitmap(scaledLeftWidth, scaledLeftHeight, Bitmap.Config.ARGB_8888);
                    leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    leftPage.close();
                    
                    // 应用旋转
                    if (rotationAngle != 0) {
                        leftBitmap = rotateBitmap(leftBitmap, rotationAngle);
                    }
                    
                    // 夜间模式下反转图片颜色
                    if (nightMode) {
                        leftBitmap = invertColors(leftBitmap);
                    }
                    
                    leftPageImageView.setImageBitmap(leftBitmap);
                } else {
                    leftPageImageView.setImageBitmap(null);
                }
                
                // 显示右页（奇数页）
                if (rightPageNum < totalPages && rightPageNum >= 0) {
                    PdfRenderer.Page rightPage = pdfRenderer.openPage(rightPageNum);
                    
                    // 获取右页原始尺寸
                    int originalRightWidth = rightPage.getWidth();
                    int originalRightHeight = rightPage.getHeight();
                    
                    // 计算右页缩放比例
                    float rightScale = Math.min(
                        (float) pageWidth / originalRightWidth,
                        (float) pageHeight / originalRightHeight
                    );
                    
                    // 计算右页缩放后的尺寸
                    int scaledRightWidth = (int) (originalRightWidth * rightScale);
                    int scaledRightHeight = (int) (originalRightHeight * rightScale);
                    
                    // 创建右页Bitmap
                    Bitmap rightBitmap = Bitmap.createBitmap(scaledRightWidth, scaledRightHeight, Bitmap.Config.ARGB_8888);
                    rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    rightPage.close();
                    
                    // 应用旋转
                    if (rotationAngle != 0) {
                        rightBitmap = rotateBitmap(rightBitmap, rotationAngle);
                    }
                    
                    // 夜间模式下反转图片颜色
                    if (nightMode) {
                        rightBitmap = invertColors(rightBitmap);
                    }
                    
                    rightPageImageView.setImageBitmap(rightBitmap);
                } else {
                    rightPageImageView.setImageBitmap(null);
                }
                
                // 更新页码显示
                pageTextView.setText("第" + (leftPageNum + 1) + "-" + (rightPageNum + 1) + "页 / 共" + totalPages + "页");
                
            } else {
                // 单页模式显示
                PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
                
                // 获取页面原始尺寸
                int pageWidth = page.getWidth();
                int pageHeight = page.getHeight();
                
                // 获取屏幕尺寸
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                
                // 根据旋转角度调整显示的尺寸
                int displayWidth = screenWidth;
                int displayHeight = screenHeight;
                
                // 当旋转90度或270度时，宽高交换
                if (rotationAngle == 90 || rotationAngle == 270) {
                    displayWidth = screenHeight;
                    displayHeight = screenWidth;
                }
                
                float scale = Math.min(
                    (float) displayWidth / pageWidth,
                    (float) displayHeight / pageHeight
                );
                
                // 计算缩放后的尺寸
                int scaledWidth = (int) (pageWidth * scale);
                int scaledHeight = (int) (pageHeight * scale);
                
                // 创建与页面比例匹配的Bitmap
                Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                
                if (halfPageMode) {
                    // 半边页模式
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    
                    // 裁剪半边
                    if (leftPage) {
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, scaledWidth / 2, scaledHeight);
                        pageTextView.setText((currentPage + 1) + "/" + totalPages + " (左)");
                    } else {
                        bitmap = Bitmap.createBitmap(bitmap, scaledWidth / 2, 0, scaledWidth / 2, scaledHeight);
                        pageTextView.setText((currentPage + 1) + "/" + totalPages + " (右)");
                    }
                } else {
                    // 整页模式
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    pageTextView.setText((currentPage + 1) + "/" + totalPages);
                }
                
                page.close();
                
                // 应用旋转
                if (rotationAngle != 0) {
                    bitmap = rotateBitmap(bitmap, rotationAngle);
                }
                
                // 夜间模式下反转图片颜色（黑白反转）
                if (nightMode) {
                    bitmap = invertColors(bitmap);
                }
                
                // 设置图片到ImageView
                pdfImageView.setImageBitmap(bitmap);
            }
            
            // 保存阅读位置
            saveReadingPosition();
            
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void goToPrevPage() {
        if (doublePageMode) {
            // 双页模式：每次翻两页（因为显示两页）
            if (currentPage > 1) {
                currentPage -= 2;
            } else if (currentPage > 0) {
                currentPage = 0;
            } else {
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
        if (doublePageMode) {
            // 双页模式：每次翻两页（因为显示两页）
            if (currentPage < totalPages - 2) {
                currentPage += 2;
            } else if (currentPage < totalPages - 1) {
                currentPage = totalPages - 1;
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
    // 调整容器大小以适应旋转
    private void adjustContainerSizeForRotation(FrameLayout container) {
        // 获取屏幕尺寸
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        
        if (rotationAngle == 90 || rotationAngle == 270) {
            // 当旋转90度或270度时，宽高交换
            FrameLayout.LayoutParams newParams = new FrameLayout.LayoutParams(
                screenHeight, screenWidth);
            newParams.gravity = Gravity.CENTER;
            container.setLayoutParams(newParams);
        } else {
            // 正常方向
            FrameLayout.LayoutParams newParams = new FrameLayout.LayoutParams(
                screenWidth, screenHeight);
            container.setLayoutParams(newParams);
        }
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
            if (leftPageImageView != null) {
                leftPageImageView.setBackgroundColor(getBackgroundColor());
            }
            if (rightPageImageView != null) {
                rightPageImageView.setBackgroundColor(getBackgroundColor());
            }
            // 更新页码文字颜色
            if (pageTextView != null) {
                pageTextView.setTextColor(getTextColor());
            }
            // 重新创建顶部状态栏来更新颜色
            if (readerContainer != null && readerContainer.getChildCount() > 1) {
                LinearLayout topBar = (LinearLayout) readerContainer.getChildAt(1);
                if (topBar != null) {
                    topBar.setBackgroundColor(getStatusBarColor());
                }
            }
            displayCurrentPage();
        }
    }
    
    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        if (halfPageBtn != null) {
            halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        }
        saveSettings();
        displayCurrentPage();
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
