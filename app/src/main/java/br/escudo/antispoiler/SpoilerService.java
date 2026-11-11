package br.escudo.antispoiler;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * O "olho" do Escudo 2.0.
 * - Lê o texto da tela dos apps escolhidos (títulos, legendas, comentários, buscas).
 * - Lê o texto DENTRO das imagens (miniaturas, Reels, Shorts) com reconhecimento no celular.
 * - Modo blindado: tela inteira preta + som mudo; Reels/Shorts/TikTok são pulados sozinhos;
 *   buscas com palavras bloqueadas são apagadas.
 */
public class SpoilerService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {

    static volatile boolean running = false;

    private static final int MAX_NODES = 3000;
    private static final long MAX_WAIT_MS = 200;
    private static final int MAX_AUTO_SKIPS = 8;

    /** Resultado de uma leitura da tela. */
    static final class ScanResult {
        String pkg = "";
        boolean watched;
        Rect screen = new Rect();
        final List<CoverOverlay.Cover> covers = new ArrayList<>();
        final List<Rect> items = new ArrayList<>(); // itens de lista visíveis (para mapear acertos da imagem)
        String searchTopic;
    }

    private Handler main;
    private HandlerThread workerThread;
    private Handler worker;
    private CoverOverlay overlay;
    private Blackout blackout;
    private ImageScanner imageScanner;
    private AudioManager audio;
    private final ExecutorService aiExec = Executors.newSingleThreadExecutor();

    // configuração
    private volatile boolean enabled = true;
    private volatile SpoilerMatcher matcher = new SpoilerMatcher(Collections.<Topic>emptyList());
    private volatile Set<String> packages = new HashSet<>();
    private volatile boolean aiOn = false;
    private volatile boolean blockAmbiguousNoAI = true;
    private volatile String geminiKey = "";
    private volatile String geminiModel = Prefs.DEFAULT_MODEL;
    private volatile Map<String, String> topicDescriptions = new HashMap<>();
    private volatile boolean blindado = true;
    private volatile boolean muteOn = true;
    private volatile boolean skipOn = true;
    private volatile boolean blockSearchOn = true;
    private volatile boolean ocrOn = true;
    private volatile boolean visionOn = false;

