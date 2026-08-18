## Context

Ver proposal.md - Why. Ponto de partida técnico, levantado antes de escrever este design:

- Pesquisa no model zoo do sherpa-onnx (k2-fsa): **não existe modelo de streaming (online)
  nativo para português**. A via real de pt-BR nesse toolkit é Whisper multilíngue
  exportado para ONNX, que é offline — decodifica um trecho de áudio já delimitado, não
  amostra a amostra como o Vosk fazia via `Recognizer.acceptWaveForm`.
- O Silero VAD que substitui o endpointer do Vosk já era esperado pela arquitetura do
  projeto desde o início: o contexto deste repositório já lista "Silero VAD (ONNX
  Runtime)" como parte da pilha de IA local, e já documenta que sessões ONNX Runtime **não
  são thread-safe** — a mesma restrição de confinamento de thread que hoje vale só para o
  `Model`/`Recognizer` do Vosk (`ReconhecedorDeComando`, `dispatcherAudio`) passa a valer
  também para o VAD e para o decodificador Whisper, sem mudar o padrão.
- `ReconhecedorDeComando` é hoje "a superfície do Vosk, e só ela" por design explícito
  (KDoc da classe, `add-audio-single-grammar-slice`): o resto do pipeline
  (`SeletorDeEscuta`, `InterpretadorDeFala`, `ResolvedorDeIntencao`, `PublicadorDeVoz`)
  não conhece Vosk, só consome `PickingEvent`/texto. Essa fronteira é o que torna a troca
  de motor uma mudança localizada em vez de uma reescrita do pipeline inteiro.
- `InterpretadorDeFala.interpretar` já faz comparação **exata** de string contra as
  constantes de `VocabularioDeVoz` (`normalizado == VocabularioDeVoz.PROXIMO`, etc.), após
  só `trim().lowercase()`. Isso já era, na prática, uma validação pós-reconhecimento — o
  Vosk é que também restringia as hipóteses do decodificador (gramática JSON), então a
  rejeição de fala fora do contrato tinha duas camadas redundantes. Com Whisper, só a
  camada que já existe em `InterpretadorDeFala` continua existindo.
- O histórico de bancada de `add-voice-recognition-reliability` (17–18/08/2026, ainda não
  commitado) documenta duas rodadas de calibração de gramática em direções opostas, nenhuma
  estável: ampliar o vocabulário do check digit para aceitar extenso piorou o dígito a
  dígito; restringir só a dígito a dígito trouxe de volta reconhecimento parcial (só um
  algarismo por elocução). Esse histórico é o dado concreto que motiva não insistir em mais
  ajuste de gramática sobre o mesmo modelo acústico.
- `AudioHfpOculos` foi implementado, verificado e **revertido no mesmo dia** (`0c5d39b` →
  `05fcb2f`) simplesmente trocando uma linha em `AppContainer`, porque `FonteAudio` é uma
  interface e as duas implementações convivem no código. Esse é o padrão de reversibilidade
  que este design segue para o motor de ASR (Decisão 1).

## Goals / Non-Goals

**Goals:**
- Trocar o decodificador de comando de voz de Vosk para sherpa-onnx (Whisper multilíngue +
  Silero VAD embutido), mantendo o contrato observável do pipeline: os mesmos
  `PickingEvent`s, a mesma fronteira de responsabilidade entre `SeletorDeEscuta` (o que
  pode ser dito), `InterpretadorDeFala` (o que significa) e `ReconhecedorDeComando` (a
  superfície do motor).
- Deixar a troca de motor reversível em uma linha, mesmo padrão já usado para
  `FonteAudio`/`AudioHfpOculos`, dado que não há ainda nenhuma medição de bancada com o
  motor novo.
- Antecipar o Silero VAD do roadmap ("Marco 2") como consequência da troca, não como
  trabalho extra.

**Non-Goals:**
- Reranking contra a expectativa do mock (doc §5, última etapa do diagrama) — continua
  fora do escopo, como já era antes desta mudança.
- Mudar o texto de `VocabularioDeVoz`/`SeletorDeEscuta` (quais palavras cada estado
  aceita) — isso é conteúdo do change `add-voice-recognition-reliability`, independente
  deste. Este change troca o motor que produz o texto a ser validado, não o vocabulário
  em si.
- Calibrar os parâmetros do VAD/Whisper em bancada — os valores desta proposta são ponto
  de partida documentado, não resultado medido; bancada é tarefa de `tasks.md`, como toda
  calibração deste projeto.
