## 1. Verificação da API do sherpa-onnx (design.md - Decisão 5)

- [x] 1.1 Confirmar a via de dependência: artefato AAR publicado num repositório Maven
      confiável, ou build a partir do código-fonte (NDK) do `k2-fsa/sherpa-onnx`. Registrar
      a decisão e a fonte usada para confirmar (documentação oficial, não busca web —
      mesmo cuidado já registrado no design.md de `add-audio-single-grammar-slice` para o
      Vosk).
- [x] 1.2 Inspecionar a superfície real da API Kotlin/Java do sherpa-onnx (`javap` sobre o
      `.aar`/`.jar`, ou leitura do binding Kotlin oficial se for build a partir do
      código-fonte): classes de VAD (Silero) e de reconhecimento (Whisper), formato de
      amostra esperado (assumir `FloatArray`, mas confirmar a escala — `±1.0` normalizado
      ou `±32767`, mesma armadilha já registrada para o Vosk), taxa de amostragem aceita,
      forma de carregar modelo a partir de `assets/`/armazenamento do app.
- [x] 1.3 Registrar em design.md (nova seção "Verificação da API do sherpa-onnx", mesmo
      formato usado para o Vosk) o que foi assumido versus o que foi confirmado, incluindo
      qualquer divergência encontrada.

## 2. Motor de ASR trocável (design.md - Decisão 1)

- [x] 2.1 Extrair interface `MotorDeAsr` com o essencial de hoje: carregar modelo(s) de
      forma assíncrona e, dado um fluxo de amostras normalizadas, produzir texto
      reconhecido (final) e, opcionalmente, hipótese parcial para log — espelhando o que
      `ReconhecedorDeComando` já faz com o Vosk hoje.
- [x] 2.2 Extrair a lógica atual do Vosk (carga do `Model`, criação de `Recognizer` por
      configuração de escuta, `setEndpointerDelays`, `acceptWaveForm`) para uma nova classe
      `MotorVosk` implementando `MotorDeAsr`, sem mudar comportamento observável.
- [x] 2.3 `ReconhecedorDeComando` passa a depender de `MotorDeAsr` (injetado), perdendo
      qualquer import direto de `org.vosk.*`. Thread dedicada, observação de estado,
      versionamento e publicação continuam como estão hoje — só a chamada ao motor muda de
      forma.
- [x] 2.4 `AppContainer` resolve qual `MotorDeAsr` instanciar em um ponto único, mesmo
      padrão de `fonteAudio`.
- [x] 2.5 Testes de JVM existentes de `ReconhecedorDeComando` (se houver) continuam
      passando com `MotorVosk` como motor de teste, sem alteração de asserts.
      **Não havia nenhum** — `ReconhecedorDeComando` depende de `Context`, `AudioRecord` e
      das bibliotecas nativas do Vosk, então nunca teve teste de JVM. O que a extração
      protege é a suíte que já existe em volta dele (`InterpretadorDeFala`,
      `SeletorDeEscuta`, `PublicadorDeVoz`): 209 testcases antes, 209 continuando a passar
      sem um assert alterado.

## 3. Motor sherpa-onnx (VAD + Whisper)

- [x] 3.1 Nova dependência Gradle/AAR do sherpa-onnx em `AgvPickVoice/app/build.gradle.kts`
      e `gradle/libs.versions.toml`, conforme resolvido na tarefa 1.1.
- [x] 3.2 Vendorizar o modelo Silero VAD (bundled no sherpa-onnx) e o modelo Whisper
      multilíngue tiny quantizado (design.md - Decisão 4) em `assets/`, seguindo o mesmo
      padrão de proveniência/versionamento já usado para o modelo Vosk.
- [x] 3.3 Implementar `MotorSherpaOnnx` (`MotorDeAsr`): carrega VAD + Whisper na
      inicialização (mesmo padrão `Deferred` do `MotorVosk`), consome o fluxo de amostras
      janela a janela alimentando o VAD, e ao detectar fim de fala decodifica o trecho
      acumulado com o Whisper.
