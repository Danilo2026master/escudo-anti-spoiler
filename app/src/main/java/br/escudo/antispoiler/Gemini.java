package br.escudo.antispoiler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** "Mini IA" opcional: Gemini na nuvem do Google, com a chave grátis do usuário. */
public final class Gemini {

    private static final String EXPAND_SYSTEM =
            "You help a spoiler-blocking phone app used by a Brazilian user. "
            + "The user describes something they do NOT want to be spoiled about (a game, movie, series, show, match, book...). "
            + "Return the words and short phrases that would appear in video titles, posts, headlines or comments about it, "
            + "in Portuguese AND English: official titles, abbreviations, numbered names (e.g. \"GTA 6\", \"GTA VI\"), "
            + "hashtags (without #), full names of characters, unique places, key plot terms. "
            + "\"strong\": terms almost exclusively about this topic, 8 to 25 items. "
            + "\"ambiguous\": distinctive terms that are often about this topic but also appear in unrelated contexts, 0 to 8 items. "
            + "Never include common words, generic first names alone, the words spoiler/trailer/final, or phrases over 4 words. "
            + "\"title\": a short clean name for the topic. \"about\": one sentence describing it, in Portuguese.";

    private static final String CLASSIFY_SYSTEM =
            "You are the judge of a spoiler-blocking phone app. You receive topics the user wants to avoid and a numbered list "
            + "of short texts from social apps (video titles, posts, captions, comments), usually in Portuguese or English. "
            + "For each text answer true if it is about, or could reveal anything about, one of the topics; false only if it is "
            + "clearly about something else. When unsure, answer true. Return exactly one boolean per text, in order.";

    private Gemini() { }

    public static final class Expansion {
        public String title;
        public String about;
        public List<String> strong = new ArrayList<>();
        public List<String> ambiguous = new ArrayList<>();
    }

    public static Expansion expand(String key, String model, String userText) throws Exception {
        JSONObject schema = obj("type", "OBJECT", "properties", obj(
                "title", obj("type", "STRING"),
                "about", obj("type", "STRING"),
                "strong", obj("type", "ARRAY", "items", obj("type", "STRING")),
                "ambiguous", obj("type", "ARRAY", "items", obj("type", "STRING"))));
        schema.put("required", new JSONArray().put("title").put("about").put("strong").put("ambiguous"));
        String prompt = "What the user does not want to see spoilers about: \"" + clip(userText, 300) + "\"\nReturn JSON.";
        JSONObject r = call(key, model, EXPAND_SYSTEM, prompt, schema);
        Expansion e = new Expansion();
        e.title = clip(r.optString("title", userText), 80);
        e.about = clip(r.optString("about", ""), 300);
        List<String> strong = new ArrayList<>();
        strong.add(e.title);
        strong.addAll(Prefs.list(r.optJSONArray("strong")));
        e.strong = SpoilerMatcher.cleanList(strong);
        e.ambiguous = SpoilerMatcher.cleanList(Prefs.list(r.optJSONArray("ambiguous")));
        return e;
    }

    /** topics: "Nome — descrição" de cada tema envolvido. */
    public static boolean[] classify(String key, String model, List<String> texts, List<String> topics) throws Exception {
        StringBuilder sb = new StringBuilder("Topics to avoid:\n");
        for (int i = 0; i < topics.size(); i++) sb.append(i + 1).append(") ").append(topics.get(i)).append('\n');
        sb.append("\nTexts (").append(texts.size()).append("):\n");
        for (int i = 0; i < texts.size(); i++) {
            sb.append(i + 1).append(". ").append(clip(texts.get(i).replaceAll("\\s+", " "), 300)).append('\n');
        }
        sb.append("\nReturn JSON {\"results\": [").append(texts.size()).append(" booleans]}.");
        JSONObject schema = obj("type", "OBJECT", "properties",
                obj("results", obj("type", "ARRAY", "items", obj("type", "BOOLEAN"))));
        schema.put("required", new JSONArray().put("results"));
        JSONObject r = call(key, model, CLASSIFY_SYSTEM, sb.toString(), schema);
        JSONArray arr = r.optJSONArray("results");
        boolean[] out = new boolean[texts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr == null || i >= arr.length() || arr.optBoolean(i, true);
        }
        return out;
    }

    // ---------------------------------------------------------------- HTTP
    static JSONObject call(String key, String model, String system, String prompt, JSONObject schema) throws Exception {
        if (key == null || key.isEmpty()) throw new Exception("Falta a chave do Gemini.");
        String[] models = model == null || model.isEmpty() || model.equals("gemini-flash-latest")
                ? new String[]{"gemini-flash-latest"}
                : new String[]{model, "gemini-flash-latest"};
        Exception last = null;
        for (String m : models) {
            JSONObject body = new JSONObject();
            body.put("systemInstruction", obj("parts", new JSONArray().put(obj("text", system))));
            body.put("contents", new JSONArray().put(obj("role", "user", "parts", new JSONArray().put(obj("text", prompt)))));
            body.put("generationConfig", obj("temperature", 0.2, "responseMimeType", "application/json", "responseSchema", schema));

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                    + URLEncoder.encode(m, "UTF-8") + ":generateContent");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("x-goog-api-key", key);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code == 404) { last = new Exception("Modelo " + m + " não encontrado."); continue; }
            if (code == 429) throw new Exception("Limite grátis do Gemini atingido por agora. Tente em alguns minutos.");
            if (code >= 400) {
                String msg = "HTTP " + code;
                try { msg = new JSONObject(resp).getJSONObject("error").optString("message", msg); } catch (JSONException e) { /* usa o código */ }
                if (code == 400 && msg.toLowerCase().contains("api key")) throw new Exception("Chave do Gemini inválida.");
                throw new Exception("Gemini: " + msg);
            }
            JSONObject data = new JSONObject(resp);
            JSONArray parts = data.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text", ""));
            return parseJson(text.toString());
        }
        throw last != null ? last : new Exception("Gemini indisponível.");
    }

    static JSONObject parseJson(String s) throws JSONException {
        s = s.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        return new JSONObject(s);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    static JSONObject obj(Object... kv) throws JSONException {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }
}
