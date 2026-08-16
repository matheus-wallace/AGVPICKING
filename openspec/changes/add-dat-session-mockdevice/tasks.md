## 1. Domínio — pausa sem causa distinguível pelo SDK

- [x] 1.1 Adicionar um novo valor a `GatilhoPausaDat` em `domain/statemachine/PickingEvent.kt` (ex.: `NAO_ESPECIFICADO`), com KDoc explicando que o `DeviceSessionState.PAUSED` do SDK não distingue hastes/remoção/toque (design.md - Decisão 5).
- [x] 1.2 Rodar `./gradlew testDebugUnitTest` a partir de `AgvPickVoice/` e confirmar que os testes existentes de `PickingReducerTest`/`PickingActorTest` continuam passando sem alteração — o reducer já colapsa qualquer `PausaDat` em `MotivoPausa.LIFECYCLE_DAT` independente do `gatilho`.

## 2. Dispositivo simulado (debug only)

- [x] 2.1 Criar `app/src/debug/java/com/agvtronic/pickvoice/dat/mockdevice/` com um bootstrap que chama `MockDeviceKit.getInstance(context).enable()`, pareia um `GlassesModel.RAYBAN_META` e o coloca pronto para uso (`powerOn()` → `unfold()` → `don()`), executado uma única vez antes de qualquer chamada de registro.
- [x] 2.2 Definir o ponto de entrada desse bootstrap como uma função com a mesma assinatura em `app/src/debug` e uma contraparte vazia (no-op) em `app/src/release`, seguindo a técnica padrão de variantes por source set do Android — a mesma usada por `debugImplementation(libs.mwdat.mockdevice)` no `build.gradle.kts`.

## 3. Controlador de sessão DAT

- [x] 3.1 Criar `dat/DatSessionController.kt` (`app/src/main/.../dat/`) recebendo o `PickingActor` no construtor, chamando o bootstrap do dispositivo simulado (via a função do item 2.2) e então `Wearables.startRegistration`, publicando `RegistroIniciado` antes da chamada e observando `Wearables.registrationState`/`Wearables.registrationErrorStream` para publicar `RegistroConcluido`/`RegistroFalhou`.
- [x] 3.2 Após `RegistroConcluido`, criar e iniciar uma `DeviceSession` via `Wearables.createSession(AutoDeviceSelector())`, assinando `session.state` antes de chamar `start()` (doc de referência: comentário do sample `CameraAccess` — "subscribe before start() so no initial transitions are missed").
- [x] 3.3 Mapear `DeviceSessionState` observado para os eventos de sessão: `STARTED` (primeira vez) → `SessaoPreparada`; falha na criação ou timeout até `STARTED` → `SessaoFalhou`; `PAUSED` → `PausaDat(gatilho = NAO_ESPECIFICADO)`; volta de `PAUSED` para `STARTED` → `SessaoRetomada`; `STOPPED` inesperado (sessão estava `STARTED`/`PAUSED`) → `ConexaoBluetoothPerdida`.
- [x] 3.4 Implementar a recriação de sessão: depois de `ConexaoBluetoothPerdida`, observar `Wearables.devices` até o dispositivo voltar a aparecer, criar uma nova `DeviceSession` (não reaproveitar a antiga) e, quando ela atingir `STARTED`, publicar `ConexaoBluetoothRestabelecida`.
- [x] 3.5 Assinar `DeviceSession.errors` e mapear qualquer erro assíncrono que não seja a transição para `STOPPED` (já coberta pelo item 3.3) para `SessaoFalhou`, usando `error.description` no campo `detalhe`.
- [x] 3.6 Tratar a ausência da permissão `BLUETOOTH_CONNECT`: se o controlador for iniciado sem ela, publicar `RegistroIniciado` seguido de `RegistroFalhou` com um detalhe fixo, sem chamar `Wearables.startRegistration` (design.md - Decisão 6).

## 4. Wiring

- [x] 4.1 `MainActivity.kt`: solicitar a permissão `BLUETOOTH_CONNECT` em tempo de execução no `onStart` (padrão do sample `CameraAccess`) antes de chamar o método de início do `DatSessionController` exposto pelo `AppContainer`.
- [x] 4.2 `AppContainer.kt`: construir e expor `val datSessionController: DatSessionController`, montado com o `pickingActor` já existente, seguindo a mesma convenção manual de DI dos demais campos.
- [x] 4.3 `DevPanelViewModel.kt`: remover o bloco de bootstrap do `init` que publica `RegistroIniciado`/`RegistroConcluido`/`SessaoPreparada` manualmente — o painel volta a só refletir e disparar eventos operacionais/transversais/de recuperação, como descrito em `dev-event-panel`.

## 5. Verificação

- [x] 5.1 Rodar `./gradlew assembleDebug` e `installDebug` a partir de `AgvPickVoice/`, instalar no dispositivo físico conectado, e confirmar visualmente que o app sai de `Ocioso` sozinho — sem nenhum toque no painel de dev — e chega em `AguardandoOrdem` com o botão "Confirmar ordem" habilitado, usando o dispositivo simulado pelo MockDeviceKit.
- [x] 5.2 No mesmo app instalado, forçar uma queda de sessão (ex.: chamar `mockDeviceKit` para desconectar/desligar o dispositivo simulado via um hook de depuração temporário, ou parar e reiniciar o processo) e confirmar visualmente que o estado observado no painel de dev vai para `Erro`/`SessaoPausada` conforme o sinal simulado, e volta ao ponto anterior quando o dispositivo simulado volta.
- [x] 5.3 Rodar `./gradlew testDebugUnitTest` a partir de `AgvPickVoice/` — confirma que nada em `domain/`/`data/` quebrou.
