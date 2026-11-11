# 🛡️ Escudo Anti-Spoiler 2.0 — app para Android

## Novidades da 2.0 (modo blindado)

- **Tela toda preta + som mudo** assim que um spoiler aparece em qualquer lugar da tela.
  A tela preta não segura o dedo: dá para continuar rolando "no escuro" até o spoiler sair.
  Embaixo aparecem os botões **Voltar**, **Pular** e **Mostrar**.
- **Reels, Shorts e TikTok com spoiler são pulados sozinhos** (até 8 seguidos; depois ele para e pede para você voltar).
- **Buscas bloqueadas**: se você (ou o teclado) digitar "GTA 6" na busca do YouTube/Instagram, o Escudo apaga na hora.
- **Reconhecimento por imagem** (Android 11 ou mais novo): o Escudo lê o texto que está *dentro* das imagens
  (miniaturas, capas, legendas no vídeo, textos em cima de Reels), no próprio celular, em fração de segundo.
- **IA visual (opcional)**: com a chave do Gemini, reconhece personagens, logos e cenas mesmo sem texto.
  Ela manda uma imagem pequena da tela para o Google a cada ~4 s, e só no YouTube, Instagram, Facebook, TikTok, X, Threads,
  Reddit e Kwai. Nunca no WhatsApp, Telegram, Discord ou Chrome. Vem desligada.
- Prefere o jeito antigo? Em **Nível de proteção**, escolha **Leve** (capa só no post).

> Se você instalou a 1.0, é só instalar a 2.0 por cima (as configurações continuam). Depois abra a Acessibilidade
> e confira se o Escudo continua ligado.

---

Cobre posts, vídeos, Reels, Shorts e comentários com spoiler dentro dos apps **YouTube, Instagram, Facebook**
(e também X, TikTok, Threads, Reddit e Kwai; dá para ligar Chrome, WhatsApp, Telegram e Discord).
Já vem configurado para **GTA 6** e **Vingadores: Doomsday**, e você pode adicionar o que quiser.

Por cima de cada post com spoiler aparece uma capa escura escrita "🛡️ Spoiler de GTA 6".
O feed continua rolando normalmente. Para ver um post, toque no botão **👁 Ver** da capa.

---

## Parte 1 — Gerar o APK no Android Studio (uma vez só)

1. **Instale o Android Studio**: baixe em **developer.android.com/studio** e instale clicando em
   *Next* em tudo (as opções padrão estão certas). Na primeira abertura, ele baixa umas coisas; espere.
2. **Descompacte** este .zip numa pasta fácil (ex.: `Documentos\escudo-anti-spoiler-android`).
3. No Android Studio: **File → Open** (ou "Open" na tela inicial) → escolha a pasta
   `escudo-anti-spoiler-android` → **OK**. Se perguntar se confia no projeto, clique em **Trust Project**.
4. **Espere a barra de baixo terminar** ("Gradle sync"). Na primeira vez demora de 5 a 15 minutos,
   porque ele baixa as ferramentas do Android.
   - Se aparecer um aviso pedindo para instalar o **Android SDK Platform 35** ou aceitar licenças, clique no link e aceite.
   - Se aparecer uma sugestão de "upgrade" do *Android Gradle Plugin*, pode aceitar ou ignorar: os dois funcionam.
5. Menu **Build → Generate App Bundles or APKs → Generate APKs**
   (em versões mais antigas: **Build → Build Bundle(s) / APK(s) → Build APK(s)**).
6. Quando terminar, aparece um aviso no canto: clique em **locate**. O arquivo é
   **`app-debug.apk`** (fica em `app\build\outputs\apk\debug\`).

> Atalho: se o celular estiver ligado no PC por cabo com a "Depuração USB" ativa, é só clicar no
> botão verde ▶ (Run) do Android Studio que ele instala direto.

## Parte 2 — Instalar no celular

1. Mande o `app-debug.apk` para o celular (cabo USB, Google Drive, ou WhatsApp para você mesmo).
2. No celular, toque no arquivo. Se pedir, permita **"Instalar apps desta fonte"**.
3. Se o Play Protect avisar "app desconhecido", toque em **Mais detalhes → Instalar mesmo assim**.
   O aviso aparece porque o app não veio da Play Store.

## Parte 3 — Ativar o Escudo

1. Abra o app **Escudo Anti-Spoiler** e toque em **Ativar agora → Abrir Acessibilidade**.
2. Procure **Escudo Anti-Spoiler** (às vezes fica em "Apps instalados" ou "Serviços baixados") e ligue.
3. **Apareceu "Configuração restrita"?** É normal no Android 13 ou mais novo com apps de fora da Play Store:
   - Volte ao app, toque em **Ativar agora → Liberar**.
   - Na tela de informações do app, toque no menu **⋮** (canto de cima) → **Permitir configurações restritas**.
   - Repita os passos 1 e 2.
4. Pronto: o app mostra **✓ Escudo ativo**.

**Xiaomi / Redmi / Poco:** em Configurações → Apps → Escudo Anti-Spoiler, ligue **Início automático** e
deixe a bateria **Sem restrições**. **Samsung:** em Bateria, coloque o Escudo em **Apps que nunca suspendem**.
Sem isso, o celular pode desligar o Escudo sozinho para economizar bateria.

## Usar

- **O que você não quer ver?**: escreva ("final de Stranger Things", "BBB", "One Piece") e toque em **Bloquear**.
- **Toque num tema** para editar as palavras ou excluir.
  - **Palavras certeiras** cobrem na hora.
  - **Palavras duvidosas** (que também são usadas para outras coisas, como "Doomsday") seguem a regra da IA.
- **Apps protegidos**: escolha em quais apps o Escudo age.
- **Proteção ligada**: desliga tudo por um tempo sem precisar mexer na Acessibilidade.

### Mini IA (opcional, grátis)

Com uma chave do Gemini (crie em **aistudio.google.com/apikey** → *Create API key* e cole no app):
- o app **monta a lista de palavras sozinho** quando você escreve um tema novo;
- as **palavras duvidosas** ficam cobertas com "🤖 Conferindo…" até a IA decidir se é spoiler.
  Se não for, a capa some sozinha.

Sem a chave, tudo funciona só com as palavras.

## Privacidade

O Escudo lê o texto que aparece na tela **só dos apps marcados**, para achar spoilers. Isso fica no celular.
A única coisa que sai do aparelho são os trechos com palavras duvidosas, e só se você colocar a chave do Gemini.

## Limites

- Ao rolar o feed muito rápido, a capa pode aparecer uma fração de segundo depois do post.
- A detecção é pelo texto (título, legenda, comentário, descrição). Uma imagem sem texto não tem como ser reconhecida.
- Quando um app muda o próprio layout, a capa pode ficar um pouco maior ou menor que o post.
- Só para Android 8 ou mais novo. No iPhone a Apple não permite esse tipo de app.
