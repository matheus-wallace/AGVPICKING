## 1. CoroutineScope e fiação do ator

- [x] 1.1 Adicionar um `CoroutineScope` de escopo de aplicação em `di/AppContainer.kt` (`SupervisorJob() + Dispatchers.Default`), criado uma única vez no construtor do container.
- [x] 1.2 Expor `val pickingActor: PickingActor` em `AppContainer`, construído com esse escopo — resolve o `TODO(#dev-event-panel)` já deixado no arquivo.

## 2. ViewModel do painel

- [x] 2.1 Criar `ui/devpanel/DevPanelUiState.kt` — estado de UI combinando o `PickingState` atual com produto/endereço da linha em andamento, quando o estado referenciar uma.
- [x] 2.2 Criar `ui/devpanel/DevPanelViewModel.kt` — recebe `PickingActor` e `PickingRepository` via construtor, carrega a primeira ordem mockada uma vez no `init`, expõe `StateFlow<DevPanelUiState>` combinando `pickingActor.state` com os dados da ordem, e expõe uma função por evento do fluxo linear feliz de um item mais as duas funções transversais (parar/emergência, disparar exceção).

## 3. Tela

- [x] 3.1 Criar `ui/devpanel/DevPanelScreen.kt` — Composable que coleta o `StateFlow` do `ViewModel`, mostra o nome do estado atual, produto/endereço da linha quando disponível, e um botão por evento exposto pelo `ViewModel`.
- [x] 3.2 Atualizar `MainActivity.kt` para construir `DevPanelViewModel` a partir do `AppContainer` (via um `ViewModelProvider.Factory` manual) e hospedar `DevPanelScreen`, substituindo o placeholder "AGV Pick Voice".

## 4. Verificação

- [ ] 4.1 Rodar `./gradlew assembleDebug` e `installDebug` a partir de `AgvPickVoice/`, instalar no dispositivo físico conectado, e confirmar visualmente que apertar os botões na ordem do fluxo principal (confirmar ordem → ... → concluir item) avança o texto de estado corretamente, e que o botão de emergência funciona a partir de qualquer estado operacional.
- [x] 4.2 Rodar `./gradlew testDebugUnitTest` a partir de `AgvPickVoice/` — confirma que nada em `domain/`/`data/` quebrou.
