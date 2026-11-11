package br.escudo.antispoiler;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detecta se um texto fala de algum tema bloqueado.
 * "GTA 6" também pega "gta6", "GTA-6", "#GTA6"; acentos e maiúsculas não importam;
 * a palavra precisa aparecer inteira ("Doom" não pega "Doomsday").
 */
public final class SpoilerMatcher {

    public static final int STRONG = 1;
    public static final int AMBIGUOUS = 2;

    public static final class Match {
        public final int kind;
        public final String topic;
        public final List<String> topicIds;

        Match(int kind, String topic, List<String> topicIds) {
            this.kind = kind;
            this.topic = topic;
            this.topicIds = topicIds;
        }
    }

    private static final class Compiled {
        String id;
        String name;
        Pattern strong;
        Pattern ambiguous;
    }

    private static final String SEP = "[\\s\\-_:.'’·|]";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPLIT = Pattern.compile("[\\s\\-_:.'’·|]+");

    private final List<Compiled> topics = new ArrayList<>();
    private final Map<String, Match> cache = new LinkedHashMap<String, Match>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Match> e) {
            return size() > 800;
        }
    };
    private static final Match NONE = new Match(0, null, Collections.<String>emptyList());

    public SpoilerMatcher(List<Topic> list) {
        for (Topic t : list) {
            if (t == null || !t.enabled) continue;
            Compiled c = new Compiled();
            c.id = t.id;
            c.name = t.title == null || t.title.isEmpty() ? "Spoiler" : t.title;
            c.strong = build(t.strong);
            c.ambiguous = build(t.ambiguous);
            if (c.strong != null || c.ambiguous != null) topics.add(c);
        }
    }

    public boolean isEmpty() {
        return topics.isEmpty();
    }

    public static String norm(CharSequence s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return MARKS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
    }

    static String keywordToPattern(String kw) {
        String n = norm(kw).trim();
        if (n.startsWith("#")) n = n.substring(1);
        String[] parts = SPLIT.split(n);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(SEP).append('*');
            sb.append(Pattern.quote(p));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Pattern build(List<String> words) {
        if (words == null || words.isEmpty()) return null;
        List<String> pats = new ArrayList<>();
        for (String w : words) {
            String p = keywordToPattern(w);
            if (p != null) pats.add(p);
        }
        if (pats.isEmpty()) return null;
        Collections.sort(pats, (a, b) -> b.length() - a.length());
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}])(?:");
        for (int i = 0; i < pats.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(pats.get(i));
        }
        sb.append(")(?![\\p{L}\\p{N}])");
        return Pattern.compile(sb.toString());
    }

    /** Devolve o resultado para um texto ainda NÃO normalizado (ou null se não bater). */
    public synchronized Match match(CharSequence raw) {
        if (raw == null || raw.length() < 2) return null;
        String key = raw.length() > 600 ? raw.subSequence(0, 600).toString() : raw.toString();
        Match cached = cache.get(key);
        if (cached != null) return cached == NONE ? null : cached;
        Match m = matchNormalized(norm(key));
        cache.put(key, m == null ? NONE : m);
        return m;
    }

    public Match matchNormalized(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Compiled c : topics) {
            if (c.strong != null && c.strong.matcher(text).find()) {
                return new Match(STRONG, c.name, Collections.singletonList(c.id));
            }
        }
        Match found = null;
        List<String> ids = null;
        for (Compiled c : topics) {
            if (c.ambiguous != null && c.ambiguous.matcher(text).find()) {
                if (found == null) {
                    ids = new ArrayList<>();
                    found = new Match(AMBIGUOUS, c.name, ids);
                }
                ids.add(c.id);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- listas
    private static final Set<String> FILLER = new HashSet<>();
    static {
        String f = "o a os as de do da dos das e em no na nos nas um uma sobre quem que qual quando como "
                + "final finais fim spoiler spoilers morre morrem morte nao quero ver saber filme serie jogo game episodio "
                + "temporada ganha ganhou vence venceu acontece termina resultado of ending dies about spoil";
        Collections.addAll(FILLER, f.split(" "));
    }

    /** Sem IA: tira palavras de enchimento do começo e do fim ("o final de Stranger Things" -> "Stranger Things"). */
    public static List<String> fallbackKeywords(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        String[] w = t.split(" ");
        int a = 0;
        int b = w.length;
        while (a < b && isFiller(w[a])) a++;
        while (b > a && isFiller(w[b - 1])) b--;
        StringBuilder sb = new StringBuilder();
        for (int i = a; i < b; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w[i]);
        }
        String core = sb.toString().trim();
        List<String> out = new ArrayList<>();
        out.add(core.isEmpty() ? t : core);
        return cleanList(out);
    }

    private static boolean isFiller(String w) {
        return FILLER.contains(norm(w).replaceAll("[^\\p{L}\\p{N}]", ""));
    }

    /** Tira repetidas, vazias e grandes demais. */
    public static List<String> cleanList(List<String> in) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (in == null) return out;
        for (String k : in) {
            if (k == null) continue;
            k = k.replaceAll("\\s+", " ").trim();
            if (k.startsWith("#")) k = k.substring(1);
            if (k.length() < 2 || k.length() > 60) continue;
            String key = norm(k);
            if (seen.add(key)) out.add(k);
        }
        return out;
    }
}
