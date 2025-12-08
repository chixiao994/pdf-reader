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
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    
    // 视图组件
    private LinearLayout mainLayout, fileListLayout;
    private FrameLayout readerContainer;
    private ImageView pdfImageView;
    private TextView pageTextView, titleTextView;
    private Button nightModeBtn, halfPageBtn, prevBtn, nextBtn, openFileBtn, refreshBtn;
    
    // PDF相关
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int currentPage = 0;
    private int totalPages = 0;
    private String currentFilePath;
    
    // 设置
    private boolean nightMode = false;
    private boolean halfPageMode = false;
    private boolean leftPage = false;
    private boolean controlsVisible = true; // 控制栏是否可见
    
    // 存储
    private SharedPreferences prefs;
    
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;
    
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
    }
    
    private void saveSettings() {
        prefs.edit()
            .putBoolean("night_mode", nightMode)
            .putBoolean("half_page", halfPageMode)
            .apply();
    }
    
    private void saveReadingPosition() {
        if (currentFilePath != null) {
            prefs.edit()
                .putInt(currentFilePath + "_page", currentPage)
                .apply();
        }
    }
    
    private int getReadingPosition(String filePath) {
        return prefs.getInt(filePath + "_page", 0);
    }
    
    private void createMainLayout() {
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        setContentView(mainLayout);
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
        noPermissionText.setTextColor(Color.BLACK);
        noPermissionText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noPermissionText);
        
        // 添加选择文件按钮
        openFileBtn = new Button(this);
        openFileBtn.setText("选择PDF文件");
        openFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        openFileBtn.setTextColor(Color.WHITE);
        openFileBtn.setOnClickListener(v -> choosePdfFile());
        fileListLayout.addView(openFileBtn);
        
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
        
        // 扫描PDF文件
        scanPdfFiles();
        
        mainLayout.addView(topBar);
        mainLayout.addView(fileListLayout);
    }
    
    private LinearLayout createTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#6200EE"));
        topBar.setPadding(20, 20, 20, 20);
        
        TextView title = new TextView(this);
        title.setText("PDF阅读器");
        title.setTextColor(Color.WHITE);
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
        
        addFileChooserButton();
    }
    
    private void addFileButton(File file) {
        Button fileBtn = new Button(this);
        String fileName = file.getName();
        
        // 限制文件名长度
        if (fileName.length() > 30) {
            fileName = fileName.substring(0, 27) + "...";
        }
        
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
                           "或者点击下方按钮选择文件");
        noFilesText.setTextSize(16);
        noFilesText.setGravity(android.view.Gravity.CENTER);
        noFilesText.setTextColor(Color.BLACK);
        noFilesText.setPadding(0, 50, 0, 50);
        fileListLayout.addView(noFilesText);
    }
    
    private void addFileChooserButton() {
        openFileBtn = new Button(this);
        openFileBtn.setText("选择其他PDF文件");
        openFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        openFileBtn.setTextColor(Color.WHITE);
        openFileBtn.setPadding(20, 30, 20, 30);
        openFileBtn.setOnClickListener(v -> choosePdfFile());
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 20;
        openFileBtn.setLayoutParams(params);
        
        fileListLayout.addView(openFileBtn);
    }
    
    private void choosePdfFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
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
            
            // 恢复阅读位置
            currentPage = getReadingPosition(filePath);
            if (currentPage >= totalPages) {
                currentPage = totalPages - 1;
            }
            
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
            
            // 创建临时文件名
            String tempFileName = "temp_pdf_" + System.currentTimeMillis() + ".pdf";
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
                            File externalDir = Environment.getExternalStorageDirectory();
                            if (externalDir != null && externalDir.getParent() != null) {
                                filePath = externalDir.getParent() + "/" + type + "/" + id;
                            }
                        }
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
            
        } catch (Exception e) {
            e.printStackTrace();
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
        
        // PDF显示区域 - 添加触摸监听用于翻页和隐藏控制栏
        pdfImageView = new ImageView(this);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        pdfImageView.setLayoutParams(imageParams);
        pdfImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pdfImageView.setBackgroundColor(nightMode ? Color.BLACK : Color.WHITE);
        
        // 添加触摸监听器
        pdfImageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    float x = event.getX();
                    float width = v.getWidth();
                    
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
                return true;
            }
        });
        
        // 创建顶部控制栏
        LinearLayout topBar = createReaderTopBar();
        topBar.setId(View.generateViewId());
        
        // 创建底部页码显示
        TextView bottomPageText = new TextView(this);
        bottomPageText.setId(View.generateViewId());
        bottomPageText.setTextColor(Color.WHITE);
        bottomPageText.setTextSize(14);
        bottomPageText.setBackgroundColor(Color.parseColor("#80000000")); // 半透明背景
        bottomPageText.setPadding(10, 5, 10, 5);
        bottomPageText.setGravity(Gravity.CENTER);
        
        // 上一页按钮 (右下角)
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
        
        // 底部页码显示布局参数
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        pageParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pageParams.bottomMargin = 20;
        bottomPageText.setLayoutParams(pageParams);
        
        // 添加所有视图到容器
        readerContainer.addView(pdfImageView);
        readerContainer.addView(topBar);
        readerContainer.addView(prevBtn);
        readerContainer.addView(nextBtn);
        readerContainer.addView(bottomPageText);
        
        // 设置页面显示
        pageTextView = bottomPageText;
        
        mainLayout.addView(readerContainer);
        
        // 显示当前页面
        displayCurrentPage();
    }
    
    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#CC6200EE")); // 半透明背景
        topBar.setPadding(10, 10, 10, 10);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        topBar.setLayoutParams(params);
        
        // 返回按钮
        Button backBtn = new Button(this);
        backBtn.setText("返回");
        backBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        backBtn.setTextColor(Color.WHITE);
        backBtn.setOnClickListener(v -> {
            closePdf();
            showFileList();
        });
        
        // 标题
        titleTextView = new TextView(this);
        if (currentFilePath != null) {
            File file = new File(currentFilePath);
            String fileName = file.getName();
            if (fileName.length() > 25) {
                fileName = fileName.substring(0, 22) + "...";
            }
            titleTextView.setText(fileName);
        }
        titleTextView.setTextColor(Color.WHITE);
        titleTextView.setTextSize(16);
        titleTextView.setPadding(10, 0, 10, 0);
        titleTextView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        
        // 夜间模式按钮
        Button nightBtn = new Button(this);
        nightBtn.setText(nightMode ? "日间" : "夜间");
        nightBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        nightBtn.setTextColor(Color.WHITE);
        nightBtn.setOnClickListener(v -> toggleNightMode());
        
        // 半页模式按钮
        halfPageBtn = new Button(this);
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        halfPageBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        halfPageBtn.setTextColor(Color.WHITE);
        halfPageBtn.setOnClickListener(v -> toggleHalfPageMode());
        
        topBar.addView(backBtn);
        topBar.addView(titleTextView);
        topBar.addView(nightBtn);
        topBar.addView(halfPageBtn);
        
        return topBar;
    }
    
    private void toggleControls() {
        controlsVisible = !controlsVisible;
        
        // 获取所有控制元素
        View topBar = readerContainer.findViewById(readerContainer.getChildAt(1).getId());
        View prevBtn = readerContainer.getChildAt(2);
        View nextBtn = readerContainer.getChildAt(3);
        View pageText = readerContainer.getChildAt(4);
        
        if (controlsVisible) {
            // 显示控制元素
            topBar.setVisibility(View.VISIBLE);
            prevBtn.setVisibility(View.VISIBLE);
            nextBtn.setVisibility(View.VISIBLE);
            pageText.setVisibility(View.VISIBLE);
        } else {
            // 隐藏控制元素
            topBar.setVisibility(View.GONE);
            prevBtn.setVisibility(View.GONE);
            nextBtn.setVisibility(View.GONE);
            pageText.setVisibility(View.GONE);
        }
    }
    
    private void displayCurrentPage() {
        if (pdfRenderer == null) return;
        
        try {
            PdfRenderer.Page page = pdfRenderer.openPage(currentPage);
            
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            
            if (halfPageMode) {
                // 半边页模式
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                
                // 裁剪半边
                if (leftPage) {
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, width / 2, height);
                    pageTextView.setText((currentPage + 1) + "/" + totalPages + " (左)");
                } else {
                    bitmap = Bitmap.createBitmap(bitmap, width / 2, 0, width / 2, height);
                    pageTextView.setText((currentPage + 1) + "/" + totalPages + " (右)");
                }
            } else {
                // 整页模式
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pageTextView.setText((currentPage + 1) + "/" + totalPages);
            }
            
            pdfImageView.setImageBitmap(bitmap);
            page.close();
            
            // 保存阅读位置
            saveReadingPosition();
            
        } catch (Exception e) {
            Toast.makeText(this, "显示页面失败", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    
    private void goToPrevPage() {
        if (halfPageMode) {
            if (leftPage) {
                // 当前是左半页，切换到上一页的右半页
                if (currentPage > 0) {
                    currentPage--;
                    leftPage = false;
                }
            } else {
                // 当前是右半页，切换到左半页
                leftPage = true;
            }
        } else {
            if (currentPage > 0) {
                currentPage--;
            }
        }
        displayCurrentPage();
    }
    
    private void goToNextPage() {
        if (halfPageMode) {
            if (leftPage) {
                // 当前是左半页，切换到右半页
                leftPage = false;
            } else {
                // 当前是右半页，切换到下一页的左半页
                if (currentPage < totalPages - 1) {
                    currentPage++;
                    leftPage = true;
                }
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
        saveSettings();
        
        // 更新背景色
        if (pdfImageView != null) {
            pdfImageView.setBackgroundColor(nightMode ? Color.BLACK : Color.WHITE);
        }
        displayCurrentPage();
    }
    
    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
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
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // 调试信息
                Log.d("PDF_DEBUG", "URI Scheme: " + uri.getScheme());
                Log.d("PDF_DEBUG", "URI Path: " + uri.getPath());
                
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
