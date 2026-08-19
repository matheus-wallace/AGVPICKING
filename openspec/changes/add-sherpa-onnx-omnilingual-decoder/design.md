## Context

`MotorSherpaOnnx.kt` já existe, comitado em `15af995`, e hoje monta um
`OfflineRecognizer` com `OfflineModelConfig.whisper` (Whisper-tiny multilíngue int8). A
bancada de 18/08/2026 mediu esse decodificador alucinando em comandos curtos de pt-BR —
achado registrado em `add-sherpa-onnx-asr-engine`, não repetido aqui. `AppContainer`
hoje usa `MotorVosk` como default; este change não muda essa linha até a bancada da seção
6 confirmar o resultado.

Inspeção do AAR vendorizado (`app/libs/sherpa-onnx-1.13.5.aar`) via `javap` nesta mesma
sessão de design (mesma prática de `add-sherpa-onnx-asr-engine` - Decisão 5, nunca
confiar em busca web sobre API real) confirma que `OfflineModelConfig` já expõe:

```
private OfflineOmnilingualAsrCtcModelConfig omnilingual;
// classe: OfflineOmnilingualAsrCtcModelConfig(model: String) — um único arquivo .onnx,
// reaproveita o campo `tokens` que já existe em OfflineModelConfig (mesmo campo que o
// Whisper já usava)
```

Não há campo `language`/`task` nessa config (diferente de `OfflineWhisperModelConfig`) —
o modelo é um único checkpoint CTC que cobre os idiomas do treino sem seleção explícita
em tempo de execução, o que também elimina a suposição `IDIOMA = "pt"`/`TAREFA =
"transcribe"` que `MotorSherpaOnnx` hoje passa para o Whisper.

## Goals / Non-Goals

**Goals:**
- Eliminar o mecanismo de alucinação medido no Whisper-tiny, trocando para um
  decodificador CTC (frame-síncrono, sem geração autoregressiva livre).
- Manter 100% do resto do pipeline (Silero VAD, reamostragem, `SessaoDeAsr`,
  `NormalizadorDeTextoAsr`) sem alteração de forma — só a config do reconhecedor muda.
- Preservar a reversibilidade em uma linha para `MotorVosk`, mesmo padrão já
  estabelecido.

**Non-Goals:**
- Otimizar tamanho de APK ou tempo de `installDebug` — decisão explícita de Matheus
  nesta rodada: tamanho não é gate de aceitação.
- Recalibrar `PerfilEndpoint`/parâmetros do Silero VAD — o VAD não muda, só o
  decodificador depois dele; se a bancada da seção 6 mostrar que o corte de silêncio
  precisa de outro valor para este decodificador especificamente, isso é uma tarefa de
  bancada separada, não parte deste change.
- Comparar contra Picovoice Rhino ou Vivoka — ambos seguem em análise de acesso, fora do
  controle deste projeto; este change resolve o que dá para resolver sem depender de
  terceiros antes de 22/08/2026.
- Suporte a mais de um idioma simultâneo além do que o próprio checkpoint Omnilingual já
  cobre nativamente — mesmo non-goal já registrado para o Whisper multilíngue.

## Decisions

### Decisão 1: Omnilingual ASR CTC 300M int8, variante `v2-2026-02-05`, como ponto de partida

Existem duas famílias publicadas pelo k2-fsa (`asr-models`, release `asr-models`):
300M e 1B parâmetros, cada uma com uma revisão `2025-11-12` e uma `v2-2026-02-05`. Entre
as quatro, a escolhida é **300M int8 v2** (`sherpa-onnx-omnilingual-asr-1600-languages-
300M-ctc-v2-int8-2026-02-05.tar.bz2`, ~292 MB comprimido, tamanho confirmado via GitHub
API nesta sessão): menor das duas famílias (a 1B pesa ~787 MB comprimido, quase o dobro,
sem indício de que o vocabulário curto deste projeto precise de mais capacidade — mesmo
raciocínio já aplicado ao escolher Whisper-tiny sobre base) e revisão mais nova
disponível (`v2` é posterior a `2025-11-12`, presumivelmente uma correção/melhoria do
mesmo checkpoint, não uma arquitetura diferente).

