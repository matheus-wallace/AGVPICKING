## 1. Contrato de dado

- [ ] 1.1 Adicionar `nomeEtapa: String` a `OperationUiState.kt`.
- [ ] 1.2 Preencher `nomeEtapa` em `ProjetorDeOperacao.kt`, dentro do `when (estado)` exaustivo
  já existente em `projetar()` (design.md - Decisão 1): um texto por estado, incluindo os dois
  sub-casos de `AguardandoCheckDigit` (`TipoCheckDigit.POSICAO`/`PRODUTO`). O helper `mensagem()`
  passa a exigir `nomeEtapa` como parâmetro obrigatório, sem valor padrão (design.md - Risco).
- [ ] 1.3 Garantir que nenhum `nomeEtapa` inclua o check digit esperado, o lote completo ou
  código ainda não confirmado — mesma restrição já aplicada ao resto do `OperationUiState`.

## 2. UI

- [ ] 2.1 Exibir `nomeEtapa` em `OperationScreen.kt`, no cabeçalho, junto de `progresso` e
  `situacao`.

## 3. Verificação

- [ ] 3.1 Testes em `ProjetorDeOperacaoTest.kt`: cada um dos quatro estados do balde
  `QUANTIDADE` (`ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho`,
  `ItemConcluido`) produz `nomeEtapa` distinto; os dois sub-casos de `AguardandoCheckDigit`
  produzem `nomeEtapa` distinto; nenhum `nomeEtapa` de `AguardandoCheckDigit` contém o valor
  esperado dos dígitos.
- [ ] 3.2 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/`.
- [ ] 3.3 Em aparelho (por toque via painel de dev, sem exigir voz humana), percorrer a
  sequência completa de uma ordem mockada e confirmar visualmente — por
  `adb exec-out screencap` — que o rótulo de etapa muda em cada transição de estado.
