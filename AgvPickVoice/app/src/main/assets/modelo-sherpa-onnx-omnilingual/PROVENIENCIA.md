# Omnilingual ASR CTC — proveniência

> **20/08/2026 — o `model.int8.onnx` saiu do repositório.** Os 349 MiB estouravam o limite de
> 100 MB por arquivo do GitHub e travavam o `push`, e o motor deixou de ser usado. O histórico
> foi reescrito para purgar o blob; ficaram aqui só o `tokens.txt` e esta proveniência. Para
> voltar a rodar o `MotorSherpaOnnx`, baixe o pacote da URL abaixo e extraia o `model.int8.onnx`
> neste diretório — **sem versioná-lo de novo** (ver `.gitignore` de `app/`). Sem esse arquivo o
> projeto compila normalmente, mas o motor sherpa falha ao carregar em tempo de execução; o
> padrão continua sendo o Vosk.

Decodificador do `MotorSherpaOnnx` a partir do change `add-sherpa-onnx-omnilingual-decoder`.
Substitui o Whisper-tiny, que a bancada de 18/08/2026 mediu alucinando em comandos curtos de
pt-BR. Fica versionado no git pela mesma decisão que já vale para o Vosk e para o Silero VAD:
um `git clone` que já compila vale o espaço.

Diretório próprio, e não uma pasta dentro de `modelo-sherpa-onnx/`, para que a proveniência
de cada decodificador continue rastreável separadamente e a volta para o Whisper seja um
`git checkout` no diretório antigo (design.md - Decisão 4).

Baixado em 18/08/2026 da mesma *release* `asr-models` do `k2-fsa/sherpa-onnx` de onde já
vieram o Silero VAD e o Whisper-tiny.

## Pacote

| | |
|---|---|
| Pacote | `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2` (292.313.120 B compactado) |
| Origem | <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2> |
| SHA-256 do pacote | `951b32409aade32bd525310bb39e9666773ba3fc611a39e817f620936d76c631` |
| Licença | Apache-2.0, © 2025 Meta Platforms, Inc. (arquivo `LICENSE` do pacote) |
| Modelo de origem | <https://github.com/facebookresearch/omnilingual-asr> |

Do pacote entram só os dois arquivos abaixo — o `LICENSE`, o `README.md` e os quatro WAVs de
teste (`test_wavs/`, alemão, inglês, espanhol e francês) ficam de fora.

| Arquivo | Tamanho | SHA-256 |
|---|---|---|
| `model.int8.onnx` | 365.841.453 B | `e3042b2f3b3ef0af2211bf99d2b4bf94a21f5ac0e9898827e7dd6d003a860e91` |
| `tokens.txt` | 90.630 B | `7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6` |

## O que os metadados do `.onnx` dizem

Lidos do próprio arquivo, não da documentação:

| Chave | Valor |
|---|---|
| `model_type` | `omnilingual-asr` |
| `sample_rate` | `16000` |
| `vocab_size` | `10288` (bate com as 10.288 linhas de `tokens.txt`) |
| `comment` | `300M-CTC` |

Não há `feat_dim` nem `normalize_samples` nos metadados, e o grafo tem um `feature_extractor`
próprio (arquitetura wav2vec2): **o modelo recebe forma de onda crua e extrai as features
dentro dele**. É a diferença prática para o Whisper, que dependia do banco de filtros de 80
canais montado fora, no `FeatureConfig` — por isso `DIMENSAO_FEATURE` saiu do
`MotorSherpaOnnx` nesta troca.

O `modelType` do `OfflineModelConfig` fica **vazio** de propósito: o despacho do sherpa-onnx
acontece por qual sub-config está preenchida (`omnilingual`), e `omnilingual-asr` não é um dos
valores que o `model_type` da config reconhece. Preencher com o valor dos metadados cairia no
caminho de "Invalid model_type" do C++.

## Por que a variante 300M int8 v2

Das quatro publicadas (300M e 1B, cada uma em `2025-11-12` e `v2-2026-02-05`), é a menor
família na revisão mais nova: a 1B pesa ~787 MB compactada, quase o dobro, sem indício de que
o vocabulário curto deste projeto precise da capacidade extra. Mesmo raciocínio que já tinha
escolhido `tiny` sobre `base` no Whisper. Ver design.md do change - Decisão 1.

Tamanho de APK **não foi critério** nesta rodada — decisão explícita de Matheus, registrada no
proposal.md. O modelo cru ocupa 349 MiB no APK, contra os 99 MB que os três arquivos do
Whisper-tiny ocupavam.

## Ao trocar de modelo

Igual ao Whisper: **não há arquivo `uuid` nem cópia para `getExternalFilesDir`** — o
sherpa-onnx lê direto do `AssetManager`, e o `noCompress += "onnx"` do `build.gradle.kts`
mantém o `.onnx` cru no APK para o ONNX Runtime mapeá-lo em vez de descomprimir 349 MiB para a
memória a cada carga. Trocar os arquivos aqui e reinstalar basta; não existe cópia velha
desempacotada no aparelho para invalidar.

Se trocar a 300M pela 1B (pergunta em aberto no design.md), os nomes de arquivo dentro do
pacote são os mesmos (`model.int8.onnx`, `tokens.txt`) e nada muda em `MotorSherpaOnnx` além
do conteúdo deste diretório.
