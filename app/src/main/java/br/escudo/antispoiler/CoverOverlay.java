package br.escudo.antispoiler;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenha as capas por cima dos outros apps.
 * - Uma janela de tela cheia que NÃO recebe toques (o feed continua rolando normalmente) com todas as capas.
 * - Um botãozinho "👁 Ver" (janela pequena, essa sim tocável) no canto de cada capa.
 * Tudo aqui roda na thread principal.
 */
final class CoverOverlay {

    interface RevealListener {
        void onReveal(String key);
    }

    static final class Cover {
        final Rect rect;
        final String topic;
        final String key;
        final boolean checking;

        Cover(Rect rect, String topic, String key, boolean checking) {
            this.rect = rect;
            this.topic = topic;
            this.key = key;
            this.checking = checking;
        }
    }

    private static final int MAX_CHIPS = 12;

    private final Context ctx;
    private final WindowManager wm;
    private final RevealListener listener;
    private final float dp;
    private CoverView coverView;
    private List<Cover> covers = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private final List<WindowManager.LayoutParams> chipParams = new ArrayList<>();
    private final List<String> chipKeys = new ArrayList<>();
    private final int[] origin = new int[2];

    CoverOverlay(Context ctx, RevealListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.dp = ctx.getResources().getDisplayMetrics().density;
    }

    private WindowManager.LayoutParams baseParams(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = 0;
        p.y = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    void attach() {
        if (coverView != null) return;
        coverView = new CoverView(ctx);
        try {
            wm.addView(coverView, baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false));
        } catch (RuntimeException e) {
            coverView = null;
        }
    }

    void detach() {
        for (TextView t : chips) safeRemove(t);
        chips.clear();
        chipParams.clear();
        chipKeys.clear();
        if (coverView != null) safeRemove(coverView);
        coverView = null;
        covers = new ArrayList<>();
    }

    boolean isEmpty() {
        return covers.isEmpty();
    }

    /** Áreas cobertas agora (o reconhecimento por imagem ignora essas partes). */
    List<Rect> currentRects() {
        List<Rect> out = new ArrayList<>();
        for (Cover c : covers) out.add(new Rect(c.rect));
        return out;
    }

    void setCovers(List<Cover> list) {
        covers = list;
        if (coverView != null) coverView.invalidate();
        updateChips();
    }

    void clear() {
        if (!covers.isEmpty()) setCovers(new ArrayList<Cover>());
    }

    /** Move as capas na hora em que o feed rola, antes da próxima leitura da tela. */
    void shift(Rect area, int dx, int dy) {
        if (covers.isEmpty() || (dx == 0 && dy == 0)) return;
        for (Cover c : covers) {
            if (area == null || Rect.intersects(area, c.rect)) c.rect.offset(dx, dy);
        }
        if (coverView != null) coverView.invalidate();
        updateChips();
    }

    void removeKey(String key) {
        List<Cover> keep = new ArrayList<>();
        for (Cover c : covers) if (!c.key.equals(key)) keep.add(c);
        setCovers(keep);
    }

    // ---------------------------------------------------------------- botões "Ver"
    private void updateChips() {
        if (coverView != null) coverView.getLocationOnScreen(origin);
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        List<Cover> withChip = new ArrayList<>();
        for (Cover c : covers) {
            if (withChip.size() >= MAX_CHIPS) break;
            if (c.rect.height() < 28 * dp || c.rect.bottom < 0 || c.rect.top > screenH) continue;
            withChip.add(c);
        }
        while (chips.size() > withChip.size()) {
            int i = chips.size() - 1;
            safeRemove(chips.remove(i));
            chipParams.remove(i);
            chipKeys.remove(i);
        }
        for (int i = 0; i < withChip.size(); i++) {
            Cover c = withChip.get(i);
            if (i >= chips.size()) {
                TextView tv = makeChip();
                WindowManager.LayoutParams p = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
                place(tv, p, c);
                try {
                    wm.addView(tv, p);
                } catch (RuntimeException e) {
                    return;
                }
                chips.add(tv);
                chipParams.add(p);
                chipKeys.add(c.key);
            } else {
                TextView tv = chips.get(i);
                WindowManager.LayoutParams p = chipParams.get(i);
                int ox = p.x;
                int oy = p.y;
                place(tv, p, c);
                chipKeys.set(i, c.key);
                if (ox != p.x || oy != p.y) {
                    try { wm.updateViewLayout(tv, p); } catch (RuntimeException e) { /* janela sumiu */ }
                }
            }
            final int idx = i;
            chips.get(i).setOnClickListener(v -> {
                if (idx < chipKeys.size()) {
                    String key = chipKeys.get(idx);
                    removeKey(key);
                    listener.onReveal(key);
                }
            });
        }
    }

    private void place(TextView tv, WindowManager.LayoutParams p, Cover c) {
        tv.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = tv.getMeasuredWidth();
        int h = tv.getMeasuredHeight();
        int pad = (int) (6 * dp);
        p.x = Math.max(0, c.rect.right - w - pad) - origin[0];
        p.y = (c.rect.height() > h + 2 * pad ? c.rect.top + pad : c.rect.top + (c.rect.height() - h) / 2) - origin[1];
    }

    private TextView makeChip() {
        TextView tv = new TextView(ctx);
        tv.setText("👁 Ver");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int h = (int) (12 * dp);
        int v = (int) (5 * dp);
        tv.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF7C3AED);
        bg.setCornerRadius(99 * dp);
        tv.setBackground(bg);
        return tv;
    }

    private void safeRemove(View v) {
        try { wm.removeViewImmediate(v); } catch (RuntimeException e) { /* já removida */ }
    }

    // ---------------------------------------------------------------- desenho das capas
    private final class CoverView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final int[] loc = new int[2];

        CoverView(Context c) {
            super(c);
            fill.setColor(0xFA17131F);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(0x997C3AED);
            title.setColor(Color.WHITE);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
            title.setTextAlign(Paint.Align.CENTER);
            sub.setColor(0xFFC4B5FD);
            sub.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
            sub.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(loc);
            float r = 14 * dp;
            for (Cover c : covers) {
                rf.set(c.rect);
                rf.offset(-loc[0], -loc[1]);
                if (rf.width() < 4 || rf.height() < 4) continue;
                canvas.drawRoundRect(rf, r, r, fill);
                canvas.drawRoundRect(rf, r, r, stroke);
                if (rf.height() < 22 * dp) continue;
                float avail = rf.width() - 24 * dp - (rf.height() < 60 * dp ? 70 * dp : 0);
                if (avail < 40 * dp) continue;
                String label = c.checking ? "🤖 Conferindo se é spoiler…" : "🛡️ Spoiler de " + c.topic;
                CharSequence t = TextUtils.ellipsize(label, title, avail, TextUtils.TruncateAt.END);
                float cx = rf.height() < 60 * dp ? rf.left + 12 * dp + avail / 2f : rf.centerX();
                boolean twoLines = rf.height() >= 80 * dp;
                float cy = rf.centerY() - (twoLines ? 8 * dp : 0) - (title.descent() + title.ascent()) / 2f;
                canvas.drawText(t, 0, t.length(), cx, cy, title);
                if (twoLines) {
                    String hint = c.checking ? "a mini IA está lendo o post" : "toque em 👁 Ver para mostrar";
                    CharSequence h = TextUtils.ellipsize(hint, sub, avail, TextUtils.TruncateAt.END);
                    canvas.drawText(h, 0, h.length(), cx, cy + 22 * dp, sub);
                }
            }
        }
    }
}
