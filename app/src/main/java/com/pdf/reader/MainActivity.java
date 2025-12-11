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
    private FrameLayout readerContainer, rotateContainer;
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
    private boolean doublePageMode = false;
    private boolean leftPage = false;
    private boolean controlsVisible = true;
    private int rotationAngle = 0;
    
    // 存储
    private SharedPreferences prefs;
    private static final String LAST_OPENED_FILE = "last_opened_file";
    private static final String AUTO_OPEN_LAST_FILE = "auto_open_last_file";
    
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;
    
    // 颜色常量
    private static final int DAY_MODE_BG = Color.WHITE;
    private static final int DAY_MODE_TEXT = Color.BLACK;
    private static final int NIGHT_MODE_BG = Color.BLACK;
    private static final int NIGHT_MODE_TEXT = Color.WHITE;
    private static final int DAY_STATUS_BAR_COLOR = Color.parseColor("#F0E68C");
    private static final int NIGHT_STATUS_BAR_COLOR = Color.BLACK;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        prefs = getSharedPreferences("pdf_reader", MODE_PRIVATE);
        loadSettings();
        
        createMainLayout();
        requestPermissions();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        checkAutoOpenLastFile();
    }
    
    private void checkAutoOpenLastFile() {
        String lastOpenedFile = prefs.getString(LAST_OPENED_FILE, null);
        boolean autoOpenLastFile = prefs.getBoolean(AUTO_OPEN_LAST_FILE, true);
        
        if (autoOpenLastFile && lastOpenedFile != null && !lastOpenedFile.isEmpty()) {
            File file = new File(lastOpenedFile);
            if (file.exists() && file.canRead()) {
                new android.os.Handler().postDelayed(() -> {
                    if (pdfRenderer == null) {
                        Toast.makeText(this, "正在打开上次阅读的文档...", Toast.LENGTH_SHORT).show();
                        openPdfFile(lastOpenedFile);
                    }
                }, 500);
            } else {
                prefs.edit().remove(LAST_OPENED_FILE).apply();
                Log.d("PDF_DEBUG", "上次打开的文件不存在或不可读: " + lastOpenedFile);
            }
        }
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                
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
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                showFileList();
            } else {
                Toast.makeText(this, "需要存储权限来扫描PDF文件", Toast.LENGTH_SHORT).show();
                showFileListWithoutScan();
            }
        }
    }
    
    private void loadSettings() {
        nightMode = prefs.getBoolean("night_mode", false);
        halfPageMode = prefs.getBoolean("half_page", false);
        doublePageMode = prefs.getBoolean("double_page", false);
        rotationAngle = prefs.getInt("rotation_angle", 0);
    }
    
    private void saveSettings() {
        prefs.edit()
            .putBoolean("night_mode", nightMode)
            .putBoolean("half_page", halfPageMode)
            .putBoolean("double_page", doublePageMode)
            .putInt("rotation_angle", rotationAngle)
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
                .putInt(currentFilePath + "_rotation", rotationAngle)
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
    
    private int getSavedRotation(String filePath) {
        return prefs.getInt(filePath + "_rotation", 0);
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
    
    private void showFileListWithoutScan() {
        mainLayout.removeAllViews();
        
        LinearLayout topBar = createTopBar();
        
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
        
        openFileBtn = new Button(this);
        openFileBtn.setText("选择PDF文件");
        openFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        openFileBtn.setTextColor(Color.WHITE);
        openFileBtn.setOnClickListener(v -> choosePdfFile());
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
        title.setText("PDF阅读器");
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
                    try {
                        scanDirectoryForPdf(new File(path), pdfFiles);
                    } catch (SecurityException e) {
                        Log.e("PDF_DEBUG", "无法访问目录: " + path);
                    }
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
            
            currentPage = getReadingPosition(filePath);
            leftPage = getHalfPageLeftState(filePath);
            rotationAngle = getSavedRotation(filePath);
            
            if (currentPage >= totalPages) {
                currentPage = totalPages - 1;
            }
            if (currentPage < 0) {
                currentPage = 0;
            }
            
            rotationAngle = rotationAngle % 360;
            if (rotationAngle % 90 != 0) {
                rotationAngle = 0;
            }
            
            saveLastOpenedFile(filePath);
            
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
        
        // 创建旋转容器，用于整体旋转
        rotateContainer = new FrameLayout(this);
        rotateContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        rotateContainer.setBackgroundColor(getBackgroundColor());
        
        // 设置整体旋转角度
        rotateContainer.setRotation(rotationAngle);
        
        // 创建阅读器容器（不旋转）
        readerContainer = new FrameLayout(this);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        
        // 根据旋转角度调整容器大小
        if (rotationAngle == 90 || rotationAngle == 270) {
            // 旋转90/270度时，交换宽高
            containerParams.width = getResources().getDisplayMetrics().heightPixels;
            containerParams.height = getResources().getDisplayMetrics().widthPixels;
            containerParams.gravity = Gravity.CENTER;
        }
        
        readerContainer.setLayoutParams(containerParams);
        readerContainer.setBackgroundColor(getBackgroundColor());
        
        if (doublePageMode) {
            // 双页模式
            LinearLayout doublePageLayout = new LinearLayout(this);
            doublePageLayout.setOrientation(LinearLayout.HORIZONTAL);
            doublePageLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            doublePageLayout.setBackgroundColor(getBackgroundColor());
            
            rightPageImageView = new ImageView(this);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            rightPageImageView.setLayoutParams(rightParams);
            rightPageImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            rightPageImageView.setBackgroundColor(getBackgroundColor());
            
            leftPageImageView = new ImageView(this);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            leftPageImageView.setLayoutParams(leftParams);
            leftPageImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            leftPageImageView.setBackgroundColor(getBackgroundColor());
            
            View.OnTouchListener doublePageTouchListener = new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        float x = event.getX();
                        float width = v.getWidth();
                        
                        if (x < width / 3) {
                            goToNextPage();
                        } else if (x > width * 2 / 3) {
                            goToPrevPage();
                        } else {
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
            readerContainer.addView(doublePageLayout);
            
        } else {
            // 单页模式
            pdfImageView = new ImageView(this);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            pdfImageView.setLayoutParams(imageParams);
            pdfImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pdfImageView.setBackgroundColor(getBackgroundColor());
            
            pdfImageView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        float x = event.getX();
                        float width = v.getWidth();
                        
                        if (x < width / 3) {
                            goToNextPage();
                        } else if (x > width * 2 / 3) {
                            goToPrevPage();
                        } else {
                            toggleControls();
                        }
                    }
                    return true;
                }
            });
            
            readerContainer.addView(pdfImageView);
        }
        
        // 设置控制元素
        setupControlElements();
        
        // 将阅读器容器添加到旋转容器
        rotateContainer.addView(readerContainer);
        
        // 将旋转容器添加到主布局
        mainLayout.addView(rotateContainer);
        
        // 显示当前页面
        displayCurrentPage();
    }
    
    private void setupControlElements() {
        // 创建顶部控制栏
        LinearLayout topBar = createSimpleTopBar();
        topBar.setId(View.generateViewId());
        
        // 创建底部页码显示
        TextView bottomPageText = new TextView(this);
        bottomPageText.setId(View.generateViewId());
        bottomPageText.setTextColor(getTextColor());
        bottomPageText.setTextSize(14);
        bottomPageText.setBackgroundColor(Color.parseColor("#80000000"));
        bottomPageText.setPadding(10, 5, 10, 5);
        bottomPageText.setGravity(Gravity.CENTER);
        
        // 创建控制按钮
        setupControlButtons();
        
        // 设置布局参数
        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        topBarParams.gravity = Gravity.TOP;
        topBar.setLayoutParams(topBarParams);
        
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        pageParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pageParams.bottomMargin = 20;
        bottomPageText.setLayoutParams(pageParams);
        
        // 按钮布局参数
        FrameLayout.LayoutParams prevParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        prevParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        prevParams.rightMargin = 20;
        prevParams.bottomMargin = 80;
        
        FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        nextParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        nextParams.leftMargin = 20;
        nextParams.bottomMargin = 80;
        
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        jumpParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        jumpParams.bottomMargin = 80;
        
        prevBtn.setLayoutParams(prevParams);
        nextBtn.setLayoutParams(nextParams);
        jumpBtn.setLayoutParams(jumpParams);
        
        // 添加所有视图到阅读器容器
        readerContainer.addView(topBar);
        readerContainer.addView(prevBtn);
        readerContainer.addView(nextBtn);
        readerContainer.addView(jumpBtn);
        readerContainer.addView(bottomPageText);
        
        // 设置页面显示
        pageTextView = bottomPageText;
    }
    
    private LinearLayout createSimpleTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(getStatusBarColor());
        topBar.setPadding(10, 10, 10, 10);
        
        // 创建按钮的辅助方法
        Button createSimpleButton(String text, View.OnClickListener listener) {
            Button button = new Button(this);
            button.setText(text);
            button.setBackgroundColor(Color.parseColor("#3700B3"));
            button.setTextColor(Color.WHITE);
            button.setOnClickListener(listener);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.rightMargin = 10;
            button.setLayoutParams(params);
            
            return button;
        }
        
        // 返回按钮
        Button backBtn = createSimpleButton("返回", v -> {
            closePdf();
            showFileList();
        });
        
        // 夜间模式按钮
        Button nightBtn = createSimpleButton(nightMode ? "日间" : "夜间", v -> toggleNightMode());
        
        // 半页模式按钮
        halfPageBtn = createSimpleButton(halfPageMode ? "整页" : "半页", v -> toggleHalfPageMode());
        
        // 双页模式按钮
        doublePageBtn = createSimpleButton(doublePageMode ? "单页" : "双页", v -> toggleDoublePageMode());
        
        // 旋转按钮
        rotateBtn = createSimpleButton("旋转 " + rotationAngle + "°", v -> rotatePage());
        
        topBar.addView(backBtn);
        topBar.addView(nightBtn);
        topBar.addView(halfPageBtn);
        topBar.addView(doublePageBtn);
        topBar.addView(rotateBtn);
        
        return topBar;
    }
    
    private void setupControlButtons() {
        // 上一页按钮
        prevBtn = new Button(this);
        prevBtn.setText("上一页");
        prevBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setOnClickListener(v -> goToPrevPage());
        
        // 下一页按钮
        nextBtn = new Button(this);
        nextBtn.setText("下一页");
        nextBtn.setBackgroundColor(Color.parseColor("#6200EE"));
        nextBtn.setTextColor(Color.WHITE);
        nextBtn.setOnClickListener(v -> goToNextPage());
        
        // 跳转按钮
        jumpBtn = new Button(this);
        jumpBtn.setText("跳转");
        jumpBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        jumpBtn.setTextColor(Color.WHITE);
        jumpBtn.setOnClickListener(v -> showJumpPageDialog());
    }
    
    private void toggleControls() {
        controlsVisible = !controlsVisible;
        
        if (readerContainer != null && readerContainer.getChildCount() > 5) {
            // 获取所有控制元素
            View topBar = readerContainer.getChildAt(0);
            View prevBtn = readerContainer.getChildAt(1);
            View nextBtn = readerContainer.getChildAt(2);
            View jumpBtn = readerContainer.getChildAt(3);
            View pageText = readerContainer.getChildAt(4);
            
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
    }
    
    private void toggleDoublePageMode() {
        doublePageMode = !doublePageMode;
        
        if (doublePageBtn != null) {
            doublePageBtn.setText(doublePageMode ? "单页" : "双页");
        }
        
        saveSettings();
        showReaderView();
    }
    
    private void rotatePage() {
        rotationAngle = (rotationAngle + 90) % 360;
        
        if (rotateBtn != null) {
            rotateBtn.setText("旋转 " + rotationAngle + "°");
        }
        
        saveSettings();
        saveReadingPosition();
        
        if (pdfRenderer != null) {
            showReaderView();
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
    
    // 反转图片黑白颜色
    private Bitmap invertColors(Bitmap bitmap) {
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
    
    // 获取当前显示的两页页码
    private int[] getDoublePageNumbers() {
        int[] pages = new int[2];
        
        if (currentPage % 2 == 0) {
            pages[0] = currentPage;
            pages[1] = currentPage + 1;
        } else {
            pages[0] = currentPage - 1;
            pages[1] = currentPage;
        }
        
        if (pages[0] < 0) pages[0] = 0;
        if (pages[1] >= totalPages) pages[1] = totalPages - 1;
        
        return pages;
    }
    
    private void displayCurrentPage() {
        if (pdfRenderer == null) return;
        
        try {
            if (doublePageMode) {
                int[] pageNumbers = getDoublePageNumbers();
                int leftPageNum = pageNumbers[0];
                int rightPageNum = pageNumbers[1];
                
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                
                int pageWidth = screenWidth / 2;
                int pageHeight = screenHeight;
                
                if (leftPageNum < totalPages && leftPageNum >= 0) {
                    PdfRenderer.Page leftPage = pdfRenderer.openPage(leftPageNum);
                    
                    int originalLeftWidth = leftPage.getWidth();
                    int originalLeftHeight = leftPage.getHeight();
                    
                    float leftScale = Math.min(
                        (float) pageWidth / originalLeftWidth,
                        (float) pageHeight / originalLeftHeight
                    );
                    
                    int scaledLeftWidth = (int) (originalLeftWidth * leftScale);
                    int scaledLeftHeight = (int) (originalLeftHeight * leftScale);
                    
                    Bitmap leftBitmap = Bitmap.createBitmap(scaledLeftWidth, scaledLeftHeight, Bitmap.Config.ARGB_8888);
                    leftPage.render(leftBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    leftPage.close();
                    
                    if (nightMode) {
                        leftBitmap = invertColors(leftBitmap);
                    }
                    
                    leftPageImageView.setImageBitmap(leftBitmap);
                } else {
                    leftPageImageView.setImageBitmap(null);
                }
                
                if (rightPageNum < totalPages && rightPageNum >= 0) {
                    PdfRenderer.Page rightPage = pdfRenderer.openPage(rightPageNum);
                    
                    int originalRightWidth = rightPage.getWidth();
                    int originalRightHeight = rightPage.getHeight();
                    
                    float rightScale = Math.min(
                        (float) pageWidth / originalRightWidth,
                        (float) pageHeight / originalRightHeight
                    );
                    
                    int scaledRightWidth = (int) (originalRightWidth * rightScale);
                    int scaledRightHeight = (int) (originalRightHeight * rightScale);
                    
                    Bitmap rightBitmap = Bitmap.createBitmap(scaledRightWidth, scaledRightHeight, Bitmap.Config.ARGB_8888);
                    rightPage.render(rightBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    rightPage.close();
                    
                    if (nightMode) {
                        rightBitmap = invertColors(rightBitmap);
                    }
                    
                    rightPageImageView.setImageBitmap(rightBitmap);
                } else {
                    rightPageImageView.setImageBitmap(null);
                }
                
                pageTextView.setText("第" + (leftPageNum + 1) + "-" + (rightPageNum + 1) + "页 / 共" + totalPages + "页");
                
            } else {
                PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
                
                int pageWidth = page.getWidth();
                int pageHeight = page.getHeight();
                
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                
                float scale = Math.min(
                    (float) screenWidth / pageWidth,
                    (float) screenHeight / pageHeight
                );
                
                int scaledWidth = (int) (pageWidth * scale);
                int scaledHeight = (int) (pageHeight * scale);
                
                Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                
                if (halfPageMode) {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    
                    if (leftPage) {
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, scaledWidth / 2, scaledHeight);
                        pageTextView.setText((currentPage + 1) + "/" + totalPages + " (左)");
                    } else {
                        bitmap = Bitmap.createBitmap(bitmap, scaledWidth / 2, 0, scaledWidth / 2, scaledHeight);
                        pageTextView.setText((currentPage + 1) + "/" + totalPages + " (右)");
                    }
                } else {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    pageTextView.setText((currentPage + 1) + "/" + totalPages);
                }
                
                page.close();
                
                if (nightMode) {
                    bitmap = invertColors(bitmap);
                }
                
                pdfImageView.setImageBitmap(bitmap);
            }
            
            saveReadingPosition();
            
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void goToPrevPage() {
        if (doublePageMode) {
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
            if (leftPageImageView != null) {
                leftPageImageView.setBackgroundColor(getBackgroundColor());
            }
            if (rightPageImageView != null) {
                rightPageImageView.setBackgroundColor(getBackgroundColor());
            }
            if (pageTextView != null) {
                pageTextView.setTextColor(getTextColor());
            }
            if (readerContainer != null && readerContainer.getChildCount() > 0) {
                LinearLayout topBar = (LinearLayout) readerContainer.getChildAt(0);
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
