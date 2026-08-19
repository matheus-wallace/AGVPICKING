## Why

A bancada de 18/08/2026 (`add-sherpa-onnx-asr-engine`) mediu Whisper-tiny multilíngue
alucinando em comandos curtos de pt-BR mesmo com sinal limpo (`degradarCanal=false`,
ganho testado) — não era problema de calibração de áudio, era o próprio decodificador
gerando texto sem correspondência no trecho decodificado ("iniciar" virando "e iniciar
prouximo"). Motor foi revertido para Vosk (baseline 9/9) na mesma sessão. A causa é
estrutural: Whisper é autoregressivo/seq2seq, um mecanismo que pode "fugir" do áudio de
entrada; nenhum ajuste de sinal resolve isso.

Inspeção do AAR vendorizado (`sherpa-onnx-1.13.5.aar`, via `javap`, não documentação de
terceiro) mostra que o toolkit já embute suporte a **Omnilingual ASR**, modelo
multilíngue mais recente da Meta: arquitetura CTC (decodificação frame-síncrona, sem o
mecanismo autoregressivo que produz alucinação), disponível como
`OfflineOmnilingualAsrCtcModelConfig` dentro do mesmo `OfflineModelConfig` que já é usado
por `MotorSherpaOnnx`. Matheus decidiu explicitamente que tamanho de APK não é
restrição nesta troca — a prioridade é taxa de acerto na primeira fala, com um prazo de
avaliação até 22/08/2026 e as alternativas de SDK comercial (Picovoice, Vivoka) ainda em
análise de acesso, fora do controle do projeto.

## What Changes

- **BREAKING: decodificador de `MotorSherpaOnnx` troca de Whisper-tiny multilíngue para
  Omnilingual ASR CTC (300M parâmetros, int8).** Mesma classe `OfflineRecognizer`, mesmo
  Silero VAD, mesma reamostragem 8kHz→16kHz, mesma interface `SessaoDeAsr` — só o
  `OfflineModelConfig` passa a preencher `omnilingual` (um único arquivo `.onnx` + os
  `tokens` já existentes na config) em vez de `whisper` (par encoder/decoder +
  `language`/`task`). `MotorVosk` continua existindo e reversível em uma linha, mesmo
  padrão já estabelecido pela Decisão 1 de `add-sherpa-onnx-asr-engine`.
- **`AppContainer.motorDeAsr` volta a apontar para `MotorSherpaOnnx`** (hoje é
  `MotorVosk`, desde a reversão de 18/08/2026) — só se a bancada desta mudança confirmar
  taxa de acerto de primeira tentativa igual ou melhor que o baseline Vosk 9/9; a troca
  do default é o desfecho da tarefa de bancada, não uma decisão já tomada aqui.
- **Modelo vendorizado em `assets/` troca de forma**: os arquivos do Whisper-tiny
  (encoder/decoder/tokens, ~116 MB) saem; entra o pacote Omnilingual ASR CTC int8
  (`sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`, ~293 MB
  comprimido) em `assets/modelo-sherpa-onnx-omnilingual/`. Tamanho final do APK de debug
  não é gate de aceitação desta mudança — decisão explícita de Matheus.
- **Sem mudança de evento de domínio nem de contrato de `MotorDeAsr`/`SessaoDeAsr`.** A
  troca é inteiramente interna a `MotorSherpaOnnx`; `InterpretadorDeFala`,
  `PublicadorDeVoz` e o restante do pipeline de voz não mudam.
- **Critério de aceitação novo, que a rodada anterior não tinha**: o texto reconhecido
  não pode conter conteúdo sem correspondência no áudio de entrada (alucinação) —
  vira um requisito explícito da capability `audio-source`, não só um resultado de
  bancada registrado em memória.

## Capabilities

### New Capabilities
(nenhuma)

### Modified Capabilities
- `audio-source`: o requisito de que o texto reconhecido corresponda ao áudio falado
  (sem conteúdo alucinado) passa a ser um cenário explícito, motivado pela falha
  documentada do decodificador anterior (Whisper) nesta mesma capability.

## Impact

- Código alterado: `audio/MotorSherpaOnnx.kt` (troca de `OfflineWhisperModelConfig` para
  `OfflineOmnilingualAsrCtcModelConfig` dentro de `OfflineModelConfig`, remoção dos
  parâmetros específicos de Whisper como `language`/`task`), `di/AppContainer.kt` (motor
  default), `assets/modelo-sherpa-onnx-omnilingual/PROVENIENCIA.md` (novo, mesmo padrão
  de proveniência já usado para Vosk e Whisper).
- Modelo removido de `assets/`: `modelo-sherpa-onnx/whisper-tiny/*` (encoder, decoder,
  tokens).
- Modelo novo vendorizado: pacote Omnilingual ASR CTC int8 300M (licença a confirmar nas
  tarefas — mesmo cuidado de proveniência já aplicado ao Whisper/Silero VAD).
- Sem mudança em `domain/`, `data/`, `vision/`, nem nos `PickingEvent`s.
- Bancada: repetir exatamente os comandos curtos que expuseram a alucinação do
  Whisper-tiny (18/08/2026) contra o novo decodificador, comparando taxa de acerto de
  primeira tentativa com o baseline Vosk 9/9 e com a tentativa anterior de sherpa-onnx.
