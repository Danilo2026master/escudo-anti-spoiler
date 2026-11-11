package br.escudo.antispoiler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Tela do app: ativar o Escudo, escrever o que não quer ver, escolher os apps e (opcional) a IA. */
public class MainActivity extends Activity {

    private TextView statusText;
    private TextView statusHelp;
    private Button activateBtn;
    private Switch masterSwitch;
    private EditText addText;
    private Button addBtn;
    private TextView addResult;
    private LinearLayout topicsList;
    private LinearLayout appsList;
    private EditText geminiKey;
    private Switch verifySwitch;
    private Switch blockAmbSwitch;
    private TextView testAiResult;

    private List<Topic> topics = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusHelp = findViewById(R.id.statusHelp);
        activateBtn = findViewById(R.id.activateBtn);
        masterSwitch = findViewById(R.id.masterSwitch);
        addText = findViewById(R.id.addText);
        addBtn = findViewById(R.id.addBtn);
        addResult = findViewById(R.id.addResult);
        topicsList = findViewById(R.id.topicsList);
        appsList = findViewById(R.id.appsList);
        geminiKey = findViewById(R.id.geminiKey);
        verifySwitch = findViewById(R.id.verifySwitch);
        blockAmbSwitch = findViewById(R.id.blockAmbSwitch);
        testAiResult = findViewById(R.id.testAiResult);

        activateBtn.setOnClickListener(v -> showActivationHelp());
        masterSwitch.setOnCheckedChangeListener((b, on) -> {
            Prefs.setEnabled(this, on);
            renderStatus();
        });
        addBtn.setOnClickListener(v -> addTopic());

