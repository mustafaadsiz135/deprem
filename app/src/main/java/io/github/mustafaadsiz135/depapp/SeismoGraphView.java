package io.github.mustafaadsiz135.depapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Sismograf benzeri canlı ivme grafiği (X, Y, Z eksenleri). */
public class SeismoGraphView extends View {

    private static final int N = 500; // ~10 saniye (50 Hz)

    private final float[] xs = new float[N];
    private final float[] ys = new float[N];
    private final float[] zs = new float[N];
    private int pos = 0;
    private int count = 0;
    private float threshold = 0.1f;
    private int state = QuakeDetector.STATE_CALIBRATING;

    private final Paint pX = line(Color.parseColor("#E53935"));
    private final Paint pY = line(Color.parseColor("#43A047"));
    private final Paint pZ = line(Color.parseColor("#1E88E5"));
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pThr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    public SeismoGraphView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        pX.setStrokeWidth(1.6f * density);
        pY.setStrokeWidth(1.6f * density);
        pZ.setStrokeWidth(1.6f * density);
        pAxis.setColor(Color.parseColor("#B0BEC5"));
        pAxis.setStrokeWidth(1f * density);
        pThr.setColor(Color.parseColor("#FB8C00"));
        pThr.setStyle(Paint.Style.STROKE);
        pThr.setStrokeWidth(1.2f * density);
        pThr.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));
        pText.setColor(Color.parseColor("#455A64"));
        pText.setTextSize(11 * density);
        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(3 * density);
    }

    private static Paint line(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    public void add(float x, float y, float z, int st, float thr) {
        xs[pos] = x;
        ys[pos] = y;
        zs[pos] = z;
        pos = (pos + 1) % N;
        if (count < N) count++;
        state = st;
        threshold = thr;
        invalidate();
    }

    public void clear() {
        pos = 0;
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float mid = h / 2f;
        c.drawColor(Color.parseColor("#FAFAFA"));

        float maxAbs = Math.max(threshold * 2f, 0.05f);
        for (int i = 0; i < count; i++) {
            maxAbs = Math.max(maxAbs, Math.abs(xs[i]));
            maxAbs = Math.max(maxAbs, Math.abs(ys[i]));
            maxAbs = Math.max(maxAbs, Math.abs(zs[i]));
        }
        maxAbs *= 1.15f;
        float k = (h / 2f - 4 * density) / maxAbs;

        c.drawLine(0, mid, w, mid, pAxis);
        path.reset();
        path.moveTo(0, mid - threshold * k);
        path.lineTo(w, mid - threshold * k);
        path.moveTo(0, mid + threshold * k);
        path.lineTo(w, mid + threshold * k);
        c.drawPath(path, pThr);

        drawTrace(c, xs, pX, w, mid, k);
        drawTrace(c, ys, pY, w, mid, k);
        drawTrace(c, zs, pZ, w, mid, k);

        float ty = 14 * density;
        c.drawText(String.format(Locale.US, "±%.2f m/s²", maxAbs), 6 * density, ty, pText);
        float lx = w - 70 * density;
        pText.setColor(pX.getColor());
        c.drawText("X", lx, ty, pText);
        pText.setColor(pY.getColor());
        c.drawText("Y", lx + 20 * density, ty, pText);
        pText.setColor(pZ.getColor());
        c.drawText("Z", lx + 40 * density, ty, pText);
        pText.setColor(Color.parseColor("#455A64"));

        int bc;
        if (state == QuakeDetector.STATE_QUAKE) bc = Color.parseColor("#C62828");
        else if (state == QuakeDetector.STATE_SUSPICIOUS) bc = Color.parseColor("#F9A825");
        else bc = Color.parseColor("#CFD8DC");
        pBorder.setColor(bc);
        c.drawRect(0, 0, w, h, pBorder);
    }

    private void drawTrace(Canvas c, float[] buf, Paint p, float w, float mid, float k) {
        if (count < 2) return;
        path.reset();
        float step = w / (N - 1);
        int start = (pos - count + N) % N;
        float x0 = (N - count) * step;
        for (int i = 0; i < count; i++) {
            float v = buf[(start + i) % N];
            float x = x0 + i * step;
            float y = mid - v * k;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, p);
    }
}
