# unified-picking-operation-screen Specification

## Purpose

Apresentar em uma única tela operacional as validações de endereço, produto e quantidade da separação, mantendo o painel de debug como uma superfície distinta de desenvolvimento.

## ADDED Requirements

### Requirement: Uma tela reúne as três validações da separação

O aplicativo DEVE (MUST) apresentar uma única tela de operação que muda seu conteúdo a partir do `PickingState`, sem exigir navegação entre telas para validar endereço, produto e quantidade.

#### Scenario: Sequência de uma linha

- **WHEN** o fluxo passa de `NavegandoParaEndereco` para `EscaneandoProduto` e depois para `ConfirmandoQuantidade`
- **THEN** a mesma tela permanece ativa
- **AND** seu cartão principal passa a mostrar, respectivamente, a validação de endereço, produto e quantidade.

### Requirement: Validação de endereço protege o check digit

Durante `NavegandoParaEndereco` e `AguardandoCheckDigit`, a tela DEVE (MUST) mostrar o endereço e a instrução operacional, mas NÃO DEVE (MUST NOT) mostrar o check digit/senha esperado.

#### Scenario: Operador aguarda check digit

- **WHEN** o estado é `AguardandoCheckDigit`
- **THEN** a tela mostra que a confirmação por voz é aguardada
- **AND** não revela os dígitos que seriam aceitos.

### Requirement: Validação de produto usa prévia apenas enquanto necessária

Durante `EscaneandoProduto`, a tela DEVE (MUST) hospedar a prévia local da câmera com sua moldura de ROI e o status de leitura. Ao sair da validação de produto, a tela DEVE (MUST) remover a superfície de prévia e não exibir frame anterior.

#### Scenario: Produto confirmado

- **WHEN** o consenso de visão confirma um código e o fluxo deixa `EscaneandoProduto`
- **THEN** a prévia é removida
- **AND** a tela pode mostrar o código confirmado como resultado operacional, sem armazenar imagem ou frame.

### Requirement: Validação de quantidade mostra somente o contexto necessário

Durante `ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho` e `ItemConcluido`, a tela DEVE (MUST) apresentar produto, quantidade esperada ou entendida, instrução de readback quando aplicável e progresso da ordem.

#### Scenario: Readback de quantidade

- **WHEN** o estado é `ReadbackQuantidade`
- **THEN** a tela exibe a quantidade entendida e informa que a confirmação/correção é por voz
- **AND** não disponibiliza um botão de avanço do fluxo normal.

### Requirement: Painel de debug permanece disponível e separado

O aplicativo DEVE (MUST) abrir a tela operacional como superfície principal e manter o painel de debug acessível por uma entrada identificada como desenvolvimento. Alternar entre as superfícies NÃO DEVE (MUST NOT) criar sessão, reiniciar a escuta nem publicar um evento no ator.

#### Scenario: Retorno do debug para operação

- **WHEN** o desenvolvedor abre o painel de debug e retorna à operação durante uma sessão ativa
- **THEN** a tela operacional reflete o mesmo `PickingState` corrente
- **AND** nenhum evento adicional é aplicado ao fluxo.
