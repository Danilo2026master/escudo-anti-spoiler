package br.escudo.antispoiler;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reconhecimento por imagem (Android 11+):
 * 1) Tira um print da tela pelo serviço de acessibilidade (sem pedir permissão de gravar a tela).
 * 2) Lê o texto que está DENTRO das imagens (miniaturas, capas, legendas queimadas no vídeo) com o ML Kit,
 *    100% no celular, em ~0,1–0,3 s.
 * 3) Opcional: manda uma imagem pequena para a IA visual (Gemini), que reconhece personagens, logos e cenas.
 */
final class ImageScanner {

    static final class Hit {
        final Rect box;        // em coordenadas da tela (null = tela inteira)
        final String topic;
        final String text;
        final List<String> topicIds;
        final boolean ambiguous;
        final boolean viaAI;

        Hit(Rect box, String topic, String text, List<String> topicIds, boolean ambiguous, boolean viaAI) {
            this.box = box;
            this.topic = topic;
            this.text = text;
            this.topicIds = topicIds;
            this.ambiguous = ambiguous;
            this.viaAI = viaAI;
        }
    }

    interface Listener {
        /** Chamado numa thread de fundo. */
        void onImageResult(List<Hit> hits, int screenW, int screenH);
    }

    static final class VisionConfig {
        String key;
        String model;
        List<String> topics; // "Nome — descrição"
        boolean allowed;     // app atual pode ir para a IA visual
        boolean screenChanged;
    }

    private static final int OCR_WIDTH = 720;
    private static final long VISION_INTERVAL_MS = 4000;

    private final AccessibilityService svc;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService visionExec = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final Paint black = new Paint();
    private volatile boolean busy = false;
    private volatile boolean visionBusy = false;
    private volatile long lastVision = 0;

    ImageScanner(AccessibilityService svc) {
        this.svc = svc;
        black.setColor(Color.BLACK);
        black.setStyle(Paint.Style.FILL);
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    boolean isBusy() {
        return busy;
    }

    void close() {
        try { recognizer.close(); } catch (RuntimeException e) { /* ok */ }
        exec.shutdownNow();
        visionExec.shutdownNow();
    }

    /**
     * @param exclude áreas cobertas pelo próprio Escudo (não podem ser lidas, senão ele lê "Spoiler de GTA 6" nas capas).
     */
    void capture(final SpoilerMatcher matcher, final List<Rect> exclude, final VisionConfig vision, final Listener listener) {
        if (!supported() || busy) return;
        busy = true;
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    try {
                        process(result, matcher, exclude, vision, listener);
                    } catch (RuntimeException e) {
                        busy = false;
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    busy = false;
                }
            });
        } catch (RuntimeException e) {
            busy = false;
        }
    }

    private void process(AccessibilityService.ScreenshotResult result, final SpoilerMatcher matcher, List<Rect> exclude,
                         final VisionConfig vision, final Listener listener) {
        HardwareBuffer hb = result.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
        if (hw == null) {
            hb.close();
            busy = false;
            return;
        }
        Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        hb.close();
        if (full == null) {
            busy = false;
            return;
        }
        final int W = full.getWidth();
        final int H = full.getHeight();
        final float scale = Math.min(1f, OCR_WIDTH / (float) W);
        Bitmap small = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(W * scale)), Math.max(1, Math.round(H * scale)), true);
        if (small != full) full.recycle();
        if (!small.isMutable()) {
            Bitmap m = small.copy(Bitmap.Config.ARGB_8888, true);
            small.recycle();
            small = m;
        }
        // Apaga as áreas que o Escudo já cobriu.
        Canvas c = new Canvas(small);
        for (Rect r : exclude) {
            c.drawRect(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale, black);
        }
        final Bitmap img = small;
        recognizer.process(InputImage.fromBitmap(img, 0))
                .addOnSuccessListener(exec, text -> {
                    List<Hit> hits = new ArrayList<>();
                    for (Text.TextBlock b : text.getTextBlocks()) {
                        boolean blockHit = false;
                        for (Text.Line line : b.getLines()) {
                            SpoilerMatcher.Match m = matcher.match(line.getText());
                            Rect bb = line.getBoundingBox();
                            if (m != null && bb != null) {
                                hits.add(new Hit(unscale(bb, scale), m.topic, line.getText(), m.topicIds,
                                        m.kind == SpoilerMatcher.AMBIGUOUS, false));
                                blockHit = true;
                            }
                        }
                        if (!blockHit) { // termo quebrado em duas linhas ("GTA" / "VI")
                            SpoilerMatcher.Match m = matcher.match(b.getText().replace('\n', ' '));
                            Rect bb = b.getBoundingBox();
                            if (m != null && bb != null) {
                                hits.add(new Hit(unscale(bb, scale), m.topic, b.getText(), m.topicIds,
                                        m.kind == SpoilerMatcher.AMBIGUOUS, false));
                            }
                        }
                    }
                    if (hits.isEmpty()) maybeVision(img, vision, listener, W, H);
                    else img.recycle();
                    busy = false;
                    listener.onImageResult(hits, W, H);
                })
                .addOnFailureListener(exec, e -> {
                    img.recycle();
                    busy = false;
                });
    }

    private void maybeVision(final Bitmap img, final VisionConfig v, final Listener listener, final int W, final int H) {
        long now = SystemClock.uptimeMillis();
        if (v == null || !v.allowed || v.key == null || v.key.isEmpty() || !v.screenChanged
                || visionBusy || now - lastVision < VISION_INTERVAL_MS) {
            img.recycle();
            return;
        }
        visionBusy = true;
        lastVision = now;
        float s = Math.min(1f, 512f / img.getWidth());
        Bitmap tiny = Bitmap.createScaledBitmap(img, Math.max(1, Math.round(img.getWidth() * s)),
                Math.max(1, Math.round(img.getHeight() * s)), true);
        if (tiny != img) img.recycle();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        tiny.compress(Bitmap.CompressFormat.JPEG, 60, bo);
        tiny.recycle();
        final byte[] jpeg = bo.toByteArray();
        visionExec.execute(() -> {
            try {
                Gemini.VisionResult r = Gemini.vision(v.key, v.model, jpeg, v.topics);
                if (r.spoiler) {
                    List<Hit> hits = new ArrayList<>();
                    hits.add(new Hit(null, r.topic.isEmpty() ? "Spoiler" : r.topic, "IA visual: " + r.what,
                            new ArrayList<String>(), false, true));
                    listener.onImageResult(hits, W, H);
                }
            } catch (Exception e) {
                // sem rede ou sem cota: o reconhecimento de texto continua funcionando
            } finally {
                visionBusy = false;
            }
        });
    }

    private static Rect unscale(Rect r, float scale) {
        return new Rect(Math.round(r.left / scale), Math.round(r.top / scale),
                Math.round(r.right / scale), Math.round(r.bottom / scale));
    }
}