- Suporte a mais de um idioma simultâneo — o modelo Whisper é multilíngue por
  característica do modelo, não porque o app precisa detectar idioma; a app continua
  assumindo pt-BR.

## Decisions

### Decisão 1: Motor de reconhecimento vira uma interface trocável, Vosk não é apagado

Alternativa considerada e rejeitada: remover o código do Vosk (dependência, modelo em
`assets/`, lógica de `ReconhecedorDeComando`) por completo nesta mudança. Rejeitada porque
não há ainda nenhuma medição de bancada do motor novo, e o projeto já tem um precedente
direto do custo de not ter um caminho de volta: `AudioHfpOculos` foi revertido no mesmo dia
em que foi implementado, só porque `FonteAudio` já era uma interface — a reversão foi trocar
uma linha em `AppContainer`, não desfazer um commit.

`ReconhecedorDeComando` passa a depender de uma nova interface `MotorDeAsr` (nome sujeito a
ajuste na implementação), com dois métodos essenciais — carregar o(s) modelo(s) e, dado um
fluxo de amostras, produzir texto reconhecido — e duas implementações:
- `MotorVosk`: o que hoje está dentro de `ReconhecedorDeComando` (carga do `Model`,
  `Recognizer` com gramática, endpoint embutido), extraído sem mudança de comportamento.
- `MotorSherpaOnnx`: Silero VAD + Whisper, implementação nova desta mudança.

`AppContainer` escolhe qual motor instanciar em uma linha, mesmo padrão de `fonteAudio`. O
resto de `ReconhecedorDeComando` (thread dedicada, observação de estado, publicação,
logging) não muda, porque já não sabia que o motor era Vosk especificamente — só chamava a
API do Vosk diretamente. Esta mudança formaliza essa fronteira em vez de inaugurá-la.

### Decisão 2: Validação de gramática por estado continua em `InterpretadorDeFala`, sem componente novo

`InterpretadorDeFala.interpretar` já compara o texto normalizado contra as constantes de
`VocabularioDeVoz` com igualdade exata — já é, na prática, uma validação pós-reconhecimento.
A única camada que deixa de existir é a restrição do **decodificador** (a gramática JSON que
o Vosk recebia no construtor do `Recognizer`); a camada que decide se o texto reconhecido
"conta" continua sendo exatamente a mesma função pura de hoje, sem mudança de assinatura ou
de comportamento.

Alternativa considerada: mover a validação para dentro do motor (`MotorSherpaOnnx` já
filtra hipóteses contra o vocabulário do estado antes de devolver texto). Rejeitada — faria
o motor precisar conhecer `SeletorDeEscuta`/`PickingState`, quebrando a mesma fronteira que
a Decisão 1 acabou de formalizar (`ReconhecedorDeComando`/motor não sabem de domínio); e
duplicaria uma comparação que `InterpretadorDeFala` já faz corretamente.

### Decisão 3: Normalização de pontuação entra na fronteira do motor, não em `InterpretadorDeFala`

Decodificadores de vocabulário aberto como o Whisper tendem a devolver texto pontuado
("Próximo.", "quarenta e sete,") — o Vosk de gramática fechada nunca produzia pontuação,
porque as únicas hipóteses possíveis eram as palavras da gramática. Sem tratar isso, toda
igualdade exata em `InterpretadorDeFala` passaria a falhar por causa de um ponto final.

A remoção de pontuação (e qualquer outra normalização específica do motor, como
capitalização de início de frase) é responsabilidade de `MotorSherpaOnnx`/`ReconhecedorDeComando`
antes de entregar o texto a `InterpretadorDeFala` — a mesma fronteira que já existe hoje
para outras peculiaridades de decodificador (o JSON do Vosk, por exemplo, nunca vaza para
fora de `ReconhecedorDeComando`). `InterpretadorDeFala` continua recebendo uma `String` já
normalizada de espaços e agora também de pontuação, sem precisar saber que o motor mudou.

Alternativa considerada: strip de pontuação dentro de `InterpretadorDeFala.interpretar`.
Rejeitada — pontuação é uma característica do decodificador (o Vosk nunca a produziu), não
do domínio; colocar esse tratamento na função pura acoplaria seu comportamento a um detalhe
de motor específico.

### Decisão 4: Modelo Whisper multilíngue tiny quantizado (int8) como ponto de partida

