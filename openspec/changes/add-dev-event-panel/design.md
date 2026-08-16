## Context

Ver proposal.md - Why. `PickingActor` e `MockPickingRepository` já existem e passam em testes de unidade (mudança `add-state-machine-and-mock-data`), mas nada os conecta a uma `Activity` real ainda. O `design.md` daquela mudança deixou explicitamente em aberto quem seria o dono do `CoroutineScope` do ator — esta mudança decide isso.

## Goals / Non-Goals

**Goals:**
- Provar o padrão de ator (`Channel` + corrotina única) num processo Android real, não só na JVM de teste.
- Decidir e implementar o dono do `CoroutineScope` de `PickingActor`.
- Permitir andar o fluxo linear de um item inteiro via toques, usando dados reais do `PickingRepository` mockado.

**Non-Goals:**
- Input real de voz ou câmera — isso são mudanças futuras (`add-audio-source-abstraction`, `add-vision-decode-cascade`).
- Cobertura de todo `PickingEvent` possível como botão — só o fluxo linear feliz de um item mais dois eventos transversais.
- Persistir estado de UI entre mortes de processo — não é necessário pra uma ferramenta de desenvolvimento.
- A tela espelho real do §12 — esta é um substituto temporário, não o design final.

## Decisions

**`PickingActor` recebe um `CoroutineScope` de escopo de aplicação (`SupervisorJob() + Dispatchers.Default`), criado uma única vez em `AppContainer`, não um `viewModelScope`.** O §4.3 do doc associa o ator conceitualmente à sessão DAT, que em mudanças futuras será de escopo de processo — uma sessão Bluetooth/câmera ativa não deveria resetar só porque a `Activity` foi recriada (ex: rotação de tela). Usar `viewModelScope` amarraria a vida do ator a uma tela só e perderia eventos/estado em toda mudança de configuração. Alternativa considerada: `CoroutineScope` por `Activity` via `rememberCoroutineScope` — rejeitada porque exigiria nova fiação em toda mudança futura que tocar sessão/áudio.

**`DevPanelViewModel` recebe `PickingActor` e `PickingRepository` do `AppContainer`, expõe um único `StateFlow<DevPanelUiState>`.** O `ViewModel` combina `pickingActor.state` com os dados da ordem mockada (carregados uma vez no `init`, não re-buscados a cada evento) — mantém a tela sempre sincronizada com uma única fonte de verdade sem lógica de UI duplicada.

**Cobertura de botões: fluxo linear de um item + dois eventos transversais, não todo `PickingEvent`.** O objetivo é provar o ator e exercitar tanto o caminho linear quanto o transversal, não construir uma ferramenta de teste exaustiva. Cobertura completa de evento não é o objetivo — mudanças futuras (áudio, visão) vão disparar eventos a partir de input bruto, não de botões.

**DI manual também para `DevPanelViewModel`.** Construído via um `ViewModelProvider.Factory` manual que recebe o `AppContainer` — mesma convenção já em uso no projeto, sem introduzir Hilt para `ViewModel`s.

## Risks / Trade-offs

**Mapeamento hardcoded de botão → evento pode divergir do reducer se a semântica mudar.** → Lista de eventos do `DevPanelViewModel` fica pequena e colocada junto da tela; se o reducer mudar de forma incompatível, o próprio build quebra (construtor de evento inexistente), o que já sinaliza a necessidade de atualizar o painel.

**`CoroutineScope` de aplicação nunca é cancelado.** → Aceitável pra um app de processo único, protótipo de hackathon; sinalizado aqui como decisão a revisitar se o app algum dia precisar de múltiplos processos ou teardown limpo.

## Open Questions

- A mudança futura de sessão DAT (`add-dat-session-mockdevice`) deve substituir esse `CoroutineScope` de aplicação por um escopo próprio da sessão, ou reusar o mesmo? Não afeta as specs ou tasks desta mudança — decisão adiada pra quando a sessão DAT existir de fato.
