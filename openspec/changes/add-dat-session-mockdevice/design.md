## Context

Ver proposal.md - Why. O ponto de partida técnico, confirmado explorando os samples deste repositório (`samples/CameraAccess`, `samples/DisplayAccess`) e as skills do plugin `mwdat-android` (em especial `session-lifecycle` e `permissions-registration`), é a superfície real do SDK do DAT disponível hoje neste checkout:

- `Wearables.startRegistration(activity)` inicia o pareamento; `Wearables.registrationState: StateFlow<RegistrationState>` (`UNAVAILABLE`/`REGISTERING`/`REGISTERED`/`UNREGISTERING`) reporta o progresso; `Wearables.registrationErrorStream` emite falhas de registro separadamente do state flow.
- `Wearables.createSession(deviceSelector): DatResult<DeviceSession, DeviceSessionError>` cria a sessão; `DeviceSession.start()`/`.stop()`; `DeviceSession.state: StateFlow<DeviceSessionState>` com os valores `IDLE`/`STARTING`/`STARTED`/`PAUSED`/`STOPPING`/`STOPPED`; `DeviceSession.errors: SharedFlow<DeviceSessionError>` para falhas assíncronas (ex.: `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`). Assinaturas confirmadas com `javap` sobre o `mwdat-core-0.9.0.aar`, não inferidas dos samples.
- `AutoDeviceSelector` (pacote `com.meta.wearable.dat.core.selectors`) seleciona automaticamente o primeiro dispositivo disponível em `Wearables.devices`, e funciona de forma idêntica contra um dispositivo real e contra um dispositivo simulado pelo `MockDeviceKit` habilitado — é o mesmo seletor usado em `CameraAccess`.
- `MockDeviceKit.getInstance(context).enable()` inicializa o ambiente simulado (auto-inicializa `Wearables` se preciso); `mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META)` retorna um `MockGlasses` controlável via `powerOn()`/`unfold()`/`don()`; a partir daí ele aparece em `Wearables.devices` como qualquer dispositivo real.
- O checklist da skill `session-lifecycle` é explícito: **"Recreate sessions after terminal stops instead of reusing dead ones"** — `STOPPED` é terminal, não há `resume()`; `PAUSED`, ao contrário, é uma pausa da mesma sessão (o exemplo do sample `CameraAccess` só chama `cleanupSession()` — descarta a referência — quando o estado é `STOPPED`, nunca em `PAUSED`).

O `PickingActor`, o `reduce()` e todos os `PickingEvent`s/`PickingState`s de sessão (`RegistroIniciado`, `RegistroConcluido`, `RegistroFalhou`, `SessaoPreparada`, `SessaoFalhou`, `PausaDat`, `ConexaoBluetoothPerdida`, `ConexaoBluetoothRestabelecida`, `SessaoRetomada`) já existem e já são testados (`add-state-machine-and-mock-data`). O único produtor hoje é o bootstrap fixo dentro de `DevPanelViewModel.init`.

## Goals / Non-Goals

**Goals:**
- Ser o produtor real dos `PickingEvent`s de sessão já existentes — nenhum evento novo no domínio.
- Rodar de ponta a ponta sem hardware físico em debug, via MockDeviceKit, sem intervenção manual do desenvolvedor.
- Deixar a troca debug → release restrita à seleção de build (doc §13.3: "a troca do device selector... deve ser a única mudança de código necessária pela manhã").

**Non-Goals:**
- Construir as telas de Pareamento e Operação (espelho) do doc §12 — o painel de dev continua sendo a única superfície de UI; isso é escopo de uma mudança futura.
- Qualquer coisa de câmera/stream (`Camera`, `Stream`, `StreamConfiguration`) — pertence à fatia de visão.
- Roteamento de áudio HFP e carregamento de modelo de ASR/VAD — o doc §3.1 associa isso a `PreparandoSessao`, mas nesta fatia `SessaoPreparada` é disparado só pelo `DeviceSessionState.STARTED`; a fatia de áudio decide depois se algo mais precisa acontecer antes desse evento.
- Distinguir `hastes fechadas` / `óculos removido` / `toque` dentro de uma pausa — o `DeviceSessionState.PAUSED` observado nos exemplos deste repositório não carrega qual das causas do doc §2.3 disparou a pausa, só que a sessão pausou. Ver Decisão 5.

## Decisions

1. **Novo pacote `dat/`, um único componente controlando a sessão.** Um `DatSessionController` (nome sugerido) recebe o `PickingActor` e expõe uma função de start chamada uma vez pelo `AppContainer`; internamente ele orquestra registro → sessão → observação contínua, publicando eventos no actor. Alternativa rejeitada: espalhar essa orquestração dentro de `MainActivity`/`AppContainer` diretamente — quebraria a convenção já estabelecida de abstrações isoladas e testáveis (o TODO de `fonteAudio` no `AppContainer` segue o mesmo padrão).

