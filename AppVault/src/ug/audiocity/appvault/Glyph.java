package ug.audiocity.appvault;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Thin line symbols for menus and search fields, drawn in code so they match the text colour. */
final class Glyph extends Drawable {
    static final int SEARCH = 0, LOCK = 1, PLUS = 2, MINUS = 3, INFO = 4, PHOTO = 5, HOME = 6, RESET = 7;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Glyph(int type, int color) {
        this.type = type;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
        p.setStrokeWidth(s * 0.09f);
        p.setStyle(Paint.Style.STROKE);
        switch (type) {
            case SEARCH:
                c.drawCircle(x + s * 0.42f, y + s * 0.42f, s * 0.28f, p);
                c.drawLine(x + s * 0.63f, y + s * 0.63f, x + s * 0.88f, y + s * 0.88f, p);
                break;
            case LOCK:
                c.drawArc(new RectF(x + s * 0.3f, y + s * 0.1f, x + s * 0.7f, y + s * 0.55f), 180, 180, false, p);
                c.drawLine(x + s * 0.3f, y + s * 0.32f, x + s * 0.3f, y + s * 0.45f, p);
                c.drawLine(x + s * 0.7f, y + s * 0.32f, x + s * 0.7f, y + s * 0.45f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(x + s * 0.18f, y + s * 0.44f, x + s * 0.82f, y + s * 0.92f), s * 0.08f, s * 0.08f, p);
                break;
            case PLUS:
            case MINUS:
                c.drawCircle(x + s / 2, y + s / 2, s * 0.42f, p);
                c.drawLine(x + s * 0.3f, y + s / 2, x + s * 0.7f, y + s / 2, p);
                if (type == PLUS) c.drawLine(x + s / 2, y + s * 0.3f, x + s / 2, y + s * 0.7f, p);
                break;
            case INFO:
                c.drawCircle(x + s / 2, y + s / 2, s * 0.42f, p);
                c.drawLine(x + s / 2, y + s * 0.45f, x + s / 2, y + s * 0.72f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x + s / 2, y + s * 0.3f, s * 0.06f, p);
                break;
            case PHOTO:
                c.drawRoundRect(new RectF(x + s * 0.08f, y + s * 0.18f, x + s * 0.92f, y + s * 0.82f), s * 0.1f, s * 0.1f, p);
                c.drawLine(x + s * 0.2f, y + s * 0.7f, x + s * 0.42f, y + s * 0.46f, p);
                c.drawLine(x + s * 0.42f, y + s * 0.46f, x + s * 0.6f, y + s * 0.64f, p);
                c.drawLine(x + s * 0.6f, y + s * 0.64f, x + s * 0.7f, y + s * 0.55f, p);
                c.drawLine(x + s * 0.7f, y + s * 0.55f, x + s * 0.82f, y + s * 0.7f, p);
                c.drawCircle(x + s * 0.7f, y + s * 0.34f, s * 0.06f, p);
                break;
            case HOME:
                c.drawLine(x + s * 0.12f, y + s * 0.48f, x + s / 2, y + s * 0.14f, p);
                c.drawLine(x + s / 2, y + s * 0.14f, x + s * 0.88f, y + s * 0.48f, p);
                c.drawRoundRect(new RectF(x + s * 0.24f, y + s * 0.42f, x + s * 0.76f, y + s * 0.86f), s * 0.05f, s * 0.05f, p);
                break;
            case RESET:
                c.drawArc(new RectF(x + s * 0.14f, y + s * 0.14f, x + s * 0.86f, y + s * 0.86f), -60, 300, false, p);
                c.drawLine(x + s * 0.68f, y + s * 0.2f, x + s * 0.7f, y + s * 0.03f, p);
                c.drawLine(x + s * 0.68f, y + s * 0.2f, x + s * 0.86f, y + s * 0.24f, p);
                break;
            default:
                break;
        }
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
