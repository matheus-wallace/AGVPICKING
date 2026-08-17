## ADDED Requirements

### Requirement: Tela mostra o nome da etapa atual

A tela operacional DEVE (MUST) exibir um rótulo textual identificando a etapa atual da separação, granular o suficiente para distinguir estados que hoje compartilham o mesmo cartão de conteúdo (por exemplo, informar quantidade, confirmar readback, alocar no carrinho e concluir o item, todos dentro do balde `QUANTIDADE`). O rótulo DEVE (MUST) cobrir também os estados fora dos três cartões operacionais (sessão, pausa, erro, exceção, conferência final, ordem concluída).

#### Scenario: Rótulo distingue sub-passos de quantidade

- **WHEN** o estado muda de `ConfirmandoQuantidade` para `ReadbackQuantidade` e depois para `AlocandoCarrinho`
- **THEN** o rótulo de etapa muda em cada transição
- **AND** nenhuma dessas três telas mostra o mesmo texto de etapa que outra.

#### Scenario: Rótulo acompanha o cartão de endereço e produto

- **WHEN** o estado é `NavegandoParaEndereco`, `AguardandoCheckDigit`, `EscaneandoProduto` ou `ValidandoContraDados`
- **THEN** o rótulo de etapa identifica claramente que a validação em curso é de endereço ou de produto, sem ambiguidade entre os dois.

#### Scenario: Rótulo cobre estados fora dos três cartões

- **WHEN** o estado é `SessaoPausada`, `TratandoExcecao`, `ConferenciaFinal` ou `OrdemConcluida`
- **THEN** o rótulo de etapa reflete essa condição, e não um nome de validação de endereço/produto/quantidade.

### Requirement: Rótulo de etapa não revela dado protegido

O rótulo de etapa NÃO DEVE (MUST NOT) conter o check digit esperado, o lote completo nem qualquer código ainda não confirmado — as mesmas restrições já aplicadas ao restante do `OperationUiState`.

#### Scenario: Rótulo durante o check digit

- **WHEN** o estado é `AguardandoCheckDigit`
- **THEN** o rótulo de etapa identifica que a validação de endereço está em curso
- **AND** não inclui o valor esperado dos dígitos.
