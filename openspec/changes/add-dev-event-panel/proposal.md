## Why

`PickingActor` existe e passa em 7 testes de unidade, mas nunca rodou fora da JVM — ninguém provou que o padrão de `Channel` + corrotina única se comporta bem num processo Android de verdade, com um `CoroutineScope` de verdade. Voz e câmera ainda não existem e são as partes mais arriscadas/lentas do projeto pra construir. Uma tela de desenvolvimento com botões que disparam os mesmos `PickingEvent`s que a voz vai disparar depois prova o ator em hardware real hoje, e resolve a pergunta em aberto que o `design.md` da mudança anterior deixou explicitamente pendente: quem é o dono do `CoroutineScope` do ator.

## What Changes

- Novo `CoroutineScope` de escopo de aplicação (não de `Activity`/`ViewModel`), criado uma única vez em `AppContainer`, nunca cancelado durante a vida do processo.
- `AppContainer` passa a expor `val pickingActor: PickingActor`, resolvendo o `TODO(#dev-event-panel)` já deixado no arquivo.
- Novo `ui/devpanel/DevPanelUiState.kt`, `DevPanelViewModel.kt`, `DevPanelScreen.kt` — um `ViewModel` que combina `pickingActor.state` com a primeira ordem mockada (carregada uma vez do `PickingRepository`), e uma tela Compose mostrando o estado atual, o produto/endereço da linha em andamento quando houver, e botões.
- Os botões cobrem o fluxo linear feliz de um item só (confirmar ordem → iniciar navegação → confirmar check digit da posição → simular scan bem-sucedido → validar dados ok → confirmar quantidade → confirmar readback → alocar carrinho → concluir item) mais dois eventos transversais (parar/emergência, disparar exceção) — o suficiente pra exercitar tanto o caminho linear quanto o transversal sem expor todo evento possível como botão.
- `MainActivity.kt` passa a hospedar `DevPanelScreen` no lugar do placeholder "AGV Pick Voice".

Esta tela é deliberadamente temporária — será substituída pela tela espelho real do §12 numa mudança futura (`add-mirror-screen`). Não fica atrás de build variant (como o `MockDeviceKit` fica): o módulo inteiro ainda não vai pra produção, então não há risco de um operador ver um botão de debug.

## Capabilities

### New Capabilities

- `dev-event-panel`: a superfície de UI que visualiza o `PickingState` atual e permite a um desenvolvedor dirigir o `PickingActor` via toques, no lugar de voz/câmera enquanto esses pipelines não existem.

### Modified Capabilities

_(nenhuma — `picking-state-machine` e `mock-picking-data` não mudam de comportamento, só ganham um consumidor novo)_

## Impact

- Novo pacote `AgvPickVoice/app/src/main/java/com/agvtronic/pickvoice/ui/devpanel/`.
- `di/AppContainer.kt` modificado: adiciona o `CoroutineScope` e expõe `pickingActor`.
- `MainActivity.kt` modificado: hospeda `DevPanelScreen` via um `ViewModelProvider.Factory` manual construído a partir do `AppContainer` (mesma convenção de DI manual já em uso, sem Hilt).
- Nenhuma mudança em `domain/` ou `data/` — só leitura do `PickingRepository` já existente.
- Primeiro teste em hardware físico desde o início do projeto: valida visualmente no celular, não só via JUnit.
