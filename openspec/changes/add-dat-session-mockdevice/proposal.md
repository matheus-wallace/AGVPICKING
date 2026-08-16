## Why

A máquina de estados já tem estados (`Registrando`, `PreparandoSessao`, `SessaoPausada`, `Erro`) e eventos (`RegistroIniciado`, `RegistroConcluido`, `SessaoPreparada`, `PausaDat`, `ConexaoBluetoothPerdida`/`Restabelecida`) dedicados ao ciclo de vida da sessão DAT, e o `PickingActor` já sabe processá-los — mas hoje o único produtor desses eventos é um bootstrap provisório dentro do `DevPanelViewModel`, que dispara `RegistroIniciado` → `RegistroConcluido` → `SessaoPreparada` em sequência fixa, sem nenhuma sessão real por trás (comentário explícito no código aponta esta mudança como o passo seguinte). Essa é a frente de maior risco do projeto: o doc §13.2 marca "DAT → HFP → câmera" como o caminho crítico — "se a sessão não sobe, nada mais importa" — e o dono dessa frente não pode avançar para áudio sem primeiro ter a sessão de verdade em pé.

## What Changes

- Novo componente de sessão DAT (`dat/`) que envolve `Wearables.initialize`, `Wearables.registrationState` e o ciclo de vida do dispositivo selecionado (pareamento, conexão/desconexão, hastes/remoção/toque) e traduz esses sinais para os `PickingEvent`s de sessão já existentes — nenhum evento novo é criado, este componente só passa a ser o produtor real dos que já existem.
- Em build de debug, o dispositivo por trás da sessão é o MockDeviceKit (glasses simuladas, pareadas e "vestidas" automaticamente) — nenhum hardware físico é necessário para exercitar o fluxo completo, inclusive nos testes de bancada até 18/09 (doc §13.1, "todo o desenvolvimento roda contra Mock Device Kit"). Em build de release, o mesmo componente usa a seleção de dispositivo real (`AutoDeviceSelector`), sem outra mudança de código — é exatamente a costura que o doc §13.3 exige ser trivial na manhã do evento.
- Remove o autobootstrap provisório do `DevPanelViewModel` (`RegistroIniciado`/`RegistroConcluido`/`SessaoPreparada` disparados no `init`): a partir desta mudança, quem publica esses eventos é a sessão DAT real, e o painel de dev volta a ser só consumidor/disparador dos eventos operacionais (fluxo principal e transversais), como já descrito na capability `dev-event-panel`.
- `MainActivity` passa a solicitar a permissão Android `BLUETOOTH_CONNECT` em tempo de execução antes de a sessão poder sair de `Ocioso`, seguindo o padrão já validado no sample `CameraAccess` deste mesmo repositório (`Wearables.initialize` já roda incondicionalmente em `PickVoiceApplication.onCreate` desde a mudança anterior — não muda aqui).
- `AppContainer` passa a montar e expor esse componente de sessão, seguindo a mesma convenção manual de DI já usada para `pickingRepository` e `pickingActor`.

## Capabilities

### New Capabilities
- `dat-session`: ciclo de vida da sessão DAT (permissões, inicialização do SDK, registro/pareamento, conexão do dispositivo, pausa por hastes/remoção/toque, perda e retomada de Bluetooth) traduzido para os `PickingEvent`s de sessão do `picking-state-machine`, com o dispositivo simulado por MockDeviceKit em debug.

### Modified Capabilities
(nenhuma — a remoção do autobootstrap do painel de dev é detalhe de implementação, não muda nenhum requisito já especificado em `dev-event-panel`)

## Impact

- Código novo: pacote `dat/` (controlador de sessão + wiring de eventos), incluindo a variante apoiada em MockDeviceKit para debug.
- Código alterado: `AppContainer.kt` (expõe o novo componente), `MainActivity.kt` (permissões + `Wearables.initialize`), `DevPanelViewModel.kt` (remove o bloco de bootstrap do `init`).
- Dependências: `mwdat-core` e `mwdat-mockdevice` já estão declaradas em `AgvPickVoice/app/build.gradle.kts` (a segunda já restrita a `debugImplementation`) — nenhuma dependência nova precisa ser adicionada.
- Sem mudança de contrato de rede, dado mockado ou schema — este é o primeiro código do projeto que fala com o SDK do DAT de verdade (ainda que contra um dispositivo simulado).