2. **`AutoDeviceSelector` em debug e em release, sem seleção manual.** É o que o doc §13.3 exige (troca de código mínima no dia do evento) e é o mesmo seletor já usado no sample `CameraAccess` contra MockDeviceKit e dispositivo real.

3. **Bootstrap do MockDeviceKit é automático, não uma tela de menu.** Em `app/src/debug/.../dat/mockdevice/`, antes de qualquer chamada de registro: `mockDeviceKit.enable()` → `pairGlasses(RAYBAN_META)` → `powerOn()` → `unfold()` → `don()`. Diferente do sample `CameraAccess`, que expõe um menu manual porque o objetivo lá é explorar a API interativamente — aqui o objetivo é que o fluxo completo rode sem fricção toda vez que o app sobe em debug, inclusive em CI/testes instrumentados futuros.

4. **Reconexão após `STOPPED` cria uma sessão nova.** Segue a orientação da skill `session-lifecycle` de não reaproveitar sessão terminal. Do ponto de vista do `PickingActor` isso é transparente: o evento `ConexaoBluetoothRestabelecida` é publicado quando a sessão nova (não a antiga) atinge `STARTED`, independente de qual objeto `DeviceSession` está por trás.

5. **Novo valor em `GatilhoPausaDat` para pausa sem causa distinguível pelo SDK.** Como `DeviceSessionState.PAUSED` não expõe qual das causas do doc §2.3 disparou a pausa (nenhum sample ou skill deste repositório mostra esse detalhe sendo exposto), esta mudança adiciona um quarto valor ao enum existente (`HASTES_FECHADAS`, `OCULOS_REMOVIDO`, `TOQUE`) para representar esse caso — decisão tomada agora, não deixada como pergunta em aberto, porque adiar mudaria a assinatura do evento publicado por este componente. Isso não altera nenhum requisito de `picking-state-machine`: a spec dessa capability trata os motivos apenas como exemplos ilustrativos de `PausaDat`, e o reducer já colapsa qualquer `PausaDat` em `MotivoPausa.LIFECYCLE_DAT` independente do `gatilho` carregado.

6. **Falha de permissão Android é tratada como registro falhado.** Não existe um estado anterior a `Registrando` na máquina para representar "sem permissão de Bluetooth". Se `BLUETOOTH_CONNECT` não estiver concedida quando o app tentaria iniciar o registro, o componente publica diretamente `RegistroIniciado` seguido de `RegistroFalhou` com um detalhe textual fixo, em vez de chamar `Wearables.startRegistration` sem a permissão necessária.

## Risks / Trade-offs

- **[Risco]** Perda de fidelidade de log: pausas reais de hastes/remoção/toque não são distinguíveis via SDK nesta fatia. → **Mitigação**: o novo valor de `GatilhoPausaDat` documenta essa limitação no próprio KDoc; se uma versão futura do SDK expuser a causa, o mapeamento é ajustado sem tocar em nenhum requisito publicado.
- **[Risco]** MockDeviceKit sempre pareado e "vestido" automaticamente pode mascarar bugs do fluxo de pareamento manual real, que só existirá quando a tela de Pareamento (doc §12) for construída. → **Mitigação**: doc §13.1 já assume que nenhum marco antes de 18/09 depende dos óculos físicos; o fluxo manual será validado no próprio dia, contra hardware real, quando essa tela existir.
- **[Risco]** `DeviceSession.errors` carrega mais detalhe do que `CausaErro` consegue expressar (ex.: atualização de firmware pendente). → **Mitigação**: o texto do SDK vai no campo `detalhe` já existente de `SessaoFalhou`, sem criar causa nova.

7. **`SessaoFalhou` só enquanto a sessão ainda não subiu** (decisão revisada na bancada). A intenção original era publicar `SessaoFalhou` para todo erro assíncrono que não fosse a transição para `STOPPED`, filtrando por tipo de erro. O teste em dispositivo mostrou que isso não separa os casos: desparear o óculos simulado emite `SESSION_ENDED_BY_DEVICE` **e** `UNEXPECTED_ERROR` no mesmo instante do `STOPPED`, e `UNEXPECTED_ERROR` é genérico demais para entrar numa lista de "erros de desconexão". Quem separa é a fase: enquanto a sessão não atingiu `STARTED`, um erro é a explicação de ela não subir e vira `SessaoFalhou`; depois disso, quem manda o fluxo para o caminho de retomada é a transição de estado, e o erro fica só no log. Isso continua atendendo o requisito da spec, que só exige `SessaoFalhou` quando a criação falha ou a sessão não fica ativa.

## Verificação em bancada

Limitação encontrada ao testar: `MockGlasses.powerOff()` **não** derruba a sessão. O canal do dispositivo morre e o `DAT:HeartbeatMonitor` acusa `DWA unavailable`, mas `DeviceSessionState` permanece `STARTED` indefinidamente. O caminho de queda só é observável via `MockDeviceKit.unpairDevice(...)`, que produz o `STOPPED` esperado. Quem for exercitar perda de conexão sem óculos físico precisa usar o despareamento, não o desligamento.
