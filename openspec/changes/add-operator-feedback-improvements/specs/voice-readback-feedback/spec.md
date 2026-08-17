## Purpose

Dar ao operador uma fala clara em `ReadbackQuantidade`, hoje silencioso, para que ele saiba que precisa confirmar ou corrigir a quantidade por voz sem depender da tela.

## ADDED Requirements

### Requirement: Sistema fala a quantidade lida de volta
Ao entrar em `ReadbackQuantidade`, o sistema DEVE (MUST) falar a quantidade informada para confirmação, no formato "Confirma {quantidade}?".

#### Scenario: Entrada em readback fala a quantidade
- **WHEN** o ator entra em `ReadbackQuantidade` com `quantidadeInformada = 12`
- **THEN** o sistema fala "Confirma 12?"

### Requirement: Correção reforça a quantidade esperada
Ao voltar para `ConfirmandoQuantidade` por `ReadbackCorrecaoSolicitada`, o sistema DEVE (MUST) repetir por voz a quantidade esperada da linha, não só silenciosamente aguardar nova fala.

#### Scenario: Correção solicitada
- **WHEN** o operador fala "corrigir" em `ReadbackQuantidade`
- **THEN** o ator volta a `ConfirmandoQuantidade`
- **AND** o sistema fala novamente a quantidade esperada da linha.