Isso é ponto de partida, não medição — tarefa de bancada compara contra o baseline Vosk
9/9 e contra o resultado já registrado do Whisper-tiny, mesmo formato usado em
`add-sherpa-onnx-asr-engine` - Decisão 4.

Alternativa considerada: `sherpa-onnx-cohere-transcribe-14-lang-int8` (14 idiomas
incluindo português, encontrado na mesma pesquisa que revelou o Omnilingual ASR).
Rejeitada por dois motivos: (a) arquitetura não confirmada como CTC — o nome "transcribe"
e a origem (Cohere, empresa de LLM) sugerem um decodificador maior, possivelmente
seq2seq, o que reintroduziria o mesmo risco estrutural de alucinação que motivou esta
troca; (b) verificar isso exigiria a mesma inspeção de API que já foi feita para o
Omnilingual ASR, e o prazo até 22/08/2026 não comporta investigar duas famílias de modelo
em paralelo. Fica registrado como alternativa futura se o Omnilingual ASR não bater o
baseline Vosk.

### Decisão 2: Sem parâmetro de idioma/tarefa na nova config

`OfflineWhisperModelConfig` tinha `language = "pt"` e `task = "transcribe"`, ambos
removidos nesta troca porque `OfflineOmnilingualAsrCtcModelConfig` não tem esses campos
(confirmado via `javap` — construtor recebe só `model: String`). O checkpoint é
multilíngue por treino, não por seleção em runtime. Consequência a testar na bancada da
seção 6: sem hint de idioma, é uma hipótese em aberto se o modelo mantém a mesma precisão
em pt-BR que tinha o Whisper com `language="pt"` fixado — não foi possível confirmar isso
sem rodar o modelo, então vira item de bancada, não suposição de design.

### Decisão 3: `IDIOMA`/`TAREFA` saem da classe; `SILERO_VAD`, taxa de amostragem e janela
não mudam

O VAD e a reamostragem 8kHz→16kHz são independentes do decodificador escolhido depois
deles — nenhuma constante relacionada ao Silero VAD (`TAXA_EXIGIDA`, `JANELA_SILERO`)
muda nesta troca. Só as constantes de arquivo (`ENCODER`/`DECODER`/`TOKENS` viram um
único `MODELO`/`TOKENS`) e o bloco que monta `OfflineModelConfig` mudam dentro de
`MotorSherpaOnnx.kt`.

### Decisão 4: Modelo vendorizado do zero, sem reaproveitar o diretório do Whisper

`assets/modelo-sherpa-onnx/whisper-tiny/` é removido e um novo diretório
`assets/modelo-sherpa-onnx-omnilingual/` é criado, com o mesmo padrão de
`PROVENIENCIA.md` já usado para Vosk e Whisper (fonte, licença, hash, data do download).
Motivo: manter o histórico de proveniência rastreável por modelo, e permitir reverter
para Whisper-tiny (`git checkout` no diretório antigo) sem perder o registro de
proveniência dele, mesmo que a intenção seja não usá-lo mais.

### Decisão 5: Critério de aceitação de bancada é o mesmo teste que expôs o defeito

A tarefa de bancada (seção 6 de `tasks.md`) repete literalmente os mesmos comandos
isolados que fizeram o Whisper-tiny alucinar em 18/08/2026 ("iniciar", "próximo"),
comparando contra o mesmo protocolo. Não basta rodar a bateria formal de 9 comandos do
Vosk — é preciso reproduzir especificamente o cenário que motivou esta troca, senão o
critério de sucesso não testaria a causa raiz que este change existe para corrigir.

## Risks / Trade-offs