    // estado compartilhado
    private final Set<String> revealed = Collections.synchronizedSet(new HashSet<String>());
    private final Map<String, Boolean> verdicts = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 1500;
        }
    });
    private final Set<String> pendingAI = Collections.synchronizedSet(new HashSet<String>());
    private final List<Object[]> aiQueue = new ArrayList<>(); // {key, text, topicIds}
    private boolean aiFlushScheduled = false;
    private long lastAiCall = 0;

    // estado da thread principal
    private ScanResult lastScan;
    private final List<CoverOverlay.Cover> imageCovers = new ArrayList<>();
    private long searchBlockUntil = 0;
    private String searchTopic = "";
    private boolean mutedByUs = false;
    private boolean skipPending = false;
    private int skipCount = 0;
    private long lastSkipAt = 0;
    private volatile long lastEventAt = 0;

    private volatile long firstPendingAt = 0;
    private final Runnable scanRunnable = () -> {
        firstPendingAt = 0;
        try { scan(); } catch (RuntimeException e) { /* a tela mudou no meio; tenta na próxima */ }
    };

    private final Runnable imageLoop = new Runnable() {
        @Override
        public void run() {
            boolean recent = SystemClock.uptimeMillis() - lastEventAt < 4000;
            main.postDelayed(this, recent ? 450 : 1200);
            captureImage();
        }
    };

    // ---------------------------------------------------------------- ciclo de vida
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        main = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("escudo-leitura");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        overlay = new CoverOverlay(this, key -> {
            revealed.add(key);
            requestScan(0);
        });
        overlay.attach();
        blackout = new Blackout(this, new Blackout.Actions() {
            @Override public void onBack() {
                performGlobalAction(GLOBAL_ACTION_BACK);
                imageCovers.clear();
                searchBlockUntil = 0;
                requestScan(400);
            }
            @Override public void onSkip() {
                skipCount = 0;
                swipeUp();
            }
            @Override public void onShow() {
                revealAllOnScreen();
            }
        });
        if (ImageScanner.supported()) imageScanner = new ImageScanner(this);
        loadConfig();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this);
        requestScan(0);
        main.postDelayed(imageLoop, 1000);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        try { Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this); } catch (RuntimeException e) { /* ok */ }
        if (main != null) main.removeCallbacks(imageLoop);
        unmute();
        if (blackout != null) blackout.hide();
        if (overlay != null) overlay.detach();
        if (imageScanner != null) imageScanner.close();
        if (workerThread != null) workerThread.quitSafely();
        aiExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        boolean topicsChanged = Prefs.K_TOPICS.equals(key);
        loadConfig();
        if (topicsChanged) {
            revealed.clear();
            verdicts.clear();
        }
        imageCovers.clear();
        searchBlockUntil = 0;
        if (blackout != null) blackout.hide();
        unmute();
        requestScan(0);
    }

    private void loadConfig() {
        enabled = Prefs.enabled(this);
        List<Topic> topics = Prefs.topics(this);
        matcher = new SpoilerMatcher(topics);
        Map<String, String> desc = new HashMap<>();
        for (Topic t : topics) {
            if (!t.enabled) continue;
            desc.put(t.id, t.title + (t.about == null || t.about.isEmpty() ? "" : " — " + t.about));
        }
        topicDescriptions = desc;
        Set<String> pk = new HashSet<>();
        for (Map.Entry<String, Boolean> e : Prefs.apps(this).entrySet()) if (e.getValue()) pk.add(e.getKey());
        packages = pk;
        geminiKey = Prefs.geminiKey(this);
        geminiModel = Prefs.geminiModel(this);
        aiOn = Prefs.aiOn(this);
        blockAmbiguousNoAI = Prefs.blockAmbiguousWithoutAI(this);
        blindado = Prefs.blindado(this);
        muteOn = Prefs.flag(this, Prefs.K_MUTE);
        skipOn = Prefs.flag(this, Prefs.K_SKIP);
        blockSearchOn = Prefs.flag(this, Prefs.K_BLOCK_SEARCH);
        ocrOn = Prefs.flag(this, Prefs.K_OCR);
        visionOn = Prefs.flag(this, Prefs.K_VISION);
    }

    // ---------------------------------------------------------------- eventos
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || overlay == null) return;
        CharSequence pkgCs = e.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        int type = e.getEventType();
        boolean watched = packages.contains(pkg);
        if (watched) lastEventAt = SystemClock.uptimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            imageCovers.clear(); // tela nova: o que foi visto na imagem não vale mais
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && watched) {
            removeFullScreenImageCovers();
            if (Build.VERSION.SDK_INT >= 28) {
                int dx = e.getScrollDeltaX();
                int dy = e.getScrollDeltaY();
                if (dx != 0 || dy != 0) {
                    Rect area = null;
                    AccessibilityNodeInfo src = e.getSource();
                    if (src != null) {
                        area = new Rect();
                        src.getBoundsInScreen(area);
                    }
                    shiftImageCovers(area, -dx, -dy);
                    overlay.shift(area, -dx, -dy);
                }
            }
        }
        if (!watched && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && overlay.isEmpty() && !blackout.isShowing()) return;
        int delay = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ? 20 : 70;
        requestScan(delay);
    }

    private void requestScan(long delay) {
        if (worker == null) return;
        long now = SystemClock.uptimeMillis();
        if (firstPendingAt == 0) firstPendingAt = now;
        worker.removeCallbacks(scanRunnable);
        worker.postDelayed(scanRunnable, now - firstPendingAt >= MAX_WAIT_MS ? 0 : delay);
    }

    // ---------------------------------------------------------------- leitura do texto da tela (thread de fundo)
    private void scan() {
        SpoilerMatcher m = matcher;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        final ScanResult res = new ScanResult();
        CharSequence pkgCs = root.getPackageName();
        res.pkg = pkgCs == null ? "" : pkgCs.toString();
        res.watched = enabled && !m.isEmpty() && packages.contains(res.pkg);
        root.getBoundsInScreen(res.screen);
        if (res.screen.isEmpty()) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            res.screen.set(0, 0, dm.widthPixels, dm.heightPixels);
        }
        if (res.watched) {
            ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
            ArrayDeque<Boolean> isItem = new ArrayDeque<>();
            stack.push(root);
            isItem.push(Boolean.FALSE);
            int count = 0;
            while (!stack.isEmpty() && count < MAX_NODES) {
                AccessibilityNodeInfo n = stack.pop();
                boolean item = isItem.pop();
                count++;
                if (n == null || !n.isVisibleToUser()) continue;
                if (item) {
                    Rect ir = new Rect();
                    n.getBoundsInScreen(ir);
                    if (!ir.isEmpty()) res.items.add(ir);
                }
                if (n.isEditable()) {
                    checkSearch(n, m, res);
                } else {
                    CharSequence text = n.getText();
                    CharSequence desc = n.getContentDescription();
                    SpoilerMatcher.Match hit = text != null ? m.match(text) : null;
                    CharSequence used = text;
                    if (hit == null && desc != null) {
                        hit = m.match(desc);
                        used = desc;
                    }
                    if (hit != null) handleMatch(n, hit, used, res.screen, res.covers);
                }
                boolean list = isList(n);
                int c = n.getChildCount();
                for (int i = c - 1; i >= 0; i--) {
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) {
                        stack.push(ch);
                        isItem.push(list);
                    }
                }
            }
        }
        main.post(() -> render(res));
    }

    /** Modo agressivo: apaga a busca se ela tiver uma palavra certeira. */
    private void checkSearch(AccessibilityNodeInfo n, SpoilerMatcher m, ScanResult res) {
        if (!blockSearchOn) return;
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return;
        CharSequence t = n.getText();
        if (t == null || t.length() < 2) return;
        SpoilerMatcher.Match hit = m.match(t);
        if (hit == null || hit.kind != SpoilerMatcher.STRONG) return;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        res.searchTopic = hit.topic;
    }

    private void handleMatch(AccessibilityNodeInfo node, SpoilerMatcher.Match hit, CharSequence text, Rect screen,
                             List<CoverOverlay.Cover> out) {
        String key = keyFor("txt", hit.topic, text);
        if (revealed.contains(key)) return;
        boolean checking = false;
        if (hit.kind == SpoilerMatcher.AMBIGUOUS) {
            if (aiOn) {
                Boolean v = verdicts.get(key);
                if (v == null) {
                    checking = true;
                    requestAI(key, text.toString(), hit.topicIds);
                } else if (!v) {
                    return; // a IA liberou
                }
            } else if (!blockAmbiguousNoAI) {
                return;
            }
        }
        Rect r = containerRect(node, screen);
        if (r == null || r.isEmpty()) return;
        out.add(new CoverOverlay.Cover(r, hit.topic, key, checking));
    }

    private static String keyFor(String kind, String topic, CharSequence text) {
        String norm = SpoilerMatcher.norm(text).replaceAll("\\s+", " ").trim();
        if (norm.length() > 160) norm = norm.substring(0, 160);
        return kind + "|" + topic + "|" + norm;
    }

    /** Acha o "card" inteiro (vídeo, post, comentário) em volta do texto encontrado. */
    private Rect containerRect(AccessibilityNodeInfo node, Rect screen) {
        AccessibilityNodeInfo cur = node;
        AccessibilityNodeInfo item = null;
        AccessibilityNodeInfo list = null;
        for (int i = 0; i < 30; i++) {
            AccessibilityNodeInfo p = cur.getParent();
            if (p == null) break;
            if (isList(p)) {
                item = cur;
                list = p;
                break;
            }
            cur = p;
        }
        Rect r = new Rect();
        if (item != null) {
            item.getBoundsInScreen(r);
            expandToMedia(list, item, r, screen);
            Rect lr = new Rect();
            list.getBoundsInScreen(lr);
            if (!lr.isEmpty() && !r.intersect(lr)) return null;
        } else {
            node.getBoundsInScreen(r);
            AccessibilityNodeInfo b = node;
            Rect pr = new Rect();
            for (int i = 0; i < 4; i++) {
                AccessibilityNodeInfo p = b.getParent();
                if (p == null) break;
                p.getBoundsInScreen(pr);
                if (pr.isEmpty() || pr.height() > screen.height() * 0.35f) break;
                r.set(pr);
                b = p;
            }
        }
        if (!r.intersect(screen)) return null;
        return r;
    }

    /** Instagram/Facebook/Threads: legenda numa linha pequena + foto/vídeo do post na linha de cima. */
    private void expandToMedia(AccessibilityNodeInfo list, AccessibilityNodeInfo item, Rect r, Rect screen) {
        int h = screen.height();
        if (list == null || r.height() >= h * 0.30f) return;
        int count = list.getChildCount();
        int idx = -1;
        for (int i = 0; i < count && i < 80; i++) {
            AccessibilityNodeInfo ch = list.getChild(i);
            if (ch != null && ch.equals(item)) { idx = i; break; }
        }
        if (idx <= 0) return;
        float gap = 12 * getResources().getDisplayMetrics().density;
        Rect acc = new Rect(r);
        int top = r.top;
        Rect sr = new Rect();
        for (int k = idx - 1; k >= Math.max(0, idx - 4); k--) {
            AccessibilityNodeInfo s = list.getChild(k);
            if (s == null) return;
            s.getBoundsInScreen(sr);
            if (sr.isEmpty() || Math.abs(sr.bottom - top) > gap) return;
            if (sr.height() >= h * 0.25f) {
                acc.union(sr);
                if (acc.height() <= h * 0.9f) r.set(acc);
                return;
            }
            if (sr.height() > h * 0.15f) return;
            acc.union(sr);
            top = sr.top;
        }
    }

    private static boolean isList(AccessibilityNodeInfo n) {
        if (n.getCollectionInfo() != null) return true;
        CharSequence cls = n.getClassName();
        String c = cls == null ? "" : cls.toString();
        if (c.endsWith("RecyclerView") || c.endsWith("ListView") || c.endsWith("GridView")
                || c.endsWith("ViewPager") || c.endsWith("ViewPager2") || c.endsWith("ScrollView")) return true;
        return n.isScrollable();
    }

    // ---------------------------------------------------------------- desenhar (thread principal)
    private void render(ScanResult res) {
        lastScan = res;
        if (!res.watched) {
            imageCovers.clear();
            hideEverything();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (res.searchTopic != null) {
            searchBlockUntil = now + 1800;
            searchTopic = res.searchTopic;
            main.postDelayed(() -> requestScan(0), 1900);
        }
        boolean searchActive = now < searchBlockUntil;

        // capas vindas da imagem: aplica o veredito da IA e tira as que saíram da tela
        for (Iterator<CoverOverlay.Cover> it = imageCovers.iterator(); it.hasNext(); ) {
            CoverOverlay.Cover c = it.next();
            Boolean v = verdicts.get(c.key);
            if (revealed.contains(c.key) || (c.checking && Boolean.FALSE.equals(v)) || !Rect.intersects(c.rect, res.screen)) {
                it.remove();
            }
        }
        List<CoverOverlay.Cover> all = new ArrayList<>(res.covers);
        for (CoverOverlay.Cover c : imageCovers) {
            boolean checking = c.checking && !Boolean.TRUE.equals(verdicts.get(c.key));
            all.add(new CoverOverlay.Cover(new Rect(c.rect), c.topic, c.key, checking));
        }
        all = dedupe(all);

        if (blindado && (searchActive || !all.isEmpty())) {
            overlay.clear();
            CoverOverlay.Cover first = all.isEmpty() ? null : all.get(0);
            String topic = searchActive ? searchTopic : first.topic;
            String reason = searchActive ? "busca" : first.key.startsWith("img|") ? "imagem" : first.key.startsWith("ia|") ? "ia" : "texto";
            boolean full = !searchActive && isFullScreen(all, res.screen);
            if (now - lastSkipAt > 20000) skipCount = 0;
            String status;
            if (full && skipOn && skipCount < MAX_AUTO_SKIPS) status = "Pulando para o próximo…";
            else if (full && skipOn) status = "Muitos spoilers seguidos. Toque em Voltar.";
            else if (searchActive) status = "";
            else status = "Role a tela para passar, ou use os botões.";
            blackout.show(topic, reason, status);
            mute();
            if (full && skipOn) scheduleSkip();
        } else {
            if (blackout.isShowing()) blackout.hide();
            unmute();
            overlay.setCovers(all);
            if (all.isEmpty() && now - lastSkipAt > 3000) skipCount = 0;
        }
    }

    private void hideEverything() {
        if (blackout != null && blackout.isShowing()) blackout.hide();
        unmute();
        if (overlay != null) overlay.clear();
    }

    private static boolean isFullScreen(List<CoverOverlay.Cover> covers, Rect screen) {
        for (CoverOverlay.Cover c : covers) if (c.rect.height() >= screen.height() * 0.8f) return true;
        return false;
    }

    private static List<CoverOverlay.Cover> dedupe(List<CoverOverlay.Cover> in) {
        Collections.sort(in, (a, b) -> (b.rect.width() * b.rect.height()) - (a.rect.width() * a.rect.height()));
        List<CoverOverlay.Cover> out = new ArrayList<>();
        for (CoverOverlay.Cover c : in) {
            boolean inside = false;
            for (CoverOverlay.Cover o : out) {
                if (o.rect.contains(c.rect)) { inside = true; break; }
            }
            if (!inside) out.add(c);
        }
        return out;
    }

    private void revealAllOnScreen() {
        if (lastScan != null) for (CoverOverlay.Cover c : lastScan.covers) revealed.add(c.key);
        for (CoverOverlay.Cover c : imageCovers) revealed.add(c.key);
        imageCovers.clear();
        searchBlockUntil = 0;
        blackout.hide();
        unmute();
        requestScan(0);
    }

    // ---------------------------------------------------------------- som e gestos
    private void mute() {
        if (!muteOn || mutedByUs || audio == null) return;
        try {
            if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                mutedByUs = true;
            }
        } catch (RuntimeException e) { /* "Não perturbe" pode impedir */ }
    }

    private void unmute() {
        if (!mutedByUs || audio == null) return;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        } catch (RuntimeException e) { /* ok */ }
        mutedByUs = false;
    }

    private void scheduleSkip() {
        if (skipPending || skipCount >= MAX_AUTO_SKIPS) return;
        skipPending = true;
        main.postDelayed(() -> {
            skipPending = false;
            if (blackout.isShowing()) {
                skipCount++;
                swipeUp();
            }
        }, 300);
    }

    /** Arrasta para cima: próximo Reel/Short/TikTok, ou desce o feed. */
    private void swipeUp() {
        lastSkipAt = SystemClock.uptimeMillis();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.5f;
        Path p = new Path();
        p.moveTo(x, dm.heightPixels * 0.72f);
        p.lineTo(x, dm.heightPixels * 0.22f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 180))
                .build();
        dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                imageCovers.clear();
                requestScan(250);
            }

            @Override
            public void onCancelled(GestureDescription d) {
                requestScan(0);
            }
        }, main);
    }

    // ---------------------------------------------------------------- reconhecimento por imagem
    private void captureImage() {
        if (!ocrOn || imageScanner == null || imageScanner.isBusy() || !enabled) return;
        ScanResult s = lastScan;
        if (s == null || !s.watched || blackout.isShowing()) return;
        List<Rect> exclude = overlay.currentRects();
        ImageScanner.VisionConfig v = new ImageScanner.VisionConfig();
        v.key = geminiKey;
        v.model = geminiModel;
        v.topics = new ArrayList<>(topicDescriptions.values());
        v.allowed = visionOn && Prefs.VISION_APPS.contains(s.pkg);
        v.screenChanged = SystemClock.uptimeMillis() - lastEventAt < 6000;
        imageScanner.capture(matcher, exclude, v, (hits, w, h) -> main.post(() -> applyImageHits(hits)));
    }

    private void applyImageHits(List<ImageScanner.Hit> hits) {
        ScanResult s = lastScan;
        if (s == null || !s.watched || hits.isEmpty()) return;
        boolean changed = false;
        float pad = 12 * getResources().getDisplayMetrics().density;
        for (ImageScanner.Hit h : hits) {
            String key = keyFor(h.viaAI ? "ia" : "img", h.topic, h.text);
            if (revealed.contains(key)) continue;
            boolean checking = false;
            if (h.ambiguous) {
                if (aiOn) {
                    Boolean v = verdicts.get(key);
                    if (Boolean.FALSE.equals(v)) continue;
                    if (v == null) {
                        checking = true;
                        requestAI(key, h.text, h.topicIds);
                    }
                } else if (!blockAmbiguousNoAI) {
                    continue;
                }
            }
            Rect target;
            if (h.box == null) {
                target = new Rect(s.screen);
            } else {
                target = itemAround(s, h.box);
                if (target == null) {
                    target = new Rect(h.box);
                    target.inset((int) -pad, (int) -pad);
                }
            }
            if (!target.intersect(s.screen)) continue;
            boolean dup = false;
            for (CoverOverlay.Cover c : imageCovers) {
                if (c.key.equals(key) || c.rect.contains(target)) { dup = true; break; }
            }
            if (dup) continue;
            imageCovers.add(new CoverOverlay.Cover(target, h.topic, key, checking));
            changed = true;
        }
        if (changed) render(s);
    }

    /** O menor item de lista que contém o centro do texto achado na imagem (a miniatura inteira, o post...). */
    private static Rect itemAround(ScanResult s, Rect box) {
        int cx = box.centerX();
        int cy = box.centerY();
        Rect best = null;
        for (Rect r : s.items) {
            if (!r.contains(cx, cy) || r.height() > s.screen.height() * 0.97f) continue;
            if (best == null || r.width() * r.height() < best.width() * best.height()) best = r;
        }
        if (best == null) {
            for (Rect r : s.items) if (r.contains(cx, cy)) { best = r; break; } // tela cheia (Reels/Shorts)
        }
        return best == null ? null : new Rect(best);
    }

    private void shiftImageCovers(Rect area, int dx, int dy) {
        for (CoverOverlay.Cover c : imageCovers) {
            if (area == null || Rect.intersects(area, c.rect)) c.rect.offset(dx, dy);
        }
    }

    private void removeFullScreenImageCovers() {
        for (Iterator<CoverOverlay.Cover> it = imageCovers.iterator(); it.hasNext(); ) {
            if (it.next().key.startsWith("ia|")) it.remove();
        }
    }

    // ---------------------------------------------------------------- mini IA de texto (Gemini)
    private void requestAI(String key, String text, List<String> topicIds) {
        if (!pendingAI.add(key)) return;
        synchronized (aiQueue) {
            aiQueue.add(new Object[]{key, text, topicIds});
            if (!aiFlushScheduled) {
                aiFlushScheduled = true;
                long wait = Math.max(400, 4000 - (SystemClock.uptimeMillis() - lastAiCall));
                worker.postDelayed(this::flushAI, wait);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void flushAI() {
        final List<Object[]> batch = new ArrayList<>();
        synchronized (aiQueue) {
            aiFlushScheduled = false;
            while (!aiQueue.isEmpty() && batch.size() < 10) batch.add(aiQueue.remove(0));
            if (!aiQueue.isEmpty()) {
                aiFlushScheduled = true;
                worker.postDelayed(this::flushAI, 4000);
            }
        }
        if (batch.isEmpty()) return;
        lastAiCall = SystemClock.uptimeMillis();
        final String key = geminiKey;
        final String model = geminiModel;
        final Map<String, String> desc = topicDescriptions;
        aiExec.execute(() -> {
            List<String> texts = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Object[] it : batch) {
                texts.add((String) it[1]);
                ids.addAll((List<String>) it[2]);
            }
            List<String> topics = new ArrayList<>();
            for (String id : ids) if (desc.containsKey(id)) topics.add(desc.get(id));
            if (topics.isEmpty()) topics.addAll(desc.values());
            boolean[] res;
            try {
                res = Gemini.classify(key, model, texts, topics);
            } catch (Exception e) {
                res = new boolean[batch.size()];
                for (int i = 0; i < res.length; i++) res[i] = true; // sem resposta = bloqueia por segurança
            }
            for (int i = 0; i < batch.size(); i++) {
                String k = (String) batch.get(i)[0];
                verdicts.put(k, res[i]);
                pendingAI.remove(k);
            }
            requestScan(0);
        });
    }
}
