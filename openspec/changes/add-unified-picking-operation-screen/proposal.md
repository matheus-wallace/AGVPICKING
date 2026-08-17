# Proposal: tela operacional unificada de separação

## Why

O app hoje exibe a prévia espelho junto do painel de desenvolvimento. Esse painel contém estado técnico, telemetria e botões para testar o ator, mas não representa a experiência de um separador. No WMS atual, o operador passa por três telas distintas: validar endereço, produto e quantidade. No fluxo hands-free, trocar de tela a cada etapa adicionaria distração sem ajudar a operação.

É necessário criar uma tela operacional real, única e orientada ao estado, que mantenha o contexto da linha e troque somente a instrução e a confirmação visual da etapa. O painel de debug continua disponível separadamente para bancada e diagnóstico.

## What Changes

- Criar uma tela Compose de operação que reúne, em uma única superfície, as etapas de validação de endereço, produto e quantidade do WMS atual.
- Derivar toda a apresentação de `PickingActor.state`, da linha corrente no `PickingRepository`, do diagnóstico de fala e do diagnóstico de visão, sem duplicar regras do reducer na UI.
- Mostrar a prévia espelho e a moldura de ROI somente durante a validação de produto; nos demais estados, ocupar essa área com orientação operacional, progresso e confirmação da última ação.
- Tornar a tela essencialmente informativa: a operação normal avança por voz, câmera ou transições já previstas, não por botões de “próximo”.
- Manter `DevPanelScreen` intacto e acessível em uma superfície de desenvolvimento explícita, sem misturar seus controles com a tela do operador.

## Capabilities

### New Capabilities

- `unified-picking-operation-screen`: tela única de separação que apresenta contexto e confirmação das três validações do WMS ao longo da máquina de estados.

### Modified Capabilities

- `vision-mirror-preview`: a prévia passa a ser hospedada pela tela operacional durante a validação de produto, preservando suas garantias de privacidade e lifecycle.
- `dev-event-panel`: deixa de ser a tela principal da Activity; continua disponível para desenvolvimento com os mesmos controles e diagnósticos.

## Impact

- Novo pacote `ui/operation/` com `OperationScreen`, `OperationViewModel` e estado de UI testável; componentes visuais da prévia podem ser extraídos de `ui/mirror/` para reuso.
- `MainActivity` passa a selecionar a superfície de operação por padrão e a oferecer uma entrada clara para o painel de debug.
- Depende de `add-state-driven-voice-flow` para que a ausência de botões de avanço seja funcional no fluxo completo; pode ser implementada antes, mas sua validação hands-free só fecha junto daquele slice.
