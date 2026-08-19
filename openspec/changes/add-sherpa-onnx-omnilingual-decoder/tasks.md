## 1. Modelo e proveniência

- [x] 1.1 Baixar `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2`
      da release `asr-models` do `k2-fsa/sherpa-onnx` (design.md - Decisão 1) e extrair.
- [x] 1.2 Conferir os arquivos extraídos contra o que `OfflineOmnilingualAsrCtcModelConfig`
      espera (`model` — um único `.onnx` — e `tokens`, campo comum de `OfflineModelConfig`)
      antes de vendorizar; nomes de arquivo reais podem diferir do nome do pacote.
- [x] 1.3 Criar `assets/modelo-sherpa-onnx-omnilingual/` e mover os arquivos para lá.
- [x] 1.4 Escrever `assets/modelo-sherpa-onnx-omnilingual/PROVENIENCIA.md` (fonte, URL da
      release, licença, hash do `.tar.bz2`, data do download) — mesmo padrão de
      `assets/modelo-sherpa-onnx/PROVENIENCIA.md` (Whisper/Silero VAD).
- [x] 1.5 Remover `assets/modelo-sherpa-onnx/whisper-tiny/` (encoder, decoder, tokens) do
      controle de versão, mantendo o restante de `assets/modelo-sherpa-onnx/` (Silero VAD)
      intocado — o VAD não muda nesta troca.

## 2. `MotorSherpaOnnx`

- [x] 2.1 Trocar o import de `OfflineWhisperModelConfig` para
      `OfflineOmnilingualAsrCtcModelConfig` em `MotorSherpaOnnx.kt`.
- [x] 2.2 Em `carregar()`, montar `OfflineModelConfig(omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = MODELO), tokens = TOKENS, modelType = TIPO, numThreads = ...)`
      no lugar do bloco atual de `whisper = OfflineWhisperModelConfig(...)` — remover
      `language`/`task` (não existem nessa config, design.md - Decisão 2) e o
      `FeatureConfig`/`DIMENSAO_FEATURE` específico do Whisper se a nova config não os
      exigir (conferir na própria classe via `javap`, não assumir).
      **Desvio conferido, não assumido:** `modelType` ficou **vazio**, não `TIPO`. Os
      metadados do `.onnx` trazem `model_type=omnilingual-asr`, mas esse valor não é aceito
      no campo da config — o despacho do sherpa-onnx é por qual sub-config está preenchida
      (`omnilingual`), e as strings do `libsherpa-onnx-jni.so` mostram o caminho de erro
      "Invalid model_type: %s" para valor não reconhecido. `FeatureConfig` **ficou**, mas só
      com `sampleRate`: o grafo do modelo tem `feature_extractor` próprio (wav2vec2, consome
      forma de onda crua) e os metadados não trazem `feat_dim`, então `DIMENSAO_FEATURE`
      (os 80 canais do banco de filtros do Whisper) foi removido.
- [x] 2.3 Atualizar as constantes do `companion object`: `ENCODER`/`DECODER` saem,
      entra `MODELO` (caminho único), `DIRETORIO` aponta para
      `modelo-sherpa-onnx-omnilingual`, `ARQUIVOS_EXIGIDOS` reflete os novos arquivos.
      `IDIOMA`/`TAREFA` são removidos (não fazem mais sentido sem a config do Whisper).
- [x] 2.4 Manter intocado: `SILERO_VAD`, `TAXA_EXIGIDA`, `JANELA_SILERO`,
      `DIMENSAO_FEATURE` (se ainda usado), toda a classe `SessaoSherpaOnnx`
      (VAD, reamostragem, normalização de texto) e a validação defensiva de
      `existeNoAssets` antes de qualquer chamada nativa (design.md - Risco 5).
- [x] 2.5 Atualizar o KDoc do arquivo (hoje descreve Whisper especificamente) para
      refletir Omnilingual ASR CTC — mesmo padrão de manutenção de comentário já seguido
      neste projeto quando a implementação muda de forma.

## 3. Wiring

- [x] 3.1 Deixar `AppContainer.motorDeAsr` apontando para `MotorVosk` até a bancada da
      seção 6 confirmar o resultado — só trocar para `MotorSherpaOnnx` depois da tarefa
      6.4 (proposal.md - "What Changes": troca de default é desfecho de bancada, não
      decisão já tomada).

## 4. Build e verificação estática

- [x] 4.1 `./gradlew assembleDebug` — build limpo com os novos assets vendorizados.
- [x] 4.2 `./gradlew lintDebug` — sem novos erros introduzidos pelas mudanças em
      `MotorSherpaOnnx.kt`.
- [x] 4.3 `./gradlew testDebugUnitTest` — contagem de testes inalterada (nenhuma classe
      de teste unitário depende do conteúdo do modelo, só da interface `MotorDeAsr`);
      confirmar 0 falhas antes de instalar em bancada.

## 5. Verificação em dispositivo (App abre sem crash)

- [x] 5.1 Instalar no dispositivo de bancada atual (Zebra TC21) com
      `AppContainer.motorDeAsr = MotorSherpaOnnx(...)` temporariamente ativado só para
      este teste (reverter depois se a bancada da seção 6 não for feita na mesma sessão).
      **Desvio:** o único aparelho conectado nesta sessão era o `RQ8NB02BZCD` (Samsung
      SM-G780F), não o TC21 — foi nele que a instalação aconteceu. O toggle para
      `MotorSherpaOnnx` foi aplicado **só o tempo do `installDebug`** e revertido no
      arquivo-fonte logo depois: o `.kt` versionado segue com `MotorVosk` (tarefa 3.1),
      enquanto o APK instalado no aparelho roda o Omnilingual ASR, que é o que a bancada
      da seção 6 precisa exercitar.
