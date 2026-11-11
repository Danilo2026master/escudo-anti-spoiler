package br.escudo.antispoiler;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configurações do app, guardadas no próprio celular. */
public final class Prefs {

    public static final String FILE = "escudo";
    public static final String K_ENABLED = "enabled";
    public static final String K_TOPICS = "topics";
    public static final String K_APPS = "apps";
    public static final String K_GEMINI_KEY = "geminiKey";
    public static final String K_GEMINI_MODEL = "geminiModel";
    public static final String K_VERIFY = "verify";
    public static final String K_BLOCK_AMBIGUOUS = "blockAmbiguousNoAI";
    public static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    // v2.0 — modo blindado
    public static final String K_MODE = "mode";               // "blindado" (tela toda escura) | "post" (capa só no post)
    public static final String K_MUTE = "mute";               // silencia o som quando bloqueia
    public static final String K_SKIP = "skipShorts";         // pula Reels/Shorts/TikTok sozinho
    public static final String K_BLOCK_SEARCH = "blockSearch";// apaga buscas com palavras bloqueadas
    public static final String K_OCR = "imageOcr";            // lê texto dentro de imagens/vídeos (no celular)
    public static final String K_VISION = "imageAI";          // IA visual (Gemini) — opcional

    /** Apps onde a IA visual pode olhar a tela (nunca apps de conversa). */
    public static final List<String> VISION_APPS = Arrays.asList(
            "com.google.android.youtube", "com.instagram.android", "com.facebook.katana", "com.facebook.lite",
            "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.instagram.barcelona", "com.reddit.frontpage", "com.kwai.video");

    /** Apps que o Escudo pode proteger: pacote -> nome. */
    public static final LinkedHashMap<String, String> APPS = new LinkedHashMap<>();
    /** Ligados de fábrica. */
    private static final List<String> APPS_ON = Arrays.asList(
            "com.google.android.youtube", "com.instagram.android", "com.facebook.katana",
            "com.facebook.lite", "com.twitter.android", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.instagram.barcelona", "com.reddit.frontpage", "com.kwai.video");

    static {
        APPS.put("com.google.android.youtube", "YouTube");
        APPS.put("com.instagram.android", "Instagram");
        APPS.put("com.facebook.katana", "Facebook");
        APPS.put("com.facebook.lite", "Facebook Lite");
        APPS.put("com.twitter.android", "X (Twitter)");
        APPS.put("com.zhiliaoapp.musically", "TikTok");
        APPS.put("com.ss.android.ugc.trill", "TikTok (outra versão)");
        APPS.put("com.instagram.barcelona", "Threads");
        APPS.put("com.reddit.frontpage", "Reddit");
        APPS.put("com.kwai.video", "Kwai");
        APPS.put("com.android.chrome", "Chrome");
        APPS.put("com.whatsapp", "WhatsApp");
        APPS.put("org.telegram.messenger", "Telegram");
        APPS.put("com.discord", "Discord");
    }

    private Prefs() { }

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
    }

    // ---------------------------------------------------------------- apps
    public static Map<String, Boolean> apps(Context c) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        JSONObject saved = null;
        try {
            String s = sp(c).getString(K_APPS, null);
            if (s != null) saved = new JSONObject(s);
        } catch (JSONException e) { /* usa o padrão */ }
        for (String pkg : APPS.keySet()) {
            boolean def = APPS_ON.contains(pkg);
            out.put(pkg, saved != null ? saved.optBoolean(pkg, def) : def);
        }
        return out;
    }

    public static void setApp(Context c, String pkg, boolean on) {
        Map<String, Boolean> m = apps(c);
        m.put(pkg, on);
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Boolean> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        } catch (JSONException e) { return; }
        sp(c).edit().putString(K_APPS, o.toString()).apply();
    }

    // ---------------------------------------------------------------- temas
    public static List<Topic> topics(Context c) {
        String s = sp(c).getString(K_TOPICS, null);
        if (s == null) return defaultTopics();
        List<Topic> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Topic t = new Topic();
                t.id = o.optString("id", Topic.newId());
                t.title = o.optString("title", "");
                t.about = o.optString("about", "");
                t.enabled = o.optBoolean("enabled", true);
                t.strong = list(o.optJSONArray("strong"));
                t.ambiguous = list(o.optJSONArray("ambiguous"));
                out.add(t);
            }
        } catch (JSONException e) {
            return defaultTopics();
        }
        return out;
    }

    public static void saveTopics(Context c, List<Topic> topics) {
        JSONArray arr = new JSONArray();
        try {
            for (Topic t : topics) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("about", t.about == null ? "" : t.about);
                o.put("enabled", t.enabled);
                o.put("strong", new JSONArray(t.strong));
                o.put("ambiguous", new JSONArray(t.ambiguous));
                arr.put(o);
            }
        } catch (JSONException e) { return; }
        sp(c).edit().putString(K_TOPICS, arr.toString()).apply();
    }

    static List<String> list(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static List<Topic> defaultTopics() {
        List<Topic> l = new ArrayList<>();
        l.add(new Topic("gta6", "GTA 6",
                "Jogo Grand Theft Auto VI, da Rockstar Games (história, final, personagens, missões, vazamentos)",
                Arrays.asList("GTA 6", "GTA VI", "Grand Theft Auto 6", "Grand Theft Auto VI", "Leonida",
                        "Lucia Caminos", "Jason Duval", "Jason e Lucia", "Lucia e Jason", "Jason and Lucia",
                        "Lucia and Jason", "Port Gellhorn", "Grassrivers"),
                Arrays.asList("Vice City", "Rockstar Games")));
        l.add(new Topic("doomsday", "Vingadores: Doomsday",
                "Filme Avengers: Doomsday / Vingadores: Doomsday da Marvel (enredo, final, mortes, cenas pós-créditos, Doutor Destino)",
                Arrays.asList("Avengers Doomsday", "Vingadores Doomsday", "Avengers 5", "Vingadores 5", "Doctor Doom",
                        "Dr Doom", "Doutor Destino", "Dr Destino", "Victor von Doom"),
                Arrays.asList("Doomsday", "Secret Wars", "Guerras Secretas")));
        return l;
    }

    // ---------------------------------------------------------------- IA
    public static String geminiKey(Context c) {
        return sp(c).getString(K_GEMINI_KEY, "").trim();
    }

    public static String geminiModel(Context c) {
        String m = sp(c).getString(K_GEMINI_MODEL, "").trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }

    public static boolean verify(Context c) {
        return sp(c).getBoolean(K_VERIFY, true);
    }

    public static boolean blockAmbiguousWithoutAI(Context c) {
        return sp(c).getBoolean(K_BLOCK_AMBIGUOUS, true);
    }

    public static boolean blindado(Context c) {
        return !"post".equals(sp(c).getString(K_MODE, "blindado"));
    }

    public static boolean flag(Context c, String key) {
        boolean def = !K_VISION.equals(key); // IA visual começa desligada (envia imagens para o Google)
        return sp(c).getBoolean(key, def);
    }

    public static boolean aiOn(Context c) {
        return verify(c) && !geminiKey(c).isEmpty();
    }
}