- [x] 3.4 Confinamento de thread: toda chamada ao VAD/Whisper roda na mesma thread
      dedicada (`dispatcherAudio`) já usada pelo Vosk — sessões ONNX Runtime não são
      thread-safe (ver contexto do projeto).
- [x] 3.5 Normalização de pontuação/capitalização do texto devolvido pelo Whisper antes de
      entregá-lo ao restante do pipeline (design.md - Decisão 3), dentro de
      `MotorSherpaOnnx`/`ReconhecedorDeComando`, nunca em `InterpretadorDeFala`.
- [x] 3.6 `AjustesAsr`/`ajustes-asr.properties.exemplo` ganham os campos necessários para
      calibrar o VAD sem recompilar (design.md - Decisão 6), espelhando o padrão já
      existente para `silencioFinalMs`/`silencioAntesDaFalaMs`.

## 4. Testes de JVM

- [x] 4.1 Testes para a normalização de pontuação (tarefa 3.5) como função pura, incluindo
      os casos que o log de bancada do Vosk já mostrou ser comum ("Próximo.", "quarenta e
      sete,").
- [x] 4.2 Testes para `MotorVosk` continuando a satisfazer `MotorDeAsr` sem regressão
      (reaproveitando os testes já existentes de `ReconhecedorDeComando`, se houver).
      **Não é testável na JVM**: `MotorVosk` carrega `libvosk.so` e `StorageService` exige
      `Context`, exatamente como o código de onde ele foi extraído. A garantia de
      não-regressão aqui é a extração ter sido literal — mesma gramática, mesmo
      `setEndpointerDelays`, mesma escala `±32767`, mesmo parse de JSON — e a verificação
      real continua sendo a bancada da tarefa 6.2, que usa o `MotorVosk` como baseline.
- [x] 4.3 Testes de unidade possíveis para `MotorSherpaOnnx` que não dependam de hardware
      real de áudio (ex.: decodificar um WAV de fixture, se a API do sherpa-onnx permitir
      rodar fora do `AudioRecord` — depende do que a tarefa 1.2 confirmar).
      **Decodificar WAV de fixture na JVM não é possível**: `Vad` e `OfflineRecognizer` só
      têm construtor com `AssetManager`, e as duas classes chamam
      `System.loadLibrary("sherpa-onnx-jni")` — precisam de Android real ou de teste
      instrumentado, que este projeto não usa. O que foi extraído para ser testável de fato
      são as duas partes puras: `ReamostradorLinear` (9 testes) e `NormalizadorDeTextoAsr`
      (10 testes), que juntas cobrem os dois pontos onde um erro seria mudo — taxa errada
      chegando ao VAD e pontuação quebrando a igualdade exata do `InterpretadorDeFala`.

## 5. Verificação de build

- [x] 5.1 `./gradlew testDebugUnitTest` — contar os testes nos XMLs
      (`app/build/test-results/testDebugUnitTest/*.xml`), seguindo a prática já
      estabelecida neste projeto de não confiar só no resumo do Gradle.
- [x] 5.2 `./gradlew assembleDebug lintDebug` limpos, e registrar o tamanho final do APK de
      debug (referência: 150 MB antes desta mudança) — os dois motores (Vosk + sherpa-onnx)
      convivem no binário pela Decisão 1, então o tamanho deve subir; registrar quanto.
- [x] 5.3 Instalar em hardware real (`./gradlew installDebug`). Feito após a implementação,
      não pelo agente que a escreveu (sem aparelho no ambiente dele). Instalado num
      **Zebra TC21** (Android 13) — não o SM-G780F das medições antigas, o aparelho de
      bancada mudou nesta sessão. App abre sem crash (`adb logcat -b crash` vazio), motor
      ativo continua `MotorVosk` (log confirma `AjustesAsr`/`ReconhecedorDeComando`
      normais). Isso só confirma que o APK de 370 MB instala e roda sem regressão no
      caminho Vosk — nenhuma elocução real passou pelo `MotorSherpaOnnx` ainda, isso
      continua sendo o grupo 6.

## 6. Bancada (Matheus, com voz humana real)

- [ ] 6.1 Recalibrar o limiar de energia de referência para o motor novo, mesmo protocolo
      de [[reference-voz-bancada]] (voz humana direta no aparelho, nunca alto-falante) —
      os -27 dBFS medidos para o Vosk não têm por que valer aqui.
- [ ] 6.2 Repetir a bateria de 9 comandos usada na verificação original do Vosk
      (`add-audio-single-grammar-slice` - "Terceira rodada"), comparando taxa de acerto de
      primeira tentativa entre `MotorVosk` (baseline já documentado: 9/9) e
      `MotorSherpaOnnx`.
      **Smoke test exploratório em 18/08/2026, TC21, não a bateria formal:** algumas
      elocuções soltas em `OrdemCarregada`/`NavegandoParaEndereco`, sem protocolo. Com
      `degradarCanal=true` (default), o Whisper alucinou em todas — texto sem relação com a
      fala. Testada a hipótese de sinal fraco: `ganho=3.0` não mudou nada (o log de nível
      mede a janela antes do ganho, então não serve de termômetro pra essa variável).
      Testada a hipótese de canal degradado: `degradarCanal=false` (16 kHz cru, pico subiu
      de ~-25 para ~-14 dBFS) melhorou a proximidade do texto — palavras certas passaram a
      aparecer (`"iniciar"`, `"próximo"`) — mas sempre embutidas em texto extra
      alucinado antes/depois, nunca isoladas o bastante para bater a gramática exata. Motor
      de produção seguiu `MotorVosk`. Fica em aberto se `degradarCanal=false` deveria ser o
      default pro sherpa-onnx (a simulação de canal HFP não faz sentido sem óculos físico
      de qualquer forma) e se a bateria formal desta tarefa muda o resultado com elocuções
      isoladas e mais cuidado de protocolo.
- [ ] 6.3 Medir a latência entre fim da fala e evento publicado (spec `audio-source` desta
      mudança, cenário "Publicação não é instantânea ao fim da fala"), comparando contra o
      baseline do Vosk (460–770 ms, do parcial ao texto final).
- [ ] 6.4 Comparar Whisper tiny int8 contra base int8 na mesma bateria, decidindo se o
      default de produção sobe de tamanho (design.md - Decisão 4) — só muda se tiny não
      bater a taxa de acerto do baseline Vosk.
- [ ] 6.5 Testar especificamente os dois casos que motivaram esta troca em
      `add-voice-recognition-reliability`: check digit dígito a dígito (`AguardandoCheckDigit`)
      e "próximo" em avaria (`TratandoExcecao`), medindo se o motor novo resolve o que a
      calibração de gramática sobre o Vosk não conseguiu resolver de forma estável.
- [ ] 6.6 Calibrar os parâmetros de VAD introduzidos na tarefa 3.6 via
      `ajustes-asr.properties` (sem recompilar), documentando os valores finais em
      design.md, mesmo padrão de calibração já usado para `silencioFinalMs` do Vosk.
- [ ] 6.7 Decisão final registrada em design.md: motor de produção passa a ser
      `MotorSherpaOnnx` (troca o valor em `AppContainer`) ou permanece `MotorVosk` com o
      código novo guardado, sem uso, para retomada futura — de acordo com o resultado das
      tarefas 6.1–6.5.
      **Estado atual, à espera da bancada:** `AppContainer` instancia `MotorVosk`. O
      `MotorSherpaOnnx` está completo, compilado contra o AAR real e empacotado no APK, mas
      **sem nenhuma medição a favor dele** — trocar o padrão agora abandonaria um baseline de
      9/9 documentado por um desconhecido. A troca, quando 6.1–6.5 a justificarem, é uma
      linha: `MotorSherpaOnnx(appContext, ajustesAsr)`.

## 7. Specs e artefatos

- [x] 7.1 `openspec validate --strict` no change completo.
