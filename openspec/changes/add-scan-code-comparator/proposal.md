## Why

A fatia `add-vision-stream-decode-slice` deixou o produtor de `DecodificacaoConcluida` rodando de ponta a ponta — a câmera lê o código e o reducer leva o ator até `ValidandoContraDados` sozinho, sem toque. Mas esse estado foi um não-objetivo declarado daquela fatia ("quem produz `ValidacaoOk`/`ValidacaoDivergente` continua sendo o painel de dev", `design.md` da fatia de visão): hoje ninguém compara o `codigoLido` contra o dado esperado da linha, e os dois eventos só saem do botão "Validação OK" do painel. Isso quebra o loop hands-free logo depois do escaneamento — a fatia de voz (`add-state-driven-voice-flow`) e a de visão já avançam sozinhas até aqui, mas todo item para nesse ponto e espera um toque manual, mesmo em bancada. Fechar esse elo é o que falta para um walkthrough completo de uma ordem sem tocar a tela.

## What Changes

- Novo produtor `ComparadorDeCodigo`, no mesmo padrão de `ControladorDeVisao` e `ResolvedorDeIntencao`: observa `PickingActor.state`, reage a `ValidandoContraDados`, busca a linha correspondente no `PickingRepository` e publica `PickingEvent.ValidacaoOk(quantidadeEsperada)` quando `codigoLido` bate com `linha.ean`, ou `PickingEvent.ValidacaoDivergente(MotivoExcecao.DIVERGENCIA)` quando diverge. Nunca escreve estado diretamente — só envia evento ao ator, como todo produtor existente.
- Comparação restrita a **EAN-13**, o único formato hoje decodificado ponta-a-ponta e validado em bancada (doc §6.3, passo 1). Sem parsing GS1 (§6.5), sem verificação assistida por VLM (§6.4) e sem tocar o check digit de produto (§7.2) — essas etapas da cascata continuam fora de escopo, como já eram antes desta fatia.
- Nenhuma mudança no reducer: `ValidandoContraDados` já aceita `ValidacaoOk`/`ValidacaoDivergente` desde `add-vision-stream-decode-slice`. Esta fatia só entrega quem publica esses eventos automaticamente.
- Painel de dev mantém os botões "Decodificação OK"/"Validação OK" como atalho de diagnóstico — não são removidos, só deixam de ser o único caminho para sair de `ValidandoContraDados`.
- Wiring novo em `AppContainer.kt`, ao lado de `ControladorDeVisao`.

## Capabilities

### New Capabilities
- `vision-code-comparison`: comparação determinística do código lido pela câmera contra o dado esperado da linha da ordem mockada, publicando `ValidacaoOk`/`ValidacaoDivergente` automaticamente no ator, sem intervenção do painel de dev.

### Modified Capabilities
(nenhuma — o reducer e o contrato de `PickingEvent` já existem; esta fatia só adiciona o produtor que faltava.)

## Impact

- Código novo: `vision/ComparadorDeCodigo.kt` (produtor) + teste de JVM contra `MockPickingRepository`.
- Código alterado: `AppContainer.kt` (fiação do novo componente). Nenhuma mudança em `PickingReducer.kt`, `PickingState.kt`, `PickingEvent.kt`, no painel de dev ou na cascata de captura/decodificação.
- Sem nova dependência, sem chamada de rede, sem mudança no contrato do `PickingRepository`.
