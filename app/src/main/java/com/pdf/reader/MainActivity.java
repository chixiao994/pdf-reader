package com.pdf.reader;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    
    // 视图组件
    private LinearLayout mainLayout, fileListLayout, readerLayout;
    private ImageView pdfImageView;
    private TextView pageTextView, titleTextView;
    private Button nightModeBtn, halfPageBtn, prevBtn, nextBtn, openFileBtn;
    
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
    
    // 存储
    private SharedPreferences prefs;
    
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
        if (checkPermission()) {
            showFileList();
        }
    }
    
    private boolean checkPermission() {
        if (ContextCompat.checkSelfPermission(this, 
                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
            return false;
        }
        return true;
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
    
    private void showFileList() {
        mainLayout.removeAllViews();
        
        // 创建顶部栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#6200EE"));
        
        TextView title = new TextView(this);
        title.setText("PDF阅读器");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(20, 20, 20, 20);
        
        nightModeBtn = new Button(this);
        nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        nightModeBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        nightModeBtn.setTextColor(Color.WHITE);
        nightModeBtn.setOnClickListener(v -> toggleNightMode());
        
        topBar.addView(title);
        topBar.addView(nightModeBtn);
        
        // 创建文件列表区域
        fileListLayout = new LinearLayout(this);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        fileListLayout.setPadding(20, 20, 20, 20);
        
        // 扫描PDF文件
        scanPdfFiles();
        
        mainLayout.addView(topBar);
        mainLayout.addView(fileListLayout);
    }
    
    private void scanPdfFiles() {
        fileListLayout.removeAllViews();
        
        File downloadDir = new File("/storage/emulated/0/Download");
        if (downloadDir.exists()) {
            File[] files = downloadDir.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".pdf"));
            
            if (files != null && files.length > 0) {
                for (File file : files) {
                    Button fileBtn = new Button(this);
                    String fileName = file.getName();
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
                    
                    // 设置点击事件
                    String filePath = file.getAbsolutePath();
                    fileBtn.setOnClickListener(v -> openPdfFile(filePath));
                    
                    fileListLayout.addView(fileBtn);
                }
            } else {
                TextView noFilesText = new TextView(this);
                noFilesText.setText("未找到PDF文件\n请将文件放在Download文件夹");
                noFilesText.setTextSize(16);
                noFilesText.setGravity(android.view.Gravity.CENTER);
                fileListLayout.addView(noFilesText);
            }
        }
        
        // 添加选择文件按钮
        openFileBtn = new Button(this);
        openFileBtn.setText("选择其他PDF文件");
        openFileBtn.setBackgroundColor(Color.parseColor("#4CAF50"));
        openFileBtn.setTextColor(Color.WHITE);
        openFileBtn.setOnClickListener(v -> choosePdfFile());
        fileListLayout.addView(openFileBtn);
    }
    
    private void choosePdfFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        startActivityForResult(intent, 101);
    }
    
    private void openPdfFile(String filePath) {
        try {
            File file = new File(filePath);
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
            
        } catch (IOException e) {
            Toast.makeText(this, "无法打开PDF文件", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showReaderView() {
        mainLayout.removeAllViews();
        
        // 创建阅读器布局
        readerLayout = new LinearLayout(this);
        readerLayout.setOrientation(LinearLayout.VERTICAL);
        readerLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        
        // 顶部控制栏
        LinearLayout topBar = createReaderTopBar();
        
        // PDF显示区域
        pdfImageView = new ImageView(this);
        pdfImageView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f));
        pdfImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pdfImageView.setBackgroundColor(nightMode ? Color.BLACK : Color.WHITE);
        
        // 添加触摸监听
        pdfImageView.setOnClickListener(v -> toggleHalfPage());
        
        // 底部控制栏
        LinearLayout bottomBar = createReaderBottomBar();
        
        readerLayout.addView(topBar);
        readerLayout.addView(pdfImageView);
        readerLayout.addView(bottomBar);
        
        mainLayout.addView(readerLayout);
        
        // 显示当前页面
        displayCurrentPage();
    }
    
    private LinearLayout createReaderTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#6200EE"));
        topBar.setPadding(10, 10, 10, 10);
        
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
            titleTextView.setText(file.getName());
        }
        titleTextView.setTextColor(Color.WHITE);
        titleTextView.setTextSize(16);
        titleTextView.setPadding(10, 0, 10, 0);
        
        // 半边页按钮
        halfPageBtn = new Button(this);
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        halfPageBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        halfPageBtn.setTextColor(Color.WHITE);
        halfPageBtn.setOnClickListener(v -> toggleHalfPageMode());
        
        topBar.addView(backBtn);
        topBar.addView(titleTextView);
        topBar.addView(halfPageBtn);
        
        return topBar;
    }
    
    private LinearLayout createReaderBottomBar() {
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#6200EE"));
        bottomBar.setPadding(10, 10, 10, 10);
        
        // 上一页按钮
        prevBtn = new Button(this);
        prevBtn.setText("上一页");
        prevBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setOnClickListener(v -> goToPrevPage());
        
        // 页码显示
        pageTextView = new TextView(this);
        pageTextView.setTextColor(Color.WHITE);
        pageTextView.setTextSize(18);
        pageTextView.setGravity(android.view.Gravity.CENTER);
        
        // 下一页按钮
        nextBtn = new Button(this);
        nextBtn.setText("下一页");
        nextBtn.setBackgroundColor(Color.parseColor("#3700B3"));
        nextBtn.setTextColor(Color.WHITE);
        nextBtn.setOnClickListener(v -> goToNextPage());
        
        bottomBar.addView(prevBtn);
        bottomBar.addView(pageTextView);
        bottomBar.addView(nextBtn);
        
        return bottomBar;
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
        nightModeBtn.setText(nightMode ? "日间模式" : "夜间模式");
        saveSettings();
        
        // 更新背景色
        if (pdfImageView != null) {
            pdfImageView.setBackgroundColor(nightMode ? Color.BLACK : Color.WHITE);
        }
    }
    
    private void toggleHalfPageMode() {
        halfPageMode = !halfPageMode;
        halfPageBtn.setText(halfPageMode ? "整页" : "半页");
        saveSettings();
        displayCurrentPage();
    }
    
    private void toggleHalfPage() {
        if (halfPageMode) {
            leftPage = !leftPage;
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
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                openPdfFile(uri.getPath());
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
    }
}