Entre os tamanhos publicados de Whisper (tiny ~39M parâmetros, base ~74M, ambos
multilíngues), a escolha inicial é **tiny int8**, pelo mesmo motivo que levou o modelo Vosk
a ser o menor pt disponível (design.md de `add-audio-single-grammar-slice`, Decisão 5): o
APK de debug já é grande (150 MB, por causa do ML Kit bundled do pipeline de visão) e o
vocabulário reconhecido por comando é curto (uma palavra, dois dígitos) — não há indício de
que o modelo maior seja necessário para esse tipo de elocução curta, e cada MB a mais custa
tempo de `installDebug` na bancada que falta até 18/09.

Isso é uma hipótese de partida, não uma medição — `tasks.md` inclui a tarefa de bancada que
compara tiny contra base na mesma bateria de comandos que `add-audio-single-grammar-slice`
já usou para o Vosk (9 comandos, primeira tentativa, dBFS de pico), e o default só muda
para base se tiny não bater a taxa de acerto que o Vosk já tinha.

Alternativa considerada: baixar o modelo em tempo de execução em vez de vendorizar em
`assets/`. Rejeitada pelo mesmo motivo já registrado para o Vosk — sem dependência de rede
nesta camada, e um passo de setup que pode falhar na manhã do evento não é aceitável.

### Decisão 5: Verificar a API do sherpa-onnx antes de escrever qualquer código de integração

A pesquisa que embasa esta proposta encontrou referências de dependência Gradle
(`com.github.k2-fsa:sherpa-onnx-android`) e de superfície Kotlin em buscas na web, não em
documentação oficial lida diretamente — o mesmo tipo de fonte que, para o Vosk, já se
mostrou não confiável nesta base de código (design.md de `add-audio-single-grammar-slice`
registra duas suposições erradas sobre o artefato Maven do Vosk, corrigidas só depois de
`javap` sobre o `.aar` real). Antes de qualquer código de `MotorSherpaOnnx`, `tasks.md`
inclui uma tarefa de verificação da API real do sherpa-onnx (artefato publicado vs. build a
partir do código-fonte, assinaturas de VAD/reconhecimento, formato de amostra esperado —
provavelmente `FloatArray` normalizado, dado que é ONNX Runtime e não Kaldi, mas isso
também precisa ser confirmado, não assumido, pela mesma razão que a escala `±32767` do Vosk
custou uma rodada inteira de bancada quando foi assumida errada).

### Decisão 6: Parâmetros de tempo do VAD partem dos valores já calibrados para o Vosk, sujeitos a nova bancada

`PerfilEndpoint`/`AjustesAsr.silencioFinalMs` continuam existindo como a configuração de
quanto silêncio fecha uma elocução — o mesmo conceito se aplica ao VAD, trocando "quando o
`Recognizer` do Vosk decide que a elocução acabou" por "quando o Silero VAD decide". Os
valores atuais (280 ms para comando curto, 700 ms para dígitos) são o ponto de partida, não
a calibração final: foram medidos contra o comportamento específico do endpointer do Vosk
(design.md de `add-audio-single-grammar-slice`, "Terceira rodada"), e nada garante que o
VAD feche a elocução no mesmo instante para o mesmo áudio. `AjustesAsr` ganha os campos
necessários para calibrar o VAD sem recompilar, seguindo o mecanismo já existente
(`ajustes-asr.properties`, `adb push` + `force-stop`).

## Risks / Trade-offs

- **[Risco]** Sem medição prévia, não há garantia de que Whisper tiny + VAD supere o Vosk
  em accuracy no ambiente ruidoso do galpão — a motivação desta troca é a hipótese de que o
  teto está no modelo acústico do Vosk, mas isso só se confirma em bancada. →
  **Mitigação**: Decisão 1 (motor reversível em uma linha) garante que, se a bancada mostrar
  piora, a volta para Vosk custa uma linha em `AppContainer`, não um revert de commit.
- **[Risco]** Whisper offline (VAD-then-ASR) tem latência de fim-de-fala até evento maior e
  menos previsível que o streaming do Vosk, que já decodificava enquanto o operador falava.
  → **Mitigação**: modelo tiny quantizado é escolhido justamente para minimizar tempo de
  inferência; a tarefa de bancada mede a latência real (ver spec `audio-source` desta
  mudança, cenário "Publicação não é instantânea ao fim da fala") antes de qualquer
  julgamento sobre usabilidade.
