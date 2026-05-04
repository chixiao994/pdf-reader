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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TianLangActivity extends AppCompatActivity {

    // ==================== 天朗算法引擎（静态内部类） ====================
    public static class TianLangEngine {

        public static class CropParams implements Cloneable {
            public int bgTol = 15;
            public int lrMargin = 0;
            public int topMargin = 0;
            public int bottomMargin = 0;
            public float contrast = 1.8f;
            public int binThresh = 90;
            public float sensitivity = 0.35f;
            public String mode = "auto-single";
            public Integer manualLeftCut = null;
            public Integer manualRightCut = null;

            public CropParams clone() {
                try { return (CropParams) super.clone(); } catch (Exception e) { return new CropParams(); }
            }
        }

        public static class PageResult {
            public Bitmap resultBitmap;
            public Rect bgCropRect;
            public int leftCut = -1;
            public int rightCut = -1;
            public int bindLine = -1;
        }

        public static PageResult processPage(Bitmap original, CropParams params) {
            Bitmap workBitmap = original;
            Rect bgCrop = removeBackground(original, params);
            if (bgCrop != null && bgCrop.width() > 10 && bgCrop.height() > 10) {
                workBitmap = Bitmap.createBitmap(original, bgCrop.left, bgCrop.top, bgCrop.width(), bgCrop.height());
            } else {
                bgCrop = new Rect(0, 0, original.getWidth(), original.getHeight());
            }

            int w = workBitmap.getWidth(), h = workBitmap.getHeight();
            int[] gray = toGrayEnhanced(workBitmap, params.contrast);
            byte[] binary = binarize(gray, params.binThresh, w, h);
            float[] proj = verticalProjection(binary, w, h);

            int autoLeft = findLeftCut(proj, w, params.sensitivity);
            int autoRight = findRightCut(proj, w, params.sensitivity);

            int left = -1, right = -1, bind = -1;
            if ("manual".equals(params.mode)) {
                left = (params.manualLeftCut != null) ? params.manualLeftCut : autoLeft;
                right = (params.manualRightCut != null) ? params.manualRightCut : autoRight;
            } else if ("left-only".equals(params.mode)) {
                left = (params.manualLeftCut != null) ? params.manualLeftCut : autoLeft;
                right = w;
            } else if ("right-only".equals(params.mode)) {
                left = 0;
                right = (params.manualRightCut != null) ? params.manualRightCut : autoRight;
            } else if ("combined".equals(params.mode)) {
                left = (params.manualLeftCut != null) ? params.manualLeftCut : autoLeft;
                right = (params.manualRightCut != null) ? params.manualRightCut : autoRight;
                bind = findMiddleFrameLine(workBitmap);
                if (bind == -1) bind = w / 2;
            } else {
                left = autoLeft;
                right = autoRight;
            }

            if (left == -1) left = 0;
            if (right == -1) right = w;

            Bitmap result;
            if ("combined".equals(params.mode) && bind != -1) {
                int lw = bind - left;
                int rw = right - bind;
                int resultW = Math.max(lw, rw);
                int resultH = h * 2 + 4;
                result = Bitmap.createBitmap(resultW, resultH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                Bitmap leftHalf = Bitmap.createBitmap(workBitmap, left, 0, lw, h);
                Bitmap rightHalf = Bitmap.createBitmap(workBitmap, bind, 0, rw, h);
                canvas.drawBitmap(leftHalf, 0, 0, null);
                Paint p = new Paint();
                p.setColor(Color.LTGRAY);
                canvas.drawRect(0, h, resultW, h + 4, p);
                canvas.drawBitmap(rightHalf, 0, h + 4, null);
                leftHalf.recycle();
                rightHalf.recycle();
            } else {
                if (left >= right) { left = 0; right = w; }
                result = Bitmap.createBitmap(workBitmap, left, 0, right - left, h);
            }

            if (workBitmap != original) workBitmap.recycle();

            PageResult res = new PageResult();
            res.resultBitmap = result;
            res.bgCropRect = bgCrop;
            res.leftCut = left;
            res.rightCut = right;
            res.bindLine = bind;
            return res;
        }

        private static Rect removeBackground(Bitmap bmp, CropParams p) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);

            List<int[]> edgePixels = new ArrayList<>();
            int ew = 2;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < ew; x++) {
                    int pixel = pixels[y * w + x];
                    edgePixels.add(new int[]{Color.red(pixel), Color.green(pixel), Color.blue(pixel)});
                }
                for (int x = w - ew; x < w; x++) {
                    int pixel = pixels[y * w + x];
                    edgePixels.add(new int[]{Color.red(pixel), Color.green(pixel), Color.blue(pixel)});
                }
            }
            for (int x = ew; x < w - ew; x++) {
                for (int y = 0; y < ew; y++) {
                    int pixel = pixels[y * w + x];
                    edgePixels.add(new int[]{Color.red(pixel), Color.green(pixel), Color.blue(pixel)});
                }
                for (int y = h - ew; y < h; y++) {
                    int pixel = pixels[y * w + x];
                    edgePixels.add(new int[]{Color.red(pixel), Color.green(pixel), Color.blue(pixel)});
                }
            }
            if (edgePixels.isEmpty()) return null;

            double mr = 0, mg = 0, mb = 0;
            for (int[] ep : edgePixels) { mr += ep[0]; mg += ep[1]; mb += ep[2]; }
            int n = edgePixels.size();
            mr /= n; mg /= n; mb /= n;
            double vr = 0, vg = 0, vb = 0;
            for (int[] ep : edgePixels) {
                vr += (ep[0] - mr) * (ep[0] - mr);
                vg += (ep[1] - mg) * (ep[1] - mg);
                vb += (ep[2] - mb) * (ep[2] - mb);
            }
            double sr = Math.sqrt(vr / n), sg = Math.sqrt(vg / n), sb = Math.sqrt(vb / n);
            double maxStd = Math.max(sr, Math.max(sg, sb));
            if (maxStd < 1) maxStd = 1;
            double th = p.bgTol * maxStd;

            byte[] mask = new byte[w * h];
            for (int i = 0; i < w * h; i++) {
                int r = Color.red(pixels[i]), g = Color.green(pixels[i]), b = Color.blue(pixels[i]);
                double dr = r - mr, dg = g - mg, db = b - mb;
                double dist = Math.sqrt(dr * dr + dg * dg + db * db);
                mask[i] = (byte) (dist <= th ? 0 : 255);
            }

            mask = dilate(mask, w, h);
            mask = erode(mask, w, h);

            Rect rect = findMaxConnectedComponent(mask, w, h);
            if (rect == null || rect.width() < 10 || rect.height() < 10) return null;

            int left = Math.max(0, rect.left - p.lrMargin);
            int top = Math.max(0, rect.top - p.topMargin);
            int right = Math.min(w - 1, rect.right + p.lrMargin);
            int bottom = Math.min(h - 1, rect.bottom + p.bottomMargin);
            return new Rect(left, top, right + 1, bottom + 1);
        }

        private static byte[] dilate(byte[] src, int w, int h) {
            byte[] dst = new byte[w * h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    byte max = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            byte val = src[(y + dy) * w + (x + dx)];
                            if (val > max) max = val;
                        }
                    }
                    dst[y * w + x] = max;
                }
            }
            return dst;
        }

        private static byte[] erode(byte[] src, int w, int h) {
            byte[] dst = new byte[w * h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    byte min = (byte) 255;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            byte val = src[(y + dy) * w + (x + dx)];
                            if (val < min) min = val;
                        }
                    }
                    dst[y * w + x] = min;
                }
            }
            return dst;
        }

        private static Rect findMaxConnectedComponent(byte[] mask, int w, int h) {
            boolean[] visited = new boolean[w * h];
            Rect maxRect = null;
            int maxArea = 0;
            for (int i = 0; i < w * h; i++) {
                if ((mask[i] & 0xff) == 255 && !visited[i]) {
                    List<Integer> queue = new ArrayList<>();
                    queue.add(i);
                    visited[i] = true;
                    int minX = w, maxX = 0, minY = h, maxY = 0, area = 0;
                    while (!queue.isEmpty()) {
                        int idx = queue.remove(0);
                        area++;
                        int x = idx % w, y = idx / w;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                        int[] neighbors = {idx - 1, idx + 1, idx - w, idx + w};
                        for (int nb : neighbors) {
                            if (nb >= 0 && nb < w * h && (mask[nb] & 0xff) == 255 && !visited[nb]) {
                                visited[nb] = true;
                                queue.add(nb);
                            }
                        }
                    }
                    if (area > maxArea) {
                        maxArea = area;
                        maxRect = new Rect(minX, minY, maxX, maxY);
                    }
                }
            }
            return maxRect;
        }

        private static int[] toGrayEnhanced(Bitmap bmp, float contrast) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] gray = new int[w * h];
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < w * h; i++) {
                int r = Color.red(pixels[i]), g = Color.green(pixels[i]), b = Color.blue(pixels[i]);
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                float enhanced = (float) Math.pow(lum / 255.0, 1.0 / contrast) * 255;
                gray[i] = Math.round(Math.max(0, Math.min(255, enhanced)));
            }
            return gray;
        }

        private static byte[] binarize(int[] gray, int thresh, int w, int h) {
            byte[] bin = new byte[w * h];
            for (int i = 0; i < w * h; i++) {
                bin[i] = (gray[i] < thresh) ? 1 : 0;
            }
            return bin;
        }

        private static float[] verticalProjection(byte[] binary, int w, int h) {
            float[] proj = new float[w];
            for (int x = 0; x < w; x++) {
                int sum = 0;
                for (int y = 0; y < h; y++) sum += binary[y * w + x];
                proj[x] = (float) sum / h;
            }
            return proj;
        }

        private static int findLeftCut(float[] proj, int w, float sensitivity) {
            int start = -1;
            for (int x = 0; x < w * 0.4; x++) {
                if (proj[x] > sensitivity) { start = x; break; }
            }
            if (start == -1) return -1;
            int peak = start;
            for (int x = start; x < w * 0.5; x++) if (proj[x] > proj[peak]) peak = x;
            for (int x = peak; x < w * 0.5; x++) if (proj[x] < sensitivity * 0.5) return x;
            return peak + 5;
        }

        private static int findRightCut(float[] proj, int w, float sensitivity) {
            int start = -1;
            for (int x = w - 1; x > w * 0.6; x--) {
                if (proj[x] > sensitivity) { start = x; break; }
            }
            if (start == -1) return -1;
            int peak = start;
            for (int x = start; x > w * 0.4; x--) if (proj[x] > proj[peak]) peak = x;
            for (int x = peak; x > w * 0.4; x--) if (proj[x] < sensitivity * 0.5) return x;
            return peak - 5;
        }

        private static int findMiddleFrameLine(Bitmap bmp) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] gray = toGrayEnhanced(bmp, 1.0f);
            int[] edge = new int[w * h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int idx = y * w + x;
                    edge[idx] = Math.abs(gray[idx + 1] - gray[idx - 1]);
                }
            }
            int startX = (int) (w * 0.35), endX = (int) (w * 0.65);
            float[] colEdge = new float[w];
            for (int x = startX; x < endX; x++) {
                int sum = 0;
                for (int y = 0; y < h; y++) sum += edge[y * w + x];
                colEdge[x] = (float) sum / h;
            }
            int bestX = -1;
            float bestVal = 0;
            for (int x = startX + 2; x < endX - 2; x++) {
                if (colEdge[x] > colEdge[x - 1] && colEdge[x] > colEdge[x + 1] && colEdge[x] > bestVal) {
                    bestVal = colEdge[x];
                    bestX = x;
                }
            }
            return bestX;
        }
    }

    // ==================== 界面与交互 ====================
    private TianLangEngine.CropParams cropParams = new TianLangEngine.CropParams();
    private Map<Integer, TianLangEngine.CropParams> pageParams = new HashMap<>();
    private Bitmap currentOriginalBitmap;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int currentPage = 0;
    private int totalPages = 0;

    private ImageView originalView, resultView;
    private RadioGroup modeGroup;
    private RadioButton rbAutoSingle, rbLeftOnly, rbRightOnly, rbCombined, rbManual;
    private TextView pageIndicator;

    // 滑块控件
    private SeekBar bgTolSeek, lrMarginSeek, topMarginSeek, bottomMarginSeek;
    private SeekBar contrastSeek, threshSeek, sensSeek;

    private static final int PICK_IMAGE = 1;
    private static final int PICK_PDF = 2;

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

        // 滑块区域 - 每个滑块都通过回调直接修改 cropParams 并保存引用
        bgTolSeek = addSlider(root, "背景容差", 0, 50, 15, val -> cropParams.bgTol = val);
        lrMarginSeek = addSlider(root, "左右边距", -50, 50, 0, val -> cropParams.lrMargin = val);
        topMarginSeek = addSlider(root, "上边距", -50, 50, 0, val -> cropParams.topMargin = val);
        bottomMarginSeek = addSlider(root, "下边距", -50, 50, 0, val -> cropParams.bottomMargin = val);
        contrastSeek = addSlider(root, "对比度", 5, 30, 18, val -> cropParams.contrast = val / 10f);
        threshSeek = addSlider(root, "二值化阈值", 40, 200, 90, val -> cropParams.binThresh = val);
        sensSeek = addSlider(root, "堆叠敏感度", 10, 90, 35, val -> cropParams.sensitivity = val / 100f);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        Button processBtn = new Button(this); processBtn.setText("执行裁切");
        processBtn.setOnClickListener(v -> processCurrent());
        Button resetBtn = new Button(this); resetBtn.setText("重置");
        resetBtn.setOnClickListener(v -> {
            cropParams = new TianLangEngine.CropParams();
            updateUIFromParams();
            processCurrent();
        });
        Button saveBtn = new Button(this); saveBtn.setText("保存当前");
        saveBtn.setOnClickListener(v -> saveCurrentResult());
        Button exportBtn = new Button(this); exportBtn.setText("生成裁切PDF");
        exportBtn.setOnClickListener(v -> exportCroppedPdf());
        btnRow.addView(processBtn); btnRow.addView(resetBtn);
        btnRow.addView(saveBtn); btnRow.addView(exportBtn);
        root.addView(btnRow);

        // 原图与结果
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

    // 构建滑块并返回 SeekBar 引用
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
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
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
        else if (id == rbManual.getId()) cropParams.mode = "manual";
    }

    private void updateUIFromParams() {
        bgTolSeek.setProgress(cropParams.bgTol);
        lrMarginSeek.setProgress(cropParams.lrMargin + 50);
        topMarginSeek.setProgress(cropParams.topMargin + 50);
        bottomMarginSeek.setProgress(cropParams.bottomMargin + 50);
        contrastSeek.setProgress((int)(cropParams.contrast * 10) - 5);
        threshSeek.setProgress(cropParams.binThresh - 40);
        sensSeek.setProgress((int)(cropParams.sensitivity * 100) - 10);
        switch (cropParams.mode) {
            case "auto-single": rbAutoSingle.setChecked(true); break;
            case "left-only": rbLeftOnly.setChecked(true); break;
            case "right-only": rbRightOnly.setChecked(true); break;
            case "combined": rbCombined.setChecked(true); break;
            case "manual": rbManual.setChecked(true); break;
        }
    }

    private void processCurrent() {
        if (currentOriginalBitmap == null) return;
        TianLangEngine.PageResult res = TianLangEngine.processPage(currentOriginalBitmap, cropParams);
        Bitmap annotated = drawAnnotations(currentOriginalBitmap, res);
        originalView.setImageBitmap(annotated);
        resultView.setImageBitmap(res.resultBitmap);
    }

    private Bitmap drawAnnotations(Bitmap original, TianLangEngine.PageResult res) {
        Bitmap work = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(work);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(4);
        if (res.bgCropRect != null && !res.bgCropRect.equals(new Rect(0,0,original.getWidth(),original.getHeight()))) {
            paint.setColor(Color.GREEN); paint.setStyle(Paint.Style.STROKE);
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
        } catch (IOException e) { Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show(); }
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
        } catch (IOException e) { Toast.makeText(this, "无法打开PDF", Toast.LENGTH_SHORT).show(); }
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
        } catch (Exception e) { Toast.makeText(this, "渲染失败", Toast.LENGTH_SHORT).show(); }
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
                        TianLangEngine.CropParams p = pageParams.containsKey(i) ? pageParams.get(i).clone() : cropParams.clone();
                        TianLangEngine.PageResult res = TianLangEngine.processPage(orig, p);
                        Bitmap result = res.resultBitmap;
                        android.graphics.pdf.PdfDocument.PageInfo info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(result.getWidth(), result.getHeight(), i).create();
                        android.graphics.pdf.PdfDocument.Page outPage = out.startPage(info);
                        outPage.getCanvas().drawBitmap(result, 0, 0, null);
                        out.finishPage(outPage);
                        orig.recycle(); result.recycle();
                    }
                    File outFile = new File(getExternalFilesDir(null), "tianlang_export_" + System.currentTimeMillis() + ".pdf");
                    out.writeTo(new FileOutputStream(outFile));
                    out.close();
                    return outFile.getAbsolutePath();
                } catch (IOException e) { return null; }
            }
            @Override
            protected void onPostExecute(String path) {
                if (path != null) Toast.makeText(TianLangActivity.this, "导出成功: " + path, Toast.LENGTH_LONG).show();
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
