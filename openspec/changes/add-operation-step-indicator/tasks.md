## 1. Contrato de dado

- [x] 1.1 Adicionar `nomeEtapa: String` a `OperationUiState.kt`.
- [x] 1.2 Preencher `nomeEtapa` em `ProjetorDeOperacao.kt`, dentro do `when (estado)` exaustivo
  já existente em `projetar()` (design.md - Decisão 1): um texto por estado, incluindo os dois
  sub-casos de `AguardandoCheckDigit` (`TipoCheckDigit.POSICAO`/`PRODUTO`). O helper `mensagem()`
  passa a exigir `nomeEtapa` como parâmetro obrigatório, sem valor padrão (design.md - Risco).
- [x] 1.3 Garantir que nenhum `nomeEtapa` inclua o check digit esperado, o lote completo ou
  código ainda não confirmado — mesma restrição já aplicada ao resto do `OperationUiState`.

## 2. UI

- [x] 2.1 Exibir `nomeEtapa` em `OperationScreen.kt`, no cabeçalho, junto de `progresso` e
  `situacao`.

## 3. Verificação

- [x] 3.1 Testes em `ProjetorDeOperacaoTest.kt`: cada um dos quatro estados do balde
  `QUANTIDADE` (`ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho`,
  `ItemConcluido`) produz `nomeEtapa` distinto; os dois sub-casos de `AguardandoCheckDigit`
  produzem `nomeEtapa` distinto; nenhum `nomeEtapa` de `AguardandoCheckDigit` contém o valor
  esperado dos dígitos.
- [x] 3.2 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/`. 196 testes, 0 falhas (193 antes desta fatia); build e lint limpos
  (17 avisos preexistentes, 0 erros, nenhum nos arquivos alterados).
- [x] 3.3 Em aparelho (por toque via painel de dev, sem exigir voz humana), percorrer a
  sequência completa de uma ordem mockada e confirmar visualmente — por
  `adb exec-out screencap` — que o rótulo de etapa muda em cada transição de estado.
  Percorrido no SM-G780F em 17/08/2026 pela ordem mockada 408176, dirigido por `adb`
  (`uiautomator dump` + `input tap`), 12 passos de `AguardandoOrdem` a `NavegandoParaEndereco` do
  item seguinte. O rótulo mudou em cada transição de estado e **todo** rótulo lido pertence ao
  conjunto dos 20 definidos no projetor — nenhum texto estranho, nenhum vazio. Os quatro estados
  do balde `QUANTIDADE`, que é o motivo desta fatia, apareceram com quatro rótulos distintos:
  "Coleta e contagem", "Confirmação da quantidade", "Alocação no carrinho" e "Item concluído".
  `AguardandoCheckDigit` foi conferido à parte, com a tela assentada: mostra "Validação da
  posição" e a senha `93` não aparece em nenhum texto da tela (`screencap` guardado na sessão).

  Nota de método, não defeito do app: ler o `PickingState` no painel e o rótulo na tela de
  operação são dois momentos distintos, e o `SharingStarted.WhileSubscribed(5s)` do
  `OperationViewModel` mantém o último valor enquanto a tela fica escondida — numa das leituras
  isso devolveu o rótulo do passo anterior. Repetir a medição com a tela assentada mostrou o
  rótulo correto; quem verificar de novo deve dar um tempo depois de trocar de superfície.
