## 1. Verificação da API do sherpa-onnx (design.md - Decisão 5)

- [ ] 1.1 Confirmar a via de dependência: artefato AAR publicado num repositório Maven
      confiável, ou build a partir do código-fonte (NDK) do `k2-fsa/sherpa-onnx`. Registrar
      a decisão e a fonte usada para confirmar (documentação oficial, não busca web —
      mesmo cuidado já registrado no design.md de `add-audio-single-grammar-slice` para o
      Vosk).
- [ ] 1.2 Inspecionar a superfície real da API Kotlin/Java do sherpa-onnx (`javap` sobre o
      `.aar`/`.jar`, ou leitura do binding Kotlin oficial se for build a partir do
      código-fonte): classes de VAD (Silero) e de reconhecimento (Whisper), formato de
      amostra esperado (assumir `FloatArray`, mas confirmar a escala — `±1.0` normalizado
      ou `±32767`, mesma armadilha já registrada para o Vosk), taxa de amostragem aceita,
      forma de carregar modelo a partir de `assets/`/armazenamento do app.
- [ ] 1.3 Registrar em design.md (nova seção "Verificação da API do sherpa-onnx", mesmo
      formato usado para o Vosk) o que foi assumido versus o que foi confirmado, incluindo
      qualquer divergência encontrada.

## 2. Motor de ASR trocável (design.md - Decisão 1)

- [ ] 2.1 Extrair interface `MotorDeAsr` com o essencial de hoje: carregar modelo(s) de
      forma assíncrona e, dado um fluxo de amostras normalizadas, produzir texto
      reconhecido (final) e, opcionalmente, hipótese parcial para log — espelhando o que
      `ReconhecedorDeComando` já faz com o Vosk hoje.
- [ ] 2.2 Extrair a lógica atual do Vosk (carga do `Model`, criação de `Recognizer` por
      configuração de escuta, `setEndpointerDelays`, `acceptWaveForm`) para uma nova classe
      `MotorVosk` implementando `MotorDeAsr`, sem mudar comportamento observável.
- [ ] 2.3 `ReconhecedorDeComando` passa a depender de `MotorDeAsr` (injetado), perdendo
      qualquer import direto de `org.vosk.*`. Thread dedicada, observação de estado,
      versionamento e publicação continuam como estão hoje — só a chamada ao motor muda de
      forma.
- [ ] 2.4 `AppContainer` resolve qual `MotorDeAsr` instanciar em um ponto único, mesmo
      padrão de `fonteAudio`.
- [ ] 2.5 Testes de JVM existentes de `ReconhecedorDeComando` (se houver) continuam
      passando com `MotorVosk` como motor de teste, sem alteração de asserts.

## 3. Motor sherpa-onnx (VAD + Whisper)

- [ ] 3.1 Nova dependência Gradle/AAR do sherpa-onnx em `AgvPickVoice/app/build.gradle.kts`
      e `gradle/libs.versions.toml`, conforme resolvido na tarefa 1.1.
- [ ] 3.2 Vendorizar o modelo Silero VAD (bundled no sherpa-onnx) e o modelo Whisper
      multilíngue tiny quantizado (design.md - Decisão 4) em `assets/`, seguindo o mesmo
      padrão de proveniência/versionamento já usado para o modelo Vosk.
- [ ] 3.3 Implementar `MotorSherpaOnnx` (`MotorDeAsr`): carrega VAD + Whisper na
      inicialização (mesmo padrão `Deferred` do `MotorVosk`), consome o fluxo de amostras
      janela a janela alimentando o VAD, e ao detectar fim de fala decodifica o trecho
      acumulado com o Whisper.
- [ ] 3.4 Confinamento de thread: toda chamada ao VAD/Whisper roda na mesma thread
      dedicada (`dispatcherAudio`) já usada pelo Vosk — sessões ONNX Runtime não são
      thread-safe (ver contexto do projeto).
- [ ] 3.5 Normalização de pontuação/capitalização do texto devolvido pelo Whisper antes de
      entregá-lo ao restante do pipeline (design.md - Decisão 3), dentro de
      `MotorSherpaOnnx`/`ReconhecedorDeComando`, nunca em `InterpretadorDeFala`.
- [ ] 3.6 `AjustesAsr`/`ajustes-asr.properties.exemplo` ganham os campos necessários para
      calibrar o VAD sem recompilar (design.md - Decisão 6), espelhando o padrão já
      existente para `silencioFinalMs`/`silencioAntesDaFalaMs`.

## 4. Testes de JVM

- [ ] 4.1 Testes para a normalização de pontuação (tarefa 3.5) como função pura, incluindo
      os casos que o log de bancada do Vosk já mostrou ser comum ("Próximo.", "quarenta e
      sete,").
- [ ] 4.2 Testes para `MotorVosk` continuando a satisfazer `MotorDeAsr` sem regressão
      (reaproveitando os testes já existentes de `ReconhecedorDeComando`, se houver).
- [ ] 4.3 Testes de unidade possíveis para `MotorSherpaOnnx` que não dependam de hardware
      real de áudio (ex.: decodificar um WAV de fixture, se a API do sherpa-onnx permitir
      rodar fora do `AudioRecord` — depende do que a tarefa 1.2 confirmar).

## 5. Verificação de build

- [ ] 5.1 `./gradlew testDebugUnitTest` — contar os testes nos XMLs
      (`app/build/test-results/testDebugUnitTest/*.xml`), seguindo a prática já
      estabelecida neste projeto de não confiar só no resumo do Gradle.
- [ ] 5.2 `./gradlew assembleDebug lintDebug` limpos, e registrar o tamanho final do APK de
      debug (referência: 150 MB antes desta mudança) — os dois motores (Vosk + sherpa-onnx)
      convivem no binário pela Decisão 1, então o tamanho deve subir; registrar quanto.
- [ ] 5.3 Instalar no SM-G780F (`./gradlew installDebug`).

## 6. Bancada (Matheus, com voz humana real)

- [ ] 6.1 Recalibrar o limiar de energia de referência para o motor novo, mesmo protocolo
      de [[reference-voz-bancada]] (voz humana direta no aparelho, nunca alto-falante) —
      os -27 dBFS medidos para o Vosk não têm por que valer aqui.
- [ ] 6.2 Repetir a bateria de 9 comandos usada na verificação original do Vosk
      (`add-audio-single-grammar-slice` - "Terceira rodada"), comparando taxa de acerto de
      primeira tentativa entre `MotorVosk` (baseline já documentado: 9/9) e
      `MotorSherpaOnnx`.
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

## 7. Specs e artefatos

- [ ] 7.1 `openspec validate --strict` no change completo.
