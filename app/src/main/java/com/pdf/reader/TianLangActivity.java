package com.pdf.reader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TianLangActivity extends AppCompatActivity {

    private TianLangEngine.CropParams cropParams = new TianLangEngine.CropParams();
    private final Map<Integer, TianLangEngine.CropParams> pageParams = new HashMap<>();
    private Bitmap currentOriginalBitmap;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int currentPage = 0, totalPages = 0;

    private ImageView originalView, resultView;
    private RadioGroup modeGroup;
    private RadioButton rbAutoSingle, rbLeftOnly, rbRightOnly, rbCombined, rbManual;
    private TextView pageIndicator;

    private SeekBar bgTolSeek, lrMarginSeek, topMarginSeek, bottomMarginSeek;
    private SeekBar contrastSeek, threshSeek, sensSeek;

    private static final int PICK_IMAGE = 1, PICK_PDF = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    private void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 30);

        // 文件选择
        LinearLayout fileRow = new LinearLayout(this);
        Button btnImage = new Button(this); btnImage.setText("上传图片");
        btnImage.setOnClickListener(v -> pickFile(PICK_IMAGE));
        Button btnPdf = new Button(this); btnPdf.setText("上传PDF");
        btnPdf.setOnClickListener(v -> pickFile(PICK_PDF));
        fileRow.addView(btnImage); fileRow.addView(btnPdf);
        root.addView(fileRow);

        // 翻页
        LinearLayout navRow = new LinearLayout(this);
        Button prevBtn = new Button(this); prevBtn.setText("上一页");
        pageIndicator = new TextView(this); pageIndicator.setText("0/0");
        pageIndicator.setGravity(Gravity.CENTER);
        pageIndicator.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button nextBtn = new Button(this); nextBtn.setText("下一页");
        prevBtn.setOnClickListener(v -> changePage(-1));
        nextBtn.setOnClickListener(v -> changePage(1));
        navRow.addView(prevBtn); navRow.addView(pageIndicator); navRow.addView(nextBtn);
        root.addView(navRow);

        // 策略
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        rbAutoSingle = new RadioButton(this); rbAutoSingle.setText("自动单侧");
        rbLeftOnly = new RadioButton(this); rbLeftOnly.setText("指定左");
        rbRightOnly = new RadioButton(this); rbRightOnly.setText("指定右");
        rbCombined = new RadioButton(this); rbCombined.setText("合拟面");
        rbManual = new RadioButton(this); rbManual.setText("手动");
        modeGroup.addView(rbAutoSingle); modeGroup.addView(rbLeftOnly);
        modeGroup.addView(rbRightOnly); modeGroup.addView(rbCombined);
        modeGroup.addView(rbManual);
        rbAutoSingle.setChecked(true);
        modeGroup.setOnCheckedChangeListener((group, id) -> {
            updateModeFromRadio();
            processCurrent();
        });
        root.addView(modeGroup);

        // 滑块
        bgTolSeek = addSlider(root, "背景容差", 0, 50, 15, val -> cropParams.bgTol = val);
        lrMarginSeek = addSlider(root, "左右边距", -50, 50, 0, val -> cropParams.lrMargin = val);
        topMarginSeek = addSlider(root, "上边距", -50, 50, 0, val -> cropParams.topMargin = val);
        bottomMarginSeek = addSlider(root, "下边距", -50, 50, 0, val -> cropParams.bottomMargin = val);
        contrastSeek = addSlider(root, "对比度", 5, 30, 18, val -> cropParams.contrast = val / 10f);
        threshSeek = addSlider(root, "二值化阈值", 40, 200, 90, val -> cropParams.binThresh = val);
        sensSeek = addSlider(root, "堆叠敏感度", 10, 90, 35, val -> cropParams.sensitivity = val / 100f);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        Button processBtn = new Button(this); processBtn.setText("执行裁切"); processBtn.setOnClickListener(v -> processCurrent());
        Button resetBtn = new Button(this); resetBtn.setText("重置"); resetBtn.setOnClickListener(v -> { cropParams = new TianLangEngine.CropParams(); updateUIFromParams(); processCurrent(); });
        Button saveBtn = new Button(this); saveBtn.setText("保存当前"); saveBtn.setOnClickListener(v -> saveCurrentResult());
        Button exportBtn = new Button(this); exportBtn.setText("生成裁切PDF"); exportBtn.setOnClickListener(v -> exportCroppedPdf());
        btnRow.addView(processBtn); btnRow.addView(resetBtn); btnRow.addView(saveBtn); btnRow.addView(exportBtn);
        root.addView(btnRow);

        originalView = new ImageView(this);
        originalView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        originalView.setBackgroundColor(Color.WHITE);
        originalView.setMinimumHeight(400);
        root.addView(newLabel("📜 原拟面 (带标注)"));
        root.addView(originalView);

        resultView = new ImageView(this);
        resultView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultView.setBackgroundColor(Color.WHITE);
        resultView.setAdjustViewBounds(true);
        resultView.setMinimumHeight(400);
        root.addView(newLabel("🌤️ 天朗半拟面"));
        root.addView(resultView);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private TextView newLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setPadding(0, 10, 0, 5);
        return tv;
    }

    private SeekBar addSlider(LinearLayout parent, String label, int min, int max, int def,
                              SliderCallback callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);
        TextView tv = new TextView(this);
        tv.setText(label + ": " + def);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(def - min);
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + min;
                tv.setText(label + ": " + value);
                callback.onChange(value);
                if (fromUser) processCurrent();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(tv);
        row.addView(seekBar);
        parent.addView(row);
        return seekBar;
    }

    interface SliderCallback { void onChange(int value); }

    private void updateModeFromRadio() {
        int id = modeGroup.getCheckedRadioButtonId();
        if (id == rbAutoSingle.getId()) cropParams.mode = "auto-single";
        else if (id == rbLeftOnly.getId()) cropParams.mode = "left-only";
        else if (id == rbRightOnly.getId()) cropParams.mode = "right-only";
        else if (id == rbCombined.getId()) cropParams.mode = "combined";
        else cropParams.mode = "manual";
    }

    private void updateUIFromParams() {
        if (bgTolSeek != null) bgTolSeek.setProgress(cropParams.bgTol);
        if (lrMarginSeek != null) lrMarginSeek.setProgress(cropParams.lrMargin + 50);
        if (topMarginSeek != null) topMarginSeek.setProgress(cropParams.topMargin + 50);
        if (bottomMarginSeek != null) bottomMarginSeek.setProgress(cropParams.bottomMargin + 50);
        if (contrastSeek != null) contrastSeek.setProgress(Math.round(cropParams.contrast * 10) - 5);
        if (threshSeek != null) threshSeek.setProgress(cropParams.binThresh - 40);
        if (sensSeek != null) sensSeek.setProgress(Math.round(cropParams.sensitivity * 100) - 10);

        String mode = cropParams.mode != null ? cropParams.mode : "auto-single";
        switch (mode) {
            case "auto-single": rbAutoSingle.setChecked(true); break;
            case "left-only": rbLeftOnly.setChecked(true); break;
            case "right-only": rbRightOnly.setChecked(true); break;
            case "combined": rbCombined.setChecked(true); break;
            default: rbManual.setChecked(true); break;
        }
    }

    private void processCurrent() {
        if (currentOriginalBitmap == null) return;
        try {
            TianLangEngine.PageResult res = TianLangEngine.processPage(currentOriginalBitmap, cropParams);
            Bitmap annotated = drawAnnotations(currentOriginalBitmap, res);
            originalView.setImageBitmap(annotated);
            resultView.setImageBitmap(res.resultBitmap);
        } catch (Exception e) {
            Toast.makeText(this, "算法处理出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap drawAnnotations(Bitmap original, TianLangEngine.PageResult res) {
        Bitmap work = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(work);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(4);
        if (res.bgCropRect != null && !res.bgCropRect.equals(new Rect(0,0,original.getWidth(),original.getHeight()))) {
            paint.setColor(Color.GREEN);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(res.bgCropRect, paint);
        }
        if (res.leftCut >= 0) { paint.setColor(Color.RED); canvas.drawLine(res.leftCut, 0, res.leftCut, work.getHeight(), paint); }
        if (res.rightCut >= 0) { paint.setColor(Color.RED); canvas.drawLine(res.rightCut, 0, res.rightCut, work.getHeight(), paint); }
        if (res.bindLine >= 0) { paint.setColor(Color.BLUE); canvas.drawLine(res.bindLine, 0, res.bindLine, work.getHeight(), paint); }
        return work;
    }

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        if (requestCode == PICK_IMAGE) intent.setType("image/*");
        else intent.setType("application/pdf");
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == PICK_IMAGE) loadImage(uri);
            else if (requestCode == PICK_PDF) loadPdf(uri);
        }
    }

    private void loadImage(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) { Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show(); return; }
            currentOriginalBitmap = bmp;
            totalPages = 1; currentPage = 0;
            pdfRenderer = null;
            updatePageInfo();
            processCurrent();
        } catch (IOException e) { Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void loadPdf(Uri uri) {
        try {
            File tempFile = new File(getCacheDir(), "tianlang_temp.pdf");
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            }
            if (pdfRenderer != null) pdfRenderer.close();
            if (fileDescriptor != null) fileDescriptor.close();
            fileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            totalPages = pdfRenderer.getPageCount();
            currentPage = 0;
            renderPage(0);
            updatePageInfo();
        } catch (IOException e) { Toast.makeText(this, "无法打开PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void renderPage(int index) {
        if (pdfRenderer == null || index < 0 || index >= totalPages) return;
        try {
            PdfRenderer.Page page = pdfRenderer.openPage(index);
            int w = page.getWidth(), h = page.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            currentOriginalBitmap = bmp;
            if (pageParams.containsKey(index)) {
                cropParams = pageParams.get(index).clone();
                updateUIFromParams();
            }
            processCurrent();
        } catch (Exception e) { Toast.makeText(this, "渲染失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void changePage(int delta) {
        if (pdfRenderer == null) return;
        int newPage = currentPage + delta;
        if (newPage >= 0 && newPage < totalPages) {
            pageParams.put(currentPage, cropParams.clone());
            currentPage = newPage;
            renderPage(currentPage);
            updatePageInfo();
        }
    }

    private void updatePageInfo() {
        if (pdfRenderer != null) pageIndicator.setText((currentPage + 1) + "/" + totalPages);
        else pageIndicator.setText("图片");
    }

    private void saveCurrentResult() {
        if (resultView.getDrawable() == null) { Toast.makeText(this, "无结果", Toast.LENGTH_SHORT).show(); return; }
        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resultView.getDrawable()).getBitmap();
        try {
            File outFile = new File(getExternalFilesDir(null), "tianlang_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(outFile)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, fos); }
            Toast.makeText(this, "已保存: " + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); }
    }

    private void exportCroppedPdf() {
        if (pdfRenderer == null || totalPages == 0) { Toast.makeText(this, "请先打开PDF", Toast.LENGTH_SHORT).show(); return; }
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    android.graphics.pdf.PdfDocument out = new android.graphics.pdf.PdfDocument();
                    for (int i = 0; i < totalPages; i++) {
                        PdfRenderer.Page page = pdfRenderer.openPage(i);
                        Bitmap orig = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                        page.render(orig, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                        TianLangEngine.CropParams p = pageParams.containsKey(i) ?
                                pageParams.get(i).clone() : cropParams.clone();
                        TianLangEngine.PageResult res = TianLangEngine.processPage(orig, p);
                        Bitmap result = res.resultBitmap;
                        android.graphics.pdf.PdfDocument.PageInfo info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                                result.getWidth(), result.getHeight(), i).create();
                        android.graphics.pdf.PdfDocument.Page outPage = out.startPage(info);
                        outPage.getCanvas().drawBitmap(result, 0, 0, null);
                        out.finishPage(outPage);
                        orig.recycle();
                        result.recycle();
                    }
                    File outFile = new File(getExternalFilesDir(null),
                            "tianlang_export_" + System.currentTimeMillis() + ".pdf");
                    out.writeTo(new FileOutputStream(outFile));
                    out.close();
                    return outFile.getAbsolutePath();
                } catch (IOException e) { return null; }
            }
            @Override
            protected void onPostExecute(String path) {
                if (path != null) Toast.makeText(TianLangActivity.this, "PDF已保存: " + path, Toast.LENGTH_LONG).show();
                else Toast.makeText(TianLangActivity.this, "导出失败", Toast.LENGTH_SHORT).show();
            }
        }.execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pdfRenderer != null) pdfRenderer.close();
        if (fileDescriptor != null) try { fileDescriptor.close(); } catch (IOException ignored) {}
    }
}