- **[Risco]** Dependência nova (sherpa-onnx) sem verificação prévia da API real, mesma classe
  de risco que já aconteceu com o Vosk (duas suposições erradas sobre o artefato Maven). →
  **Mitigação**: Decisão 5, tarefa de verificação dedicada antes de qualquer código de
  integração, mesma prática (`javap`/inspeção do artefato real) já validada neste projeto.
- **[Risco]** APK de debug cresce mais ainda (já em 150 MB) com os modelos Whisper + Silero
  VAD somados aos do Vosk, que continuam no repositório pela Decisão 1. → **Mitigação**:
  modelo tiny quantizado é a menor opção plausível; se o tamanho combinado se mostrar
  proibitivo na bancada, remover o Vosk deixa de ser opcional e vira uma tarefa de limpeza
  subsequente — não bloqueia esta mudança, mas fica registrado como consequência possível.
- **[Risco]** Toda a calibração de bancada registrada em [[reference-voz-bancada]] (limiar
  de -27 dBFS, protocolo de teste) foi medida contra o Vosk e pode não valer para o motor
  novo. → **Mitigação**: `tasks.md` trata a bancada deste change como uma calibração do
  zero, não como reaproveitamento dos números antigos — mesma disciplina que o projeto já
  aplicou toda vez que um componente de áudio mudou (ex.: `AudioHfpOculos`, que pedia
  remedição do mesmo limiar).

## Verificação da API do sherpa-onnx

Executada em 18/08/2026 (tarefas 1.1–1.3), antes de qualquer código de `MotorSherpaOnnx`,
seguindo a Decisão 5. Mesmo formato do registro que o Vosk já tem em
`add-audio-single-grammar-slice`: o que era suposição, o que foi confirmado, e contra qual
artefato real.

**Método.** `javap` sobre o `classes.jar` do `.aar` oficial baixado, cruzado com o
código-fonte Kotlin e C++ do repositório no **mesmo tag** (`v1.13.5`). Nenhuma afirmação
abaixo vem de busca web ou de memória — cada uma tem um arquivo real por trás.

### 1.1 Via de dependência — CONFIRMADO, e diferente do que a proposta supunha

| | |
|---|---|
| Suposição da proposta | `com.github.k2-fsa:sherpa-onnx-android` num repositório Maven |
| Confirmado | **Não existe no Maven Central.** Busca na API do Central por `sherpa` devolve 12 artefatos, nenhum do k2-fsa |
| Artefato real | `sherpa-onnx-1.13.5.aar`, publicado como *release asset* no GitHub |
| Origem | <https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar> |
| Tamanho | 49.095.090 bytes (4 ABIs: arm64-v8a 30 MB, armeabi-v7a 21 MB, x86 35 MB, x86_64 34 MB) |
| SHA-256 | `6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b` |
| Licença | Apache-2.0 |

O JitPack (`com.github.k2-fsa:sherpa-onnx`) **tem** builds marcados `ok` para a v1.13.5,
mas os módulos que ele publica são os de JVM/desktop (`sherpa-onnx-jvm`,
`sherpa-onnx-native-lib-linux-*`, `-osx-*`, `-win-*`) — **não o AAR de Android**. Não serve.

**Decisão: vendorizar o `.aar` em `AgvPickVoice/app/libs/` e depender dele por arquivo**,
não por coordenada Maven. É o mesmo raciocínio da Decisão 4 sobre baixar modelo em runtime,
aplicado à dependência: um `git clone` que já compila vale o espaço, e um passo de download
que pode falhar na manhã de 18/09 não é aceitável. Compilar do código-fonte com NDK, a outra
via que a Open Question levantava, fica descartada — existe binário oficial, não há motivo.

### 1.2 Superfície da API — CONFIRMADA

Classes reais (`com.k2fsa.sherpa.onnx`), assinaturas verificadas por `javap`:

- **VAD**: `Vad(AssetManager?, VadModelConfig)`, com `acceptWaveform(FloatArray)`,
  `empty(): Boolean`, `front(): SpeechSegment`, `pop()`, `clear()`, `reset()`, `flush()`,
  `isSpeechDetected(): Boolean`, `release()`. `SpeechSegment(val start: Int, val samples: FloatArray)`.
- **Reconhecimento**: `OfflineRecognizer(AssetManager?, OfflineRecognizerConfig)`, com
  `createStream(): OfflineStream`, `decode(OfflineStream)`, `getResult(OfflineStream): OfflineRecognizerResult`.
  `OfflineStream.acceptWaveform(FloatArray, Int)` — o `Int` é a taxa de amostragem.
  `OfflineRecognizerResult.text: String`.
