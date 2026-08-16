## Why

Todo input do AGV Pick Voice — voz, câmera, check digit, ciclo de vida da sessão — termina em um único evento postado num único ator de estado (doc §4.3), e toda tela lê seus dados de um único repositório mockado (§11). Construir e testar essas duas peças primeiro, sem depender de Vosk, ML Kit ou do DAT SDK, dá pra cada fatia seguinte (painel de eventos de dev, pipeline de áudio, pipeline de visão) algo real pra se conectar, em vez de uma casca pra construir em volta. É a própria filosofia do Marco 1 (§13.1) aplicada ao primeiro corte: o fake mais barato possível, mas o núcleo de verdade, testável hoje só com JUnit — sem emulador, sem dispositivo.

## What Changes

- Novo `PickingState` sealed interface cobrindo os 19 estados da tabela em §3.1 (`Ocioso`, `Registrando`, ... `Erro`).
- Novo `PickingEvent` sealed interface cobrindo o fluxo linear (§3.2) e as transições transversais disponíveis a partir de qualquer estado operacional (§3.3): parar/emergência, repetir, exceção, pausa do DAT, perda de BT.
- Novo `PickingReducer`: uma função pura `(PickingState, PickingEvent) -> PickingState` codificando a tabela de transições. Sem I/O, sem corrotinas — a parte que toda peça futura (áudio, visão, UI) vai dirigir, mas nunca tocar diretamente.
- Novo `PickingActor`: a casca de ator único do §4.3 — um `Channel<PickingEvent>`, uma corrotina consumindo sequencialmente e aplicando `PickingReducer`, exposta como `StateFlow<PickingState>`. Nenhum produtor ainda conectado (voz/câmera/UI chegam em mudanças futuras); esta mudança só prova que o padrão de ator compila e é testável.
- Novo `PickingRepository` interface (§11.1): `operadorAtual`, `ordensDisponiveis`, `ordem`, `registrarColeta`, `registrarExcecao`, `fecharConferencia`.
- Novo `MockPickingRepository`: implementação em memória com ao menos uma `Ordem` estruturalmente realista (SKUs/GTINs/formatos de lote reais por §11.4), zero chamadas de rede.
- Novos modelos de dados do §11.2: `Linha`, `Coleta`, `MetodoValidacao`, além de `Endereco`, `Operador`, `Ordem`, `ResumoOrdem`, `Excecao`, `Conferencia` conforme necessário pra satisfazer a interface do repositório.
- Novos testes unitários (JUnit, `AgvPickVoice/app/src/test/`) cobrindo: cada transição do reducer em §3.2/§3.3, e que o repositório mockado retorna dados no formato dos modelos §11.2.

## Capabilities

### New Capabilities

- `picking-state-machine`: o modelo sealed de estado/evento, o reducer puro, e a fiação de ator único (`Channel` + corrotina) na qual toda fonte de input futura (voz, câmera, painel de dev) vai postar eventos.
- `mock-picking-data`: a interface `PickingRepository` e sua implementação mockada em memória, substituindo o WMS conforme a regra de mock de dados do doc (§1.2) — dado é mockado, sensor/decodificação nunca são.

### Modified Capabilities

_(nenhuma — primeira mudança, nada preexiste)_

## Impact

- Só código novo, sob `AgvPickVoice/app/src/main/java/com/agvtronic/pickvoice/domain/statemachine/` e `.../data/` (mais `data/mock/`, `data/model/`).
- Novos testes sob `AgvPickVoice/app/src/test/java/com/agvtronic/pickvoice/`.
- Sem mudança de UI (`MainActivity` continua o placeholder do scaffold) e sem fiação de DAT SDK / MockDeviceKit — essas são mudanças separadas e futuras (`add-dev-event-panel`, `add-dat-session-mockdevice`).
- `di/AppContainer.kt` ganha uma linha de fiação nova: `val pickingRepository: PickingRepository = MockPickingRepository()`. `PickingActor` ainda não é exposto pelo container — a próxima mudança (`add-dev-event-panel`) conecta ele a uma tela real e decide seu dono de ciclo de vida.
