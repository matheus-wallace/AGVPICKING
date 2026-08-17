## Why

Três lacunas surgiram numa sessão de bancada real (17/08/2026), fora do escopo das fatias já
planejadas: (1) `ReadbackQuantidade` não fala nada — lido o código, `ProjetorDeFalaPicking.kt`
retorna `null` para esse estado, então o operador não sabe que precisa confirmar a quantidade
por voz; (2) reconhecimento de voz falha silenciosamente com alguma frequência (sobretudo
números e check digit) e hoje não há como medir a taxa de acerto, só o log de gramática/nível já
existente; (3) o fluxo real de operação (chegar → bipar etiqueta → abrir a caixa → contar →
bipar o código do produto) não cabe na janela de câmera atual, que liga e "fecha" cedo demais
para o tempo real que o separador leva contando os itens antes de escanear.

## What Changes

- `ControladorDeFala`/`ProjetorDeFalaPicking` passam a falar em `ReadbackQuantidade`:
  "Confirma {quantidadeInformada}?" — e, ao voltar para `ConfirmandoQuantidade` por correção,
  reforçam a quantidade esperada na fala.
- Todo resultado final do ASR passa a ser logado de forma estruturada, aceito ou não pela
  gramática — hoje só o resultado aceito (que virou evento) fica plenamente rastreável; o texto
  descartado por não casar com a gramática ou pela guarda de estado precisa do mesmo nível de
  log, para medir taxa de reconhecimento por comando em bancada.
- Câmera de `EscaneandoProduto` deixa de encerrar antes do operador ter tempo real de abrir a
  caixa e contar os itens. A causa raiz ainda não está confirmada (ver Open Questions do
  design.md) — pode ser um teto de tempo fixo, ou uma leitura prematura/errada que já satisfaz o
  consenso antes da hora. A correção depende de qual for.

## Capabilities

### New Capabilities

- `voice-readback-feedback`: fala do sistema em `ReadbackQuantidade`, cobrindo confirmação e
  correção da quantidade lida de volta ao operador.
- `asr-recognition-logging`: log estruturado de todo resultado final do ASR, aceito ou
  descartado, para medir precisão de reconhecimento em bancada.

### Modified Capabilities

- `vision-stream-decode`: a janela de câmera de `EscaneandoProduto` passa a acomodar o tempo
  real do operador entre a chegada e o escaneamento do produto, não só o tempo de leitura do
  código em si.

## Impact

- Código afetado: `audio/output/ProjetorDeFalaPicking.kt`/`ControladorDeFala.kt` (novo branch de
  fala), `audio/ReconhecedorDeComando.kt` (log de todo resultado final), `vision/AjustesVisao.kt`
  e `vision/ControladorDeVisao.kt` (janela de câmera — pendente de diagnóstico).
- Sem mudança em `PickingReducer`/`PickingEvent`/contrato do `PickingRepository`.