- **Whisper**: `OfflineWhisperModelConfig(encoder, decoder, language = "en", task = "transcribe",
  tailPaddings = 1000, ...)` dentro de `OfflineModelConfig(whisper = ..., tokens = ..., modelType = "whisper")`.
  `language` é onde entra `"pt"`.

**Escala das amostras: `±1.0` normalizado — CONFIRMADO, e é o oposto do Vosk.** O
`offline-stream.h` documenta literalmente "the range [-1, 1]" sobre `AcceptWaveform`. Ou
seja, o contrato de `FonteAudio` (`-1.0..1.0`) já é exatamente o que o sherpa-onnx quer:
**a constante `ESCALA_INT16` que o Vosk exigia não tem equivalente aqui**, e aplicá-la por
inércia produziria o mesmo tipo de falha silenciosa que custou uma rodada de bancada em
`add-audio-single-grammar-slice`, só que na direção contrária (saturação em vez de silêncio).
Era a armadilha que a Decisão 5 mandava conferir; conferida.

**Carga a partir de `assets/`: direta.** Tanto `Vad` quanto `OfflineRecognizer` têm
construtor que recebe `AssetManager` e resolvem caminhos relativos dentro de `assets/`. Não
há nada como o `StorageService.sync` do Vosk — **nenhuma cópia de 51 MB para
`getExternalFilesDir` na primeira execução, e nenhum arquivo `uuid` para manter**. Um passo a
menos que o modelo Vosk tinha.

### 1.3 Divergências encontradas — três, e duas mudam o desenho

**(a) O VAD exige 16 kHz e a fonte de áudio deste projeto entrega 8 kHz.**
`silero-vad-model.cc` rejeita qualquer outra taxa: `if (sample_rate_ != 16000) { LOGE(...);
SHERPA_ONNX_EXIT(-1); }`. A `FonteAudio` deste projeto declara **8000 Hz** nas duas
implementações — é a taxa do canal HFP do óculos (doc §2.1), reproduzida de propósito pela
`DegradacaoCanalTelefonico` para que a calibração transfira para o hardware real. Isso não
era conhecido quando a proposta foi escrita.

Consequência: `MotorSherpaOnnx` **precisa reamostrar 8 kHz → 16 kHz antes do VAD**. Não é
opcional nem contornável por configuração. O reconhecedor, ao contrário do VAD, reamostra
sozinho (`offline-stream.cc` cria um `LinearResample` quando a taxa recebida difere da do
`FeatureConfig`), mas como o trecho já sai do VAD em 16 kHz ele é entregue nessa taxa e
nenhuma reamostragem dupla acontece.

Vale registrar o limite físico por baixo disso: um sinal que passou por 8 kHz não tem
conteúdo acima de 4 kHz, e reamostrar não devolve o que a decimação tirou. O Whisper foi
treinado em 16 kHz de banda cheia. **A troca de motor não elimina essa perda** — ela vem do
canal HFP, não do decodificador —, o que é mais um motivo para a bancada da tarefa 6.2
comparar contra o baseline do Vosk em vez de assumir melhora.

**(b) Erro de configuração mata o processo, não lança exceção.**
`SHERPA_ONNX_EXIT(code)` expande para `_Exit(code)`. Taxa errada, `window_size` errado,
modelo não reconhecido, metadado ausente — todos terminam o processo **imediatamente**, sem
exceção Java, sem `try`/`catch`, sem stack trace, sem chance de o app degradar em silêncio
como faz hoje quando o Vosk falha ao carregar. Um `runCatching` em volta não protege nada.

Consequência: `MotorSherpaOnnx` valida em Kotlin, **antes** de qualquer chamada nativa, tudo
o que o lado C++ trataria com `_Exit` — presença dos arquivos de modelo em `assets/` e taxa
de amostragem efetivamente igual a 16000. Isso preserva a garantia da Decisão 6 do
`add-audio-single-grammar-slice` (falha de ASR não derruba o app, o painel de dev segue
servindo), que de outro modo estaria perdida.

**(c) O tamanho do Whisper tiny int8 não é o que a Decisão 4 supunha.**
Medido no pacote oficial `sherpa-onnx-whisper-tiny.tar.bz2` (multilíngue, 116 MB compactado):

