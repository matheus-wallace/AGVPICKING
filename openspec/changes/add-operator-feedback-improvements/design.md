## Context

Ver `proposal.md` - Why. Achados de bancada de 17/08/2026, ainda não commitados em código:

- `ProjetorDeFalaPicking.kt` (branch de `ReadbackQuantidade`, `AlocandoCarrinho`,
  `ConferenciaFinal`, `SessaoPausada`) confirma retorno `null` — nenhuma fala nesses estados.
  Só `ReadbackQuantidade` é o alvo desta fatia; os outros três já têm cobertura textual na tela
  (`OperationScreen`) e não foram reportados como confusos.
- O reducer já resolve `ReadbackCorrecaoSolicitada` → `ConfirmandoQuantidade` preservando o item
  em andamento (`PickingReducer.kt:175-179`) — a fatia só precisa acrescentar a fala, não mudar
  transição nenhuma.
- `AjustesVisao.kt` tem `cooldownCapturaMs` (1500ms, intervalo entre tentativas de captura) e
  `timeoutOrientacaoMs` (8000ms, aviso de orientação incorreta) — nenhum dos dois é obviamente
  "a câmera fecha depois de N segundos". A causa relatada por Matheus (câmera "fecha" antes de
  ele terminar de abrir a caixa e contar) ainda não foi localizada no código nesta sessão; ver
  Open Questions.

## Goals / Non-Goals

**Goals:**
- Fala clara em `ReadbackQuantidade`, sem exigir leitura de tela.
- Log estruturado de 100% dos resultados finais do ASR, aceitos ou não, para medir taxa de
  reconhecimento por comando em bancada.
- Diagnosticar e corrigir a causa real do fechamento prematuro da câmera em `EscaneandoProduto`.

**Non-Goals:**
- Não é um retrabalho do pipeline de ASR/VAD em si (silêncio final, limiar dBFS) — isso já está
  documentado em [[reference-voz-bancada]] e é ajuste de calibração, não de arquitetura.
- Não adiciona um passo de leitura óptica da etiqueta de posição (a ideia de bipar a etiqueta na
  chegada, hoje substituída por check digit falado) — é uma mudança de fluxo maior, fica para
  decisão futura separada se o check digit falado não se provar suficiente.
- Não altera `PickingReducer`/`PickingEvent`/`PickingRepository`.

## Decisions

1. **Fala do readback é só mais um branch em `ProjetorDeFalaPicking`, mesmo padrão dos outros
   estados.** Nenhuma mudança estrutural — o projetor já é um `when` sobre `PickingState`
   retornando mensagem opcional; hoje `ReadbackQuantidade` retorna `null` só porque nunca foi
   preenchido, não por decisão deliberada registrada em nenhum design anterior.

2. **Log de ASR descartado usa o mesmo canal estruturado já usado para gramática/nível
   (`ReconhecedorDeComando`), não um canal novo.** Bancada já lê logcat nessa mesma convenção
   ([[reference-voz-bancada]]); um formato novo só para resultados descartados fragmentaria a
   leitura em vez de ajudar a medir precisão.

3. **A causa raiz do fechamento prematuro da câmera fica para diagnóstico amanhã, não para
   suposição hoje.** Duas hipóteses concorrentes, ambas plausíveis e com correções diferentes:
   (a) existe um teto de tempo que encerra `EscaneandoProduto` cedo demais — a correção é
   estender ou remover esse teto; (b) não há teto nenhum, e o que Matheus está vendo é uma
   leitura prematura/errada (algo no campo de visão antes da caixa estar pronta) que já satisfaz
   `ConsensoDeLeitura` e avança o estado sozinho — a correção seria outra (gating por sinal
   explícito do operador antes de aceitar leitura, não estender tempo). Decidir errado aqui
   custaria uma correção que não resolve o sintoma relatado.

## Risks / Trade-offs

- **[Risco]** Falar "Confirma {quantidade}?" produz uma fala nova exatamente no estado em que o
  TTS já compete com o ASR (Decisão 6 de `add-state-driven-voice-flow`) — precisa respeitar o
  mesmo gate de `SaidaDeAudio.falando` já existente, não introduzir uma janela nova de disputa.
  **Mitigação:** reaproveitar o mecanismo já implementado, não criar um novo.
- **[Risco]** Logar 100% dos resultados do ASR pode incluir texto sensível de exceção em
  `TratandoExcecao` (texto livre) em log de bancada. **Mitigação:** aplicar a mesma regra que já
  vale para o painel/áudio — nenhum valor protegido (check digit esperado) pode aparecer; texto
  livre do operador não é dado protegido pelo doc §9, mas vale confirmar no diagnóstico de
  amanhã antes de logar em produção (só bancada/debug por ora).

## Open Questions

- **Qual é a causa real do fechamento prematuro da câmera?** Precisa ler `ControladorDeVisao.kt`
  por completo (ciclo de vida da câmera por estado, o que dispara o fim da janela) antes de
  escrever tasks.md desta parte da fatia. Sem essa resposta, as tarefas de câmera desta fatia
  ficam como investigação, não implementação — a resposta muda o approach (Decisão 3).
