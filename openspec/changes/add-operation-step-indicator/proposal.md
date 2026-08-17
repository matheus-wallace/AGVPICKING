## Why

A tela de operação (`add-unified-picking-operation-screen`, commit `11e54dd`) já troca o conteúdo do cartão central conforme o `PickingState` avança, agrupado internamente em quatro baldes (`EtapaOperacao.ENDERECO/PRODUTO/QUANTIDADE/MENSAGEM`), mas esse agrupamento nunca vira um rótulo visível para o operador. O balde `QUANTIDADE` sozinho cobre quatro estados bem diferentes (`ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho`, `ItemConcluido`) sob o mesmo cabeçalho de seção ("Quantidade"), então o operador só descobre o que fazer lendo a instrução corrida — sem um nome de etapa fixo para se orientar, é fácil perder o fio da sequência numa operação hands-free onde não há para onde voltar o olhar além da tela.

## What Changes

- Novo campo em `OperationUiState` com o nome legível da etapa atual (mais granular que o `EtapaOperacao` de quatro baldes hoje usado só para escolher o conteúdo do cartão) — cobre também os estados fora dos quatro baldes (sessão, pausa, erro, exceção, conferência final, ordem concluída).
- `OperationScreen` exibe esse nome de forma proeminente, perto do cabeçalho onde já ficam `progresso` ("Item X de N") e `situacao` ("Sessão ativa").
- Nenhuma mudança em `EtapaOperacao` (continua decidindo qual `Conteudo*` renderizar), em `PickingReducer`, em voz ou em visão — é só um rótulo novo derivado do mesmo `PickingState` que `ProjetorDeOperacao` já projeta.

## Capabilities

### Modified Capabilities

- `unified-picking-operation-screen`: a tela ganha um indicador textual de etapa, granular por `PickingState`, além dos quatro baldes de conteúdo já existentes.

## Impact

- Código alterado: `ui/operation/OperationUiState.kt` (novo campo), `ui/operation/ProjetorDeOperacao.kt` (preenche o campo por estado), `ui/operation/OperationScreen.kt` (exibe o rótulo).
- Sem mudança em `PickingReducer`, `PickingActor`, áudio ou visão. Sem novo estado, sem novo evento.
- Teste afetado: `ProjetorDeOperacaoTest.kt` ganha casos novos para o rótulo.
