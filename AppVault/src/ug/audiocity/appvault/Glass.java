package ug.audiocity.appvault;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.FrameLayout;

/** A rounded panel of frosted glass: the blurred wallpaper behind it plus a light tint, like the iPhone dock. */
final class Glass extends FrameLayout {
    private final float radius;
    private final int tint;
    private final Path clip = new Path();
    /** When true the panel is as tall as it is wide (App Library boxes). */
    boolean square;

    Glass(Context c, float radiusPx, int tint) {
        super(c);
        this.radius = radiusPx;
        this.tint = tint;
        setWillNotDraw(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, square ? widthSpec : heightSpec);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        Wallpaper.drawBehind(canvas, this, true);
        canvas.drawColor(tint);
        canvas.restore();
        super.dispatchDraw(canvas);
    }
}