        geminiKey.setText(Prefs.geminiKey(this));
        geminiKey.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                Prefs.sp(MainActivity.this).edit().putString(Prefs.K_GEMINI_KEY, s.toString().trim()).apply();
            }
        });
        verifySwitch.setChecked(Prefs.verify(this));
        verifySwitch.setOnCheckedChangeListener((b, on) -> Prefs.sp(this).edit().putBoolean(Prefs.K_VERIFY, on).apply());
        blockAmbSwitch.setChecked(Prefs.blockAmbiguousWithoutAI(this));
        blockAmbSwitch.setOnCheckedChangeListener((b, on) -> Prefs.sp(this).edit().putBoolean(Prefs.K_BLOCK_AMBIGUOUS, on).apply());
        findViewById(R.id.testAiBtn).setOnClickListener(v -> testAI());
        findViewById(R.id.keyLink).setOnClickListener(v -> openUrl("https://aistudio.google.com/apikey"));

        // 2.0: nível de proteção
        RadioButton blind = findViewById(R.id.modeBlindado);
        RadioButton post = findViewById(R.id.modePost);
        (Prefs.blindado(this) ? blind : post).setChecked(true);
        blind.setOnCheckedChangeListener((b, on) -> { if (on) Prefs.sp(this).edit().putString(Prefs.K_MODE, "blindado").apply(); renderStatus(); });
        post.setOnCheckedChangeListener((b, on) -> { if (on) Prefs.sp(this).edit().putString(Prefs.K_MODE, "post").apply(); renderStatus(); });
        bindFlag(R.id.skipSwitch, Prefs.K_SKIP);
        bindFlag(R.id.muteSwitch, Prefs.K_MUTE);
        bindFlag(R.id.searchSwitch, Prefs.K_BLOCK_SEARCH);
        bindFlag(R.id.ocrSwitch, Prefs.K_OCR);
        bindFlag(R.id.visionSwitch, Prefs.K_VISION);
        if (Build.VERSION.SDK_INT < 30) {
            Switch ocr = findViewById(R.id.ocrSwitch);
            ocr.setEnabled(false);
            ocr.setText("Ler texto dentro de imagens (precisa de Android 11 ou mais novo)");
            Switch vis = findViewById(R.id.visionSwitch);
            vis.setEnabled(false);
        }

        renderApps();
    }

    private void bindFlag(int id, final String key) {
        Switch s = findViewById(id);
        s.setChecked(Prefs.flag(this, key));
        s.setOnCheckedChangeListener((b, on) -> {
            Prefs.sp(this).edit().putBoolean(key, on).apply();
            if (Prefs.K_VISION.equals(key) && on && Prefs.geminiKey(this).isEmpty()) {
                Toast.makeText(this, "Cole a chave do Gemini lá embaixo para a IA visual funcionar.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        topics = Prefs.topics(this);
        masterSwitch.setChecked(Prefs.enabled(this));
        renderStatus();
        renderTopics();
    }

    // ---------------------------------------------------------------- ativação
    private boolean serviceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName cn = new ComponentName(this, SpoilerService.class);
        for (String s : list.split(":")) {
            ComponentName other = ComponentName.unflattenFromString(s);
            if (cn.equals(other)) return true;
        }
        return false;
    }

    private void renderStatus() {
        boolean on = serviceEnabled();
        if (on && Prefs.enabled(this)) {
            statusText.setText(Prefs.blindado(this) ? "✓ Escudo 2.0 ativo · modo blindado" : "✓ Escudo 2.0 ativo · modo leve");
            statusText.setTextColor(0xFF34D399);
            statusHelp.setText("Ele cobre os spoilers sozinho enquanto você usa os apps marcados abaixo.");
            activateBtn.setVisibility(View.GONE);
        } else if (on) {
            statusText.setText("⏸ Escudo pausado");
            statusText.setTextColor(0xFFFBBF24);
            statusHelp.setText("Ligue a chave abaixo para voltar a cobrir os spoilers.");
            activateBtn.setVisibility(View.GONE);
        } else {
            statusText.setText("⚠ Falta 1 passo: ativar o Escudo");
            statusText.setTextColor(0xFFFBBF24);
            statusHelp.setText("O Android precisa da sua permissão para o Escudo ler o que aparece na tela e cobrir os spoilers.");
            activateBtn.setVisibility(View.VISIBLE);
        }
    }

    private void showActivationHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Ativar o Escudo")
                .setMessage("1. Toque em ABRIR ACESSIBILIDADE.\n"
                        + "2. Procure \"Escudo Anti-Spoiler\" (às vezes fica em \"Apps instalados\" ou \"Serviços baixados\").\n"
                        + "3. Ligue e toque em Permitir.\n\n"
                        + "Apareceu \"Configuração restrita\"? É normal em apps instalados fora da Play Store (Android 13 ou mais novo):\n"
                        + "• Toque em LIBERAR, abra o menu ⋮ no canto de cima e escolha \"Permitir configurações restritas\".\n"
                        + "• Depois volte e repita os passos 1 a 3.")
                .setPositiveButton("Abrir Acessibilidade", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNeutralButton("Liberar", (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    // ---------------------------------------------------------------- temas
    private void addTopic() {
        final String text = addText.getText().toString().trim();
        if (text.isEmpty()) return;
        final String key = Prefs.geminiKey(this);
        final String model = Prefs.geminiModel(this);
        addBtn.setEnabled(false);
        addResult.setVisibility(View.VISIBLE);
        addResult.setTextColor(0xFFA39CBA);
        addResult.setText(key.isEmpty() ? "Adicionando…" : "🤖 A mini IA está montando a lista de palavras…");
        new Thread(() -> {
            Topic t;
            String msg;
            int color;
            try {
                if (key.isEmpty()) throw new Exception("sem chave");
                Gemini.Expansion e = Gemini.expand(key, model, text);
                t = new Topic(Topic.newId(), e.title, e.about.isEmpty() ? text : e.about, e.strong, e.ambiguous);
                msg = "✓ Bloqueando \"" + t.title + "\" com " + t.wordCount() + " palavras. Toque no tema para conferir.";
                color = 0xFF34D399;
            } catch (Exception ex) {
                t = new Topic(Topic.newId(), text, text, SpoilerMatcher.fallbackKeywords(text), new ArrayList<String>());
                msg = key.isEmpty()
                        ? "✓ Bloqueando \"" + t.strong.get(0) + "\". Dica: com a mini IA (abaixo) a lista fica bem mais completa."
                        : "IA indisponível (" + ex.getMessage() + "). Adicionei \"" + t.strong.get(0) + "\"; complete à mão.";
                color = key.isEmpty() ? 0xFF34D399 : 0xFFFBBF24;
            }
            final Topic ft = t;
            final String fm = msg;
            final int fc = color;
            runOnUiThread(() -> {
                topics = Prefs.topics(this);
                topics.add(ft);
                Prefs.saveTopics(this, topics);
                addText.setText("");
                addBtn.setEnabled(true);
                addResult.setTextColor(fc);
                addResult.setText(fm);
                renderTopics();
            });
        }).start();
    }

    private void renderTopics() {
        topicsList.removeAllViews();
        if (topics.isEmpty()) {
            topicsList.addView(muted("Nada ainda. Escreva acima o que você não quer ver."));
            return;
        }
        for (int i = 0; i < topics.size(); i++) {
            final Topic t = topics.get(i);
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(t.title);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            TextView meta = muted(t.wordCount() + (t.wordCount() == 1 ? " palavra" : " palavras") + " · toque para editar");
            info.addView(name);
            info.addView(meta);
            row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(t.enabled);
            sw.setOnCheckedChangeListener((b, on) -> {
                t.enabled = on;
                Prefs.saveTopics(this, topics);
            });
            row.addView(sw);
            row.setOnClickListener(v -> editTopic(idx));
            topicsList.addView(row);
        }
    }

    private void editTopic(final int idx) {
        final Topic t = topics.get(idx);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText title = field(box, "Nome do tema", t.title, false);
        final EditText about = field(box, "Do que se trata (ajuda a IA)", t.about, false);
        final EditText strong = field(box, "Palavras certeiras: cobrem na hora (uma por linha)", join(t.strong), true);
        final EditText amb = field(box, "Palavras duvidosas: a IA confere antes (uma por linha)", join(t.ambiguous), true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Editar tema")
                .setView(sv)
                .setPositiveButton("Salvar", (d, w) -> {
                    t.title = title.getText().toString().trim().isEmpty() ? "Sem nome" : title.getText().toString().trim();
                    t.about = about.getText().toString().trim();
                    t.strong = SpoilerMatcher.cleanList(lines(strong.getText().toString()));
                    t.ambiguous = SpoilerMatcher.cleanList(lines(amb.getText().toString()));
                    Prefs.saveTopics(this, topics);
                    renderTopics();
                })
                .setNeutralButton("Excluir", (d, w) -> new AlertDialog.Builder(this)
                        .setMessage("Excluir o tema \"" + t.title + "\"?")
                        .setPositiveButton("Excluir", (d2, w2) -> {
                            topics.remove(idx);
                            Prefs.saveTopics(this, topics);
                            renderTopics();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- apps
    private void renderApps() {
        appsList.removeAllViews();
        Map<String, Boolean> apps = Prefs.apps(this);
        for (Map.Entry<String, String> e : Prefs.APPS.entrySet()) {
            final String pkg = e.getKey();
            boolean installed = isInstalled(pkg);
            Switch sw = new Switch(this);
            sw.setText(e.getValue() + (installed ? "" : "  (não instalado)"));
            sw.setTextColor(installed ? Color.WHITE : 0xFF8A83A3);
            sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            sw.setPadding(0, dp(6), 0, dp(6));
            Boolean on = apps.get(pkg);
            sw.setChecked(on != null && on);
            sw.setOnCheckedChangeListener((b, checked) -> Prefs.setApp(this, pkg, checked));
            appsList.addView(sw);
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- IA
    private void testAI() {
        final String key = Prefs.geminiKey(this);
        final String model = Prefs.geminiModel(this);
        testAiResult.setVisibility(View.VISIBLE);
        if (key.isEmpty()) {
            testAiResult.setTextColor(0xFFFBBF24);
            testAiResult.setText("Cole a chave do Gemini primeiro.");
            return;
        }
        testAiResult.setTextColor(0xFFA39CBA);
        testAiResult.setText("Testando…");
        new Thread(() -> {
            String msg;
            int color;
            long t0 = System.currentTimeMillis();
            try {
                boolean[] r = Gemini.classify(key, model,
                        Arrays.asList("DOOMSDAY: explicando a cena pós-créditos", "Doomsday Clock: análise dos quadrinhos da DC"),
                        Arrays.asList("Vingadores: Doomsday — filme da Marvel"));
                msg = "✓ Funcionando (" + (System.currentTimeMillis() - t0) + " ms). Exemplo: \"cena pós-créditos\" → "
                        + (r[0] ? "spoiler" : "liberado") + "; \"quadrinhos da DC\" → " + (r[1] ? "spoiler" : "liberado") + ".";
                color = 0xFF34D399;
            } catch (Exception e) {
                msg = "✗ " + e.getMessage();
                color = 0xFFF87171;
            }
            final String fm = msg;
            final int fc = color;
            runOnUiThread(() -> {
                testAiResult.setTextColor(fc);
                testAiResult.setText(fm);
            });
        }).start();
    }

    // ---------------------------------------------------------------- utilidades
    private EditText field(LinearLayout box, String label, String value, boolean multi) {
        TextView l = muted(label);
        l.setPadding(0, dp(12), 0, dp(4));
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextColor(Color.WHITE);
        e.setBackgroundResource(R.drawable.input);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
            e.setMaxLines(8);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setSingleLine(true);
        }
        box.addView(e);
        return e;
    }

    private TextView muted(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFA39CBA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return t;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) {
            l = l.trim();
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
