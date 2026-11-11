package br.escudo.antispoiler;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * O "olho" do Escudo: lê o texto que aparece nos apps escolhidos (YouTube, Instagram, Facebook...)
 * e cobre os posts/vídeos/comentários que falam dos temas bloqueados.
 * Nada é enviado para fora do celular, a não ser os trechos duvidosos se a pessoa ligar o Gemini.
 */
public class SpoilerService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {

    static volatile boolean running = false;

    private static final int MAX_NODES = 3000;
    private static final long MAX_WAIT_MS = 250;

    private Handler main;
    private HandlerThread workerThread;
    private Handler worker;
    private CoverOverlay overlay;
    private final ExecutorService aiExec = Executors.newSingleThreadExecutor();

    // configuração (lida na thread principal, usada na thread de leitura)
    private volatile boolean enabled = true;
    private volatile SpoilerMatcher matcher = new SpoilerMatcher(Collections.<Topic>emptyList());
    private volatile Set<String> packages = new HashSet<>();
    private volatile boolean aiOn = false;
    private volatile boolean blockAmbiguousNoAI = true;
    private volatile String geminiKey = "";
    private volatile String geminiModel = Prefs.DEFAULT_MODEL;
    private volatile Map<String, String> topicDescriptions = new HashMap<>();

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

    private volatile long firstPendingAt = 0;
    private final Runnable scanRunnable = () -> {
        firstPendingAt = 0;
        try { scan(); } catch (RuntimeException e) { /* a tela mudou no meio; tenta na próxima */ }
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
        overlay = new CoverOverlay(this, key -> {
            revealed.add(key);
            requestScan(0);
        });
        overlay.attach();
        loadConfig();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this);
        requestScan(0);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        try { Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this); } catch (RuntimeException e) { /* ok */ }
        if (overlay != null) overlay.detach();
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
        requestScan(0);
    }

    private void loadConfig() {
        enabled = Prefs.enabled(this);
        List<Topic> topics = Prefs.topics(this);
        matcher = new SpoilerMatcher(topics);
        Map<String, String> desc = new HashMap<>();
        for (Topic t : topics) {
            String d = t.title + (t.about == null || t.about.isEmpty() ? "" : " — " + t.about);
            desc.put(t.id, d);
        }
        topicDescriptions = desc;
        Set<String> pk = new HashSet<>();
        for (Map.Entry<String, Boolean> e : Prefs.apps(this).entrySet()) if (e.getValue()) pk.add(e.getKey());
        packages = pk;
        geminiKey = Prefs.geminiKey(this);
        geminiModel = Prefs.geminiModel(this);
        aiOn = Prefs.aiOn(this);
        blockAmbiguousNoAI = Prefs.blockAmbiguousWithoutAI(this);
    }

    // ---------------------------------------------------------------- eventos
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || overlay == null) return;
        CharSequence pkgCs = e.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        int type = e.getEventType();
        boolean watched = packages.contains(pkg);

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && watched && Build.VERSION.SDK_INT >= 28) {
            int dx = e.getScrollDeltaX();
            int dy = e.getScrollDeltaY();
            if (dx != 0 || dy != 0) {
                Rect area = null;
                AccessibilityNodeInfo src = e.getSource();
                if (src != null) {
                    area = new Rect();
                    src.getBoundsInScreen(area);
                }
                overlay.shift(area, -dx, -dy);
            }
        }
        // Eventos de outros apps só interessam se for troca de tela (para tirar as capas).
        if (!watched && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && overlay.isEmpty()) return;
        requestScan(type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ? 30 : 90);
    }

    private void requestScan(long delay) {
        if (worker == null) return;
        long now = SystemClock.uptimeMillis();
        if (firstPendingAt == 0) firstPendingAt = now;
        worker.removeCallbacks(scanRunnable);
        worker.postDelayed(scanRunnable, now - firstPendingAt >= MAX_WAIT_MS ? 0 : delay);
    }

    private void post(final List<CoverOverlay.Cover> covers) {
        main.post(() -> {
            if (overlay != null) overlay.setCovers(covers);
        });
    }

    // ---------------------------------------------------------------- leitura da tela (thread de fundo)
    private void scan() {
        SpoilerMatcher m = matcher;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (!enabled || m.isEmpty() || !packages.contains(pkg)) {
            post(new ArrayList<CoverOverlay.Cover>());
            return;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) {
            screen.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        }

        List<CoverOverlay.Cover> covers = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        int count = 0;
        while (!stack.isEmpty() && count < MAX_NODES) {
            AccessibilityNodeInfo n = stack.pop();
            count++;
            if (n == null || !n.isVisibleToUser()) continue;
            if (!n.isEditable()) { // nunca cobre o que a pessoa está digitando
                CharSequence text = n.getText();
                CharSequence desc = n.getContentDescription();
                SpoilerMatcher.Match hit = text != null ? m.match(text) : null;
                CharSequence used = text;
                if (hit == null && desc != null) {
                    hit = m.match(desc);
                    used = desc;
                }
                if (hit != null) handleMatch(n, hit, used, screen, covers);
            }
            int c = n.getChildCount();
            for (int i = c - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) stack.push(ch);
            }
        }
        post(dedupe(covers));
    }

    private void handleMatch(AccessibilityNodeInfo node, SpoilerMatcher.Match hit, CharSequence text, Rect screen,
                             List<CoverOverlay.Cover> out) {
        String norm = SpoilerMatcher.norm(text).replaceAll("\\s+", " ").trim();
        if (norm.length() > 160) norm = norm.substring(0, 160);
        String key = hit.topic + "|" + norm;
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
            if (!lr.isEmpty() && !r.intersect(lr)) return null; // não passa por cima da barra do app
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

    /**
     * Instagram/Facebook/Threads costumam montar cada post em várias linhas da lista
     * (foto, botões, curtidas, legenda). Se o spoiler está numa linha pequena (legenda) e logo acima
     * há uma linha grande (a foto/vídeo do post), cobre tudo junto.
     */
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
            if (sr.height() >= h * 0.25f) { // achou a foto/vídeo do post
                acc.union(sr);
                if (acc.height() <= h * 0.9f) r.set(acc);
                return;
            }
            if (sr.height() > h * 0.15f) return;
            acc.union(sr); // linha pequena no meio (botões, curtidas)
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

    /** Tira capas repetidas ou que já estão dentro de outra maior. */
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

    // ---------------------------------------------------------------- mini IA (Gemini)
    private void requestAI(String key, String text, List<String> topicIds) {
        if (!pendingAI.add(key)) return;
        synchronized (aiQueue) {
            aiQueue.add(new Object[]{key, text, topicIds});
            if (!aiFlushScheduled) {
                aiFlushScheduled = true;
                long wait = Math.max(400, 4000 - (SystemClock.uptimeMillis() - lastAiCall)); // no máx. ~15 pedidos/min
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
                for (int i = 0; i < res.length; i++) res[i] = true; // sem resposta = cobre por segurança
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
