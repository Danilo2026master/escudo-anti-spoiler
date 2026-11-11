package br.escudo.antispoiler;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Modo blindado: deixa a tela INTEIRA preta quando aparece spoiler.
 * - A tela preta não recebe toques: dá para continuar rolando "no escuro" até o spoiler sair.
 * - Uma barrinha embaixo (essa recebe toques) tem Voltar / Pular / Mostrar.
 * Roda na thread principal.
 */
final class Blackout {

    interface Actions {
        void onBack();
        void onSkip();
        void onShow();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Actions actions;
    private final float dp;
    private FrameLayout screen;
    private LinearLayout bar;
    private TextView title;
    private TextView sub;
    private TextView status;
    private boolean showing = false;

    Blackout(Context ctx, Actions actions) {
        this.ctx = ctx;
        this.actions = actions;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.dp = ctx.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return showing;
    }

    private WindowManager.LayoutParams params(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    private void build() {
        screen = new FrameLayout(ctx);
        screen.setBackgroundColor(Color.BLACK);
        LinearLayout center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView icon = text("🛡️", 52, Color.WHITE, false);
        title = text("Spoiler bloqueado", 22, Color.WHITE, true);
        sub = text("", 15, 0xFFC4B5FD, false);
        status = text("", 14, 0xFF8A83A3, false);
        center.addView(icon);
        center.addView(title);
        center.addView(sub);
        center.addView(status);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        screen.addView(center, lp);

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (6 * dp);
        bar.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2201A2E);
        bg.setCornerRadius(18 * dp);
        bg.setStroke((int) (1 * dp), 0x557C3AED);
        bar.setBackground(bg);
        bar.addView(button("← Voltar", v -> actions.onBack()));
        bar.addView(button("⏭ Pular", v -> actions.onSkip()));
        bar.addView(button("👁 Mostrar", v -> actions.onShow()));
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        int p = (int) (4 * dp);
        t.setPadding((int) (24 * dp), p, (int) (24 * dp), p);
        return t;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView b = text(label, 15, Color.WHITE, true);
        int h = (int) (16 * dp);
        int v = (int) (11 * dp);
        b.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF7C3AED);
        bg.setCornerRadius(12 * dp);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (4 * dp);
        lp.setMargins(m, 0, m, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /** reason: "texto", "imagem", "ia", "busca". */
    void show(String topic, String reason, String statusText) {
        if (screen == null) build();
        sub.setText(("busca".equals(reason) ? "Busca apagada · " : "") + topic + "  ·  " + label(reason));
        status.setText(statusText == null ? "" : statusText);
        if (showing) return;
        try {
            wm.addView(screen, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false));
            WindowManager.LayoutParams bp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
            bp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            bp.y = (int) (72 * dp);
            wm.addView(bar, bp);
            showing = true;
        } catch (RuntimeException e) {
            hide();
        }
    }

    private static String label(String reason) {
        if ("imagem".equals(reason)) return "visto na imagem";
        if ("ia".equals(reason)) return "reconhecido pela IA visual";
        if ("busca".equals(reason)) return "não pesquise isso 😉";
        return "visto no texto";
    }

    void hide() {
        if (screen != null) try { wm.removeViewImmediate(screen); } catch (RuntimeException e) { /* já saiu */ }
        if (bar != null) try { wm.removeViewImmediate(bar); } catch (RuntimeException e) { /* já saiu */ }
        showing = false;
    }
}
