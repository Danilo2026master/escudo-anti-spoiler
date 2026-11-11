package br.escudo.antispoiler;

import java.util.ArrayList;
import java.util.List;

/** Um tema que a pessoa não quer ver (ex.: "GTA 6"). */
public class Topic {
    public String id;
    public String title;
    public String about;
    public boolean enabled = true;
    /** Palavras certeiras: cobrem na hora. */
    public List<String> strong = new ArrayList<>();
    /** Palavras duvidosas: a IA confere antes (ou seguem a regra "sem IA"). */
    public List<String> ambiguous = new ArrayList<>();

    public Topic() { }

    public Topic(String id, String title, String about, List<String> strong, List<String> ambiguous) {
        this.id = id;
        this.title = title;
        this.about = about;
        this.strong = new ArrayList<>(strong);
        this.ambiguous = new ArrayList<>(ambiguous);
    }

    public int wordCount() {
        return strong.size() + ambiguous.size();
    }

    public static String newId() {
        return "t" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
    }
}
