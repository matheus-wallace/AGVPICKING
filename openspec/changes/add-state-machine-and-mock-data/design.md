## Context

Esta é a primeira mudança do projeto (ver proposal.md - Why). Ela introduz o padrão de ator no qual toda mudança futura (painel de eventos de dev, sessão DAT, pipeline de áudio, pipeline de visão) vai postar eventos, então a forma decidida aqui é estrutural pro resto do roadmap. Não existe código ainda além do scaffold `AgvPickVoice` (pacotes `domain/`, `data/` vazios, um `AppContainer` manual).

## Goals / Non-Goals

**Goals:**
- Um `PickingReducer` que é uma função pura, testável com JUnit puro, sem dependência de Android/corrotina.
- Um `PickingActor` que prova que o padrão de `Channel` de consumidor único do doc §4.3 compila e se comporta corretamente sob envio concorrente.
- Um `PickingRepository` + `MockPickingRepository` com dados estruturalmente realistas, prontos pros cenários de `EscaneandoProduto`/quantidade/check digit em mudanças futuras.

**Non-Goals:**
- Conectar o ator a qualquer input real (voz, câmera, UI) — isso é `add-dev-event-panel` e mudanças futuras.
- Decidir o dono de ciclo de vida do ator (qual `CoroutineScope` inicia/para ele) — `PickingActor` recebe um `CoroutineScope` como parâmetro de construtor e permanece agnóstico; quem conectar ele no `AppContainer`/ciclo de vida de uma `Activity` decide isso na próxima mudança.
- Validação completa de check digit ou quantidade — só a forma do estado e os modelos de dados necessários pra representá-los.

## Decisions

**`PickingState` e `PickingEvent` como `sealed interface`, não `sealed class` nem enum.** Estados e eventos diferem em formato (ex: `AguardandoCheckDigit` não precisa de payload, o evento de quantidade errada em `ConfirmandoQuantidade` carrega o valor digitado) — `sealed interface` deixa cada variante ser seu próprio `data object`/`data class` sem forçar um construtor comum. Um enum não carrega payload; uma única hierarquia `sealed class` força um construtor base artificial entre variantes que não compartilham campos.

**`PickingReducer` como função pura de topo `(PickingState, PickingEvent) -> PickingState`, não uma classe com campos mutáveis.** Mantém trivialmente testável (chama, confere o resultado) e mantém `PickingActor` como o único lugar com estado mutável — de acordo com o invariante do doc de que transições só acontecem pelo ator (§3.4.2, "nada é registrado sem readback confirmado" implica ausência de escrita por canal lateral).

**`PickingActor` usa `Channel<PickingEvent>(capacity = Channel.UNLIMITED)`.** Um channel de rendezvous ou capacidade fixa pequena deixaria uma rajada de eventos transversais (ex: duplo toque rápido disparando tanto `"repetir"` quanto um resultado real de ASR) aplicar backpressure nos produtores na thread de áudio — exatamente a thread que o doc §4.2 diz que nunca pode bloquear em nada além do próprio loop de frame. Capacidade ilimitada do lado do consumidor não custa nada nessa taxa de evento (dígitos únicos de eventos/segundo) e mantém produtores sempre não-bloqueantes.

**Dados mockados moram em `data/mock/MockPickingRepository`, modelos em `data/model/`.** Combina com o layout de pacotes já acordado nos TODOs do `AppContainer.kt` e a própria sugestão de pacote do doc — nenhum pacote de topo novo introduzido.

## Risks / Trade-offs

**Channel ilimitado pode esconder um bug de produtor que inunda de eventos.** → Log estruturado (uma mudança futura, `add-structured-logging`) vai tornar uma inundação de eventos visível na prática; não vale a pena limitar o channel preventivamente quando nenhum produtor existe ainda pra se comportar mal.

**Adiar a posse do `CoroutineScope` do ator pode deixar a próxima mudança escolher algo estranho (ex: `GlobalScope`).** → O design.md de `add-dev-event-panel` precisa justificar explicitamente sua escolha de escopo (provavelmente `viewModelScope` de um `ViewModel`, ou um `CoroutineScope` de escopo de `Application` se o ator precisar sobreviver a uma tela só); sinalizado aqui pra não passar batido.

## Open Questions

- O `PickingActor` deve sobreviver à recriação da `MainActivity` (ex: rotação) ou reiniciar por Activity? Depende de onde a posse da sessão/áudio vai acabar morando (§4.3 associa o ator conceitualmente à sessão DAT, que é de escopo de processo, não de Activity) — adiado pra `add-dev-event-panel`, não afeta as specs ou tasks desta mudança.