- **[Risco]** Sem medição prévia, não há garantia de que Omnilingual ASR CTC 300M supere
  o Vosk ou até o próprio Whisper-tiny em acurácia de pt-BR — CTC elimina o mecanismo de
  alucinação, mas não garante que o modelo acústico em si seja melhor para este
  vocabulário. → **Mitigação**: `MotorVosk` continua default até a bancada confirmar;
  reversão é uma linha em `AppContainer`, mesmo padrão já validado neste projeto.
- **[Risco]** Sem campo de idioma na config (Decisão 2), a precisão em pt-BR específico
  pode ser menor que a do Whisper com `language="pt"` fixado, mesmo sem alucinar. →
  **Mitigação**: nenhuma preventiva possível em design — é exatamente o que a bancada da
  seção 6 mede; se a taxa de acerto vier abaixo do baseline Vosk mesmo sem alucinação,
  o resultado é registrado e a decisão de manter Vosk permanece.
- **[Risco]** ~292 MB comprimidos a mais em `assets/` (não compensados pela remoção dos
  ~116 MB do Whisper-tiny) aumentam o tempo de `installDebug` na bancada, que já era um
  fator citado em `add-sherpa-onnx-asr-engine` - Decisão 4. → **Mitigação nenhuma
  aplicada de propósito**: Matheus decidiu explicitamente que tamanho não é gate nesta
  rodada, dado o prazo até 22/08/2026 e a falta de alternativa de SDK comercial
  disponível agora. Fica registrado como trade-off consciente, não ignorado.
- **[Risco]** Licença do pacote Omnilingual ASR não verificada nesta sessão de design —
  só o tamanho e os nomes de arquivo foram confirmados via GitHub API. → **Mitigação**:
  tarefa dedicada de proveniência (mesmo padrão do Vosk/Whisper) antes de vendorizar,
  não depois.
- **[Risco]** É a primeira vez que este projeto carrega um modelo CTC via sherpa-onnx —
  os mesmos riscos de API não verificada que já se aplicaram ao Whisper (Decisão 5 de
  `add-sherpa-onnx-asr-engine`: `SHERPA_ONNX_EXIT` mata o processo sem stack trace em
  config inválida) valem aqui, só que para uma classe de config nova
  (`OfflineOmnilingualAsrCtcModelConfig`) nunca instanciada neste código. → **Mitigação**:
  mesma validação defensiva antes de qualquer chamada nativa que `MotorSherpaOnnx` já
  faz para o Whisper (checar arquivos em `assets/` antes de construir o `OfflineRecognizer`)
  se aplica identicamente ao novo caminho.

## Bancada

Executada em 18/08/2026 no SM-G780F (`RQ8NB02BZCD`) — resultado completo em
`tasks.md`, seção 6. Resumo: o risco do primeiro bullet desta seção se confirmou, mas por
um mecanismo diferente do hipotetizado — não foi "acurácia pior sem alucinar", foi
confusão de idioma/script que impede qualquer reconhecimento correto em elocuções
isoladas curtas. O risco "sem campo de idioma, precisão pt-BR pode ser menor" (bullet 2)
também se confirmou, de forma mais severa do que "menor": sem seletor de idioma, o
espaço de saída de 1600 idiomas compete inteiro por cada elocução curta, e o resultado é
tipicamente incorreto, não apenas subótimo. `MotorVosk` permanece default; este change
não chega a trocar `AppContainer` em nenhum momento definitivo.

## Open Questions

- Se a bancada mostrar que o Omnilingual ASR bate o baseline Vosk mas com latência de
  inferência sensivelmente maior (300M parâmetros é ~8x o Whisper-tiny em contagem de
  parâmetros, mesmo sem o custo autoregressivo), vale a pena medir a variante 1B antes de
  descartar a família Omnilingual, ou o 300M já é suficiente? Fica para decidir depois da
  primeira rodada de bancada, não bloqueia a implementação.