- [x] 5.2 Confirmar que `carregar()` retorna `true` e o app abre sem crash nativo
      (`adb logcat -b crash` vazio) — valida que a config nova não aciona
      `SHERPA_ONNX_EXIT` (design.md - Risco 5).
      Resultado: `MotorSherpaOnnx: Omnilingual ASR carregado em 1933ms`, processo vivo,
      `adb logcat -b crash` com 0 linhas.

## 6. Bancada (Matheus, com voz humana real)

- [x] 6.1 Repetir especificamente os comandos isolados que expuseram a alucinação do
      Whisper-tiny em 18/08/2026 ("iniciar", "próximo", sinal limpo,
      `degradarCanal=true`, padrão do dispositivo) e registrar se o texto reconhecido
      corresponde ao áudio falado, sem conteúdo extra concatenado (design.md - Decisão 5,
      spec `audio-source` - cenário "Comando curto isolado não produz texto alucinado").
      **Achado, 18/08/2026, SM-G780F (`RQ8NB02BZCD`):** não alucina no sentido do Whisper
      (não gera continuação de texto plausível desconectada do áudio), mas erra de forma
      diferente e igualmente fatal para a gramática — decodifica em scripts de outros
      idiomas. Log real capturado (`adb logcat -s ReconhecedorDeComando:*`):
      `"你ش"`, `"isia prosim"`, `"ρόσημο"`, `"ग ग श ग ग प रसम"`,
      `"inifiar inifiar inifiar"`, `"inicia iniciar próxima iniciar"` (esse último contém
      "iniciar"/"próxima" corretos, mas cercados de repetição — mesmo padrão de sintoma do
      Whisper, causa diferente). Nível de sinal normal (-20 a -40 dBFS), VAD disparando
      normalmente — não é problema de captura de áudio. Todas as tentativas descartadas
      por "fora da gramática", nenhuma alcançou 6.2.
      **Causa raiz confirmada, não hipótese:** `OfflineOmnilingualAsrCtcModelConfig` não
      tem campo de idioma — confirmado tanto no binding Kotlin (`javap`) quanto no
      `.h` do C++ oficial (`offline-omnilingual-asr-ctc-model-config.h`, via GitHub raw:
      struct só tem `model: std::string`, nenhum outro campo). O modelo é um único
      checkpoint compartilhando o espaço de saída entre os 1600 idiomas sem seletor em
      tempo de execução — para uma elocução isolada de 1-2 palavras não há contexto
      acústico suficiente para o modelo decidir o script certo. Existe issue aberta no
      repositório oficial pedindo exatamente esse recurso, ainda não implementado:
      `k2-fsa/sherpa-onnx#2812` ("Add language hint for Omnilingual ASR CTC on Android &
      iOS"). Tentativa de mitigação via `hotwordsFile`/`hotwordsScore`
      (`OfflineRecognizerConfig`, já presente no AAR) **descartada sem implementar** —
      documentação oficial confirma que hotwords só funciona em modelos transducer, não
      em CTC ("Only transducer models support hotwords... All other models don't support
      hotwords"), então não haveria efeito algum no Omnilingual ASR.
- [ ] 6.2 Rodar a bateria formal dos 9 comandos já usada como baseline do Vosk
      (`add-audio-single-grammar-slice`), primeira tentativa, dBFS de pico, mesmo
      protocolo de [[reference-voz-bancada]]. **Não executada** — 6.1 já mostrou que
      nenhuma elocução isolada passa da gramática, rodar a bateria completa não mudaria
      a conclusão (mesmo raciocínio já aplicado ao Whisper-tiny em 18/08/2026).
- [x] 6.3 Comparar taxa de acerto de primeira tentativa: Omnilingual ASR CTC vs. baseline
      Vosk (9/9) vs. resultado já registrado do Whisper-tiny (18/08/2026). **Resultado:
      0/tentativas em 6.1** — pior que o Whisper-tiny (que ao menos produzia a palavra
      certa embutida em texto extra em alguns casos) e muito abaixo do baseline Vosk 9/9.
- [ ] 6.4 Medir latência fim-de-fala até evento publicado. **Não executada** — sem
      reconhecimento correto em nenhuma tentativa, latência do decodificador é
      irrelevante para a decisão desta rodada.
- [x] 6.5 Com base em 6.3: **decisão é manter Vosk.** `AppContainer.motorDeAsr` já estava
      em `MotorVosk` desde a tarefa 3.1 e continua assim — nenhuma reversão necessária.
      Duas trocas de decodificador dentro do sherpa-onnx (Whisper autoregressivo,
      Omnilingual CTC) falharam por razões estruturalmente diferentes no mesmo tipo de
      elocução (comando isolado curto), e nenhuma delas tem mecanismo de restrição por
      gramática disponível no toolkit para esse caso — só modelos transducer têm
      hotwords, e o model zoo do sherpa-onnx não publica transducer para português
      (achado original de `add-sherpa-onnx-asr-engine`). Via sherpa-onnx encerrada para
      este projeto até que um desses dois fatos mude. Próximo passo recomendado:
      bancada dos itens já implementados e não verificados de
      `add-voice-recognition-reliability` (grupo 6, 6 tarefas) — é a via com maior
      probabilidade de melhorar a taxa de acerto do Vosk antes de 22/08/2026, por não
      depender de trocar de motor de novo.