| Arquivo | Tamanho |
|---|---|
| `tiny-encoder.int8.onnx` | 12.937.772 B (12,3 MB) |
| `tiny-decoder.int8.onnx` | **89.855.401 B (85,7 MB)** |
| `tiny-tokens.txt` | 816.730 B (0,8 MB) |
| **total vendorizado** | **~99 MB** |
| `silero_vad.onnx` | 643.854 B (0,6 MB) |

A Decisão 4 escolheu tiny sobre base argumentando tamanho ("cada MB a mais custa tempo de
`installDebug`"). A escolha continua certa — base é ~2× isso —, mas **a premissa de que
tiny seria pequeno está errada por uma ordem de grandeza**. O decodificador multilíngue
carrega o vocabulário de 51.865 tokens do Whisper, e é ele, não o encoder, que domina: 86 dos
99 MB. Somado ao modelo Vosk que a Decisão 1 mantém no binário, o APK de debug sai dos 150 MB
atuais para a faixa dos 250 MB.

Isso **não invalida** a Decisão 1 (motor reversível) nem a Decisão 4 (tiny como ponto de
partida) — mas transforma o "[Risco] APK de debug cresce mais ainda" de risco em fato medido,
e antecipa a consequência que aquele risco já previa: se a bancada aprovar o
`MotorSherpaOnnx`, remover o Vosk deixa de ser opcional.

### Tamanho do APK de debug — medido, e é o ponto mais duro desta mudança

Medido depois da integração completa (tarefa 5.2), com os dois motores no binário pela
Decisão 1:

| Build | APK de debug |
|---|---|
| Antes desta mudança | 150 MB |
| **Depois, como está no código** | **370 MB** |
| Depois, se o build filtrasse ABI para só `arm64-v8a` | 223 MB |

Os +220 MB são ~99 MB de modelos (gravados sem compressão, por `noCompress`, para o ONNX
Runtime poder ler o asset mapeado em vez de descomprimir 86 MB para a RAM a cada carga) e
~120 MB de bibliotecas nativas — o AAR traz `libsherpa-onnx-jni.so`, `libonnxruntime.so` e
companhia para **quatro** ABIs (arm64-v8a, armeabi-v7a, x86, x86_64).

A linha do meio da tabela é uma **medição, não uma mudança aplicada**: o build continua
empacotando as quatro ABIs, como sempre fez. Filtrar para `arm64-v8a` — a ABI do SM-G780F de
bancada — devolveria 147 MB, mas é decisão de escopo próprio: mexe em todas as bibliotecas
nativas do app (Vosk, ML Kit, SDK da Meta), e tiraria o emulador x86_64 da mesa. Fica
registrado como a alavanca disponível, para ser puxada por quem decidir, não por este change.

Isso realiza o "[Risco] APK de debug cresce mais ainda" com número real e torna concreta a
consequência que ele já previa: a 370 MB, cada `installDebug` custa vários minutos, e o tempo
de bancada que resta até 18/09 é justamente o recurso escasso. **Se a bancada aprovar o
`MotorSherpaOnnx`, remover o Vosk deixa de ser opcional.**

### O que continua sendo suposição, não medição

- Que Whisper tiny int8 reconhece os comandos deste projeto melhor que o Vosk — é a hipótese
  inteira da mudança, e só a tarefa 6.2 responde.
- Os parâmetros de VAD (`threshold` 0.5, `minSilenceDuration`, `minSpeechDuration`,
  `windowSize` 512) partem dos defaults do próprio sherpa-onnx e dos tempos já calibrados para
  o Vosk (Decisão 6). Nenhum foi medido com voz humana neste pipeline.
- A qualidade da reamostragem 8→16 kHz escrita para o item (a) — é interpolação linear, a
  opção mais simples que atende o contrato; se a bancada mostrar que o VAD dispara errado,
  é o primeiro lugar a olhar.

## Open Questions

- ~~Artefato do sherpa-onnx: existe um AAR publicado em um repositório Maven confiável?~~
  **Respondida** pela verificação acima: não há artefato Maven; há AAR oficial em GitHub
  Releases, vendorizado em `app/libs/`.
- A perda de banda do canal HFP (8 kHz, item (a) da verificação) limita qualquer decodificador
  treinado em 16 kHz, o Whisper inclusive. Se a bancada mostrar que o `MotorSherpaOnnx` não
  supera o Vosk, a pergunta seguinte deixa de ser "qual modelo" e passa a ser "dá para não
  degradar o canal" — o que depende do que o HFP do óculos real entrega, e não de escolha de
  motor. Fora do escopo desta mudança, mas é onde a investigação continua se 6.2 decepcionar.
