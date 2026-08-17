## 1. Comparador puro

- [x] 1.1 Criar `vision/ComparadorDeCodigo.kt`: observa `PickingActor.state` (`collectLatest`,
  `Dispatchers.Default`) e reage apenas a `ValidandoContraDados`.
- [x] 1.2 Curto-circuito do sentinela: se `codigoLido == CODIGO_CHECK_DIGIT_PRODUTO`, publicar
  `ValidacaoOk` direto, sem consultar o EAN (design.md - Decisão 4).
- [x] 1.3 Caso contrário, buscar a linha via `PickingRepository` e comparar `codigoLido` contra
  `linha.ean`: igual publica `ValidacaoOk(linha.quantidade)`; diferente publica
  `ValidacaoDivergente(MotivoExcecao.DIVERGENCIA)`.
- [x] 1.4 Guarda contra estado obsoleto: depois da consulta suspensa ao repositório, conferir se
  `PickingActor.state.value` ainda é o mesmo `ValidandoContraDados` que disparou a consulta antes
  de publicar o evento (design.md - Decisão 3).
- [x] 1.5 Linha do item não encontrada no repositório: não publicar evento algum, só logar a
  tentativa (mesmo padrão defensivo de `ResolvedorDeIntencao`).

## 2. Integração

- [x] 2.1 Instanciar e iniciar o `ComparadorDeCodigo` em `AppContainer.kt`, ao lado de
  `ControladorDeVisao`, injetando o `PickingRepository` já existente.
- [x] 2.2 Confirmar que os botões "Decodificação OK"/"Validação OK" do painel de dev continuam
  funcionando sem alteração — nenhuma mudança esperada em `DevPanelViewModel.kt`.

## 3. Verificação

- [x] 3.1 Testes de JVM contra `MockPickingRepository`, cobrindo: EAN correto, EAN divergente,
  sentinela do check digit de produto, resultado descartado por estado obsoleto e linha não
  encontrada.
- [x] 3.2 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/`. 166 testes, 0 falhas (160 antes desta fatia); build e lint limpos
  (16 avisos preexistentes, 0 erros, nenhum nos arquivos novos ou alterados).
- [x] 3.3 Em bancada: apontar a câmera para a caixa Loratamed (EAN `7896523202204`, ordem 408176,
  primeiro item) e confirmar que o app sai de `EscaneandoProduto` e chega em
  `ConfirmandoQuantidade` sem tocar no painel. Repetir apontando para um código diferente e
  confirmar que o app vai para `TratandoExcecao` sem revelar o EAN esperado em tela/áudio/log.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok — a câmera real contra a
  caixa Loratamed sai de `EscaneandoProduto` e chega em `ConfirmandoQuantidade` sem toque, e um
  código divergente vai para `TratandoExcecao` sem revelar o EAN esperado.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok.
- [x] 3.4 Em bancada: forçar o caminho de fallback (câmera indisponível → check digit de produto
  por voz) e confirmar que ele ainda chega em `ConfirmandoQuantidade` automaticamente, sem
  regressão do comportamento existente.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok — o fallback por check
  digit de produto falado chega em `ConfirmandoQuantidade` automaticamente.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok.
