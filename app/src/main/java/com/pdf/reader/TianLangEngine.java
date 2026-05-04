package com.pdf.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class TianLangEngine {

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

        @Override
        public CropParams clone() {
            try {
                return (CropParams) super.clone();
            } catch (CloneNotSupportedException e) {
                // 不会执行，因为实现了 Cloneable
                return new CropParams();
            }
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
        // 1. 背景切除
        Bitmap workBitmap = original;
        Rect bgCrop = removeBackground(original, params);
        if (bgCrop != null && bgCrop.width() > 10 && bgCrop.height() > 10) {
            workBitmap = Bitmap.createBitmap(original, bgCrop.left, bgCrop.top, bgCrop.width(), bgCrop.height());
        } else {
            bgCrop = new Rect(0, 0, original.getWidth(), original.getHeight());
        }

        int w = workBitmap.getWidth();
        int h = workBitmap.getHeight();
        int[] gray = toGrayEnhanced(workBitmap, params.contrast);
        byte[] binary = binarize(gray, params.binThresh, w, h);
        float[] proj = verticalProjection(binary, w, h);

        int autoLeft = findLeftCut(proj, w, params.sensitivity);
        int autoRight = findRightCut(proj, w, params.sensitivity);

        int left = -1, right = -1, bind = -1;
        switch (params.mode) {
            case "manual":
                left  = (params.manualLeftCut  != null) ? params.manualLeftCut  : autoLeft;
                right = (params.manualRightCut != null) ? params.manualRightCut : autoRight;
                break;
            case "left-only":
                left = (params.manualLeftCut != null) ? params.manualLeftCut : autoLeft;
                right = w;
                break;
            case "right-only":
                left = 0;
                right = (params.manualRightCut != null) ? params.manualRightCut : autoRight;
                break;
            case "combined":
                left  = (params.manualLeftCut  != null) ? params.manualLeftCut  : autoLeft;
                right = (params.manualRightCut != null) ? params.manualRightCut : autoRight;
                bind = findMiddleFrameLine(workBitmap);
                if (bind == -1) bind = w / 2;
                break;
            default: // auto-single
                left = autoLeft;
                right = autoRight;
                break;
        }

        // 边界保护
        if (left == -1) left = 0;
        if (right == -1) right = w;
        if (left >= right) { left = 0; right = w; }

        Bitmap result;
        if ("combined".equals(params.mode) && bind != -1 && bind > left && bind < right) {
            int lw = bind - left;
            int rw = right - bind;
            int resultW = Math.max(lw, rw);
            int resultH = h * 2 + 4;
            result = Bitmap.createBitmap(resultW, resultH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            Bitmap leftHalf = Bitmap.createBitmap(workBitmap, left, 0, lw, h);
            Bitmap rightHalf = Bitmap.createBitmap(workBitmap, bind, 0, rw, h);
            canvas.drawBitmap(leftHalf, 0, 0, null);
            Paint separator = new Paint();
            separator.setColor(Color.LTGRAY);
            canvas.drawRect(0, h, resultW, h + 4, separator);
            canvas.drawBitmap(rightHalf, 0, h + 4, null);
            leftHalf.recycle();
            rightHalf.recycle();
        } else {
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

    // ---------- 背景切除 ----------
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

        int n = edgePixels.size();
        double mr = 0, mg = 0, mb = 0;
        for (int[] ep : edgePixels) { mr += ep[0]; mg += ep[1]; mb += ep[2]; }
        mr /= n; mg /= n; mb /= n;
        double vr = 0, vg = 0, vb = 0;
        for (int[] ep : edgePixels) {
            vr += Math.pow(ep[0] - mr, 2);
            vg += Math.pow(ep[1] - mg, 2);
            vb += Math.pow(ep[2] - mb, 2);
        }
        double sr = Math.sqrt(vr / n), sg = Math.sqrt(vg / n), sb = Math.sqrt(vb / n);
        double maxStd = Math.max(sr, Math.max(sg, sb));
        if (maxStd < 1) maxStd = 1;
        double tol = p.bgTol * maxStd;

        byte[] mask = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            int r = Color.red(pixels[i]), g = Color.green(pixels[i]), b = Color.blue(pixels[i]);
            double dr = r - mr, dg = g - mg, db = b - mb;
            double dist = Math.sqrt(dr*dr + dg*dg + db*db);
            mask[i] = (dist <= tol) ? 0 : (byte) 255;
        }

        mask = dilate(mask, w, h);
        mask = erode(mask, w, h);

        Rect maxRect = findMaxConnectedComponent(mask, w, h);
        if (maxRect == null || maxRect.width() < 10 || maxRect.height() < 10) return null;

        int left = Math.max(0, maxRect.left - p.lrMargin);
        int top = Math.max(0, maxRect.top - p.topMargin);
        int right = Math.min(w - 1, maxRect.right + p.lrMargin);
        int bottom = Math.min(h - 1, maxRect.bottom + p.bottomMargin);
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
                Deque<Integer> stack = new ArrayDeque<>();
                stack.push(i);
                visited[i] = true;
                int minX = w, maxX = 0, minY = h, maxY = 0, area = 0;
                while (!stack.isEmpty()) {
                    int idx = stack.pop();
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
                            stack.push(nb);
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

    // ---------- 图像处理基础方法 ----------
    private static int[] toGrayEnhanced(Bitmap bmp, float contrast) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] gray = new int[w * h];
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
        for (int x = start; x < w * 0.5; x++) {
            if (proj[x] > proj[peak]) peak = x;
        }
        for (int x = peak; x < w * 0.5; x++) {
            if (proj[x] < sensitivity * 0.5) return x;
        }
        return peak + 5;
    }

    private static int findRightCut(float[] proj, int w, float sensitivity) {
        int start = -1;
        for (int x = w - 1; x > w * 0.6; x--) {
            if (proj[x] > sensitivity) { start = x; break; }
        }
        if (start == -1) return -1;
        int peak = start;
        for (int x = start; x > w * 0.4; x--) {
            if (proj[x] > proj[peak]) peak = x;
        }
        for (int x = peak; x > w * 0.4; x--) {
            if (proj[x] < sensitivity * 0.5) return x;
        }
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
