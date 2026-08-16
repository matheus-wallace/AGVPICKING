## 1. Modelo de estado e evento de picking

- [ ] 1.1 Criar `domain/statemachine/PickingState.kt` — `sealed interface PickingState` com os 19 estados da tabela do doc §3.1 (`Ocioso`, `Registrando`, `PreparandoSessao`, `AguardandoOrdem`, `OrdemCarregada`, `NavegandoParaEndereco`, `AguardandoCheckDigit`, `EscaneandoProduto`, `DecodificandoProduto`, `VerificacaoAssistida`, `ValidandoContraDados`, `ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho`, `ItemConcluido`, `TratandoExcecao`, `ConferenciaFinal`, `OrdemConcluida`, `SessaoPausada`, `Erro`). Dar aos estados com payload (ex: `Erro` precisa de uma causa, `TratandoExcecao` precisa de contexto) seus campos já agora, mesmo que nada os produza ainda.
- [ ] 1.2 Criar `domain/statemachine/PickingEvent.kt` — `sealed interface PickingEvent` cobrindo o fluxo linear (§3.2) e os eventos transversais (§3.3): parar/emergência, repetir, gatilho de exceção, pausa do DAT, perda de BT, mais eventos de avanço/rejeição por estado (ordem confirmada, check digit certo/errado, sucesso/falha de decodificação, quantidade confirmada/corrigida, etc).

## 2. Reducer

- [ ] 2.1 Criar `domain/statemachine/PickingReducer.kt` — `fun reduce(state: PickingState, event: PickingEvent): PickingState` pura, implementando o fluxo linear (diagrama §3.2) e as transições transversais (§3.3), aplicáveis a partir de qualquer estado operacional.
- [ ] 2.2 Testar unitariamente cada cenário de `specs/picking-state-machine/spec.md` em `AgvPickVoice/app/src/test/.../domain/statemachine/PickingReducerTest.kt` — um teste por `#### Scenario`.

## 3. Fiação de ator único

- [ ] 3.1 Criar `domain/statemachine/PickingActor.kt` — recebe um `CoroutineScope`, possui um `Channel<PickingEvent>(Channel.UNLIMITED)`, inicia uma corrotina que consome o channel e aplica `PickingReducer.reduce`, expõe `val state: StateFlow<PickingState>` e `fun send(event: PickingEvent)`.
- [ ] 3.2 Testar unitariamente (usando `kotlinx-coroutines-test`) que: (a) eventos enviados concorrentemente por múltiplas corrotinas são todos aplicados, um de cada vez, na ordem de envio; (b) `state` reflete a saída do reducer depois de cada evento.

## 4. Camada de dados mockados

- [ ] 4.1 Criar `data/model/` — `Endereco`, `Linha`, `Coleta`, `MetodoValidacao`, `Operador`, `Ordem`, `ResumoOrdem`, `Excecao`, `Conferencia` conforme o doc §11.2.
- [ ] 4.2 Criar `data/PickingRepository.kt` — a interface do §11.1 (`operadorAtual`, `ordensDisponiveis`, `ordem`, `registrarColeta`, `registrarExcecao`, `fecharConferencia`), todos `suspend fun`.
- [ ] 4.3 Criar `data/mock/MockPickingRepository.kt` — implementação em memória, ao menos uma `Ordem` com 2-3 `Linha` usando formatos de SKU/GTIN/lote estruturalmente realistas (§11.4), zero chamada de rede em qualquer lugar da classe.
- [ ] 4.4 Testar unitariamente cada cenário de `specs/mock-picking-data/spec.md` em `AgvPickVoice/app/src/test/.../data/MockPickingRepositoryTest.kt`.

## 5. Fiação

- [ ] 5.1 Adicionar `val pickingRepository: PickingRepository = MockPickingRepository()` em `di/AppContainer.kt`, substituindo o comentário TODO atual de `#mock-repository`.
- [ ] 5.2 Deixar `PickingActor` sem conectar no `AppContainer` por enquanto — adicionar um comentário TODO referenciando `add-dev-event-panel` como a mudança que decide seu dono de `CoroutineScope` (ver design.md - Open Questions).

## 6. Verificação

- [ ] 6.1 Rodar `./gradlew testDebugUnitTest` a partir de `AgvPickVoice/` — todos os testes novos passam, nenhum teste existente quebra.
- [ ] 6.2 Rodar `./gradlew assembleDebug` — confirma que o scaffold ainda compila com o código novo de `domain/`/`data/` no lugar (sem fiação de UI ainda, então sem teste manual em dispositivo pra esta mudança).
