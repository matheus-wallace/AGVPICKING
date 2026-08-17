## MODIFIED Requirements

### Requirement: Sinônimo único para avanços sem dado novo

O sistema DEVE (MUST) aceitar "próximo" como sinônimo de "alocado" em
`AlocandoCarrinho`, de "confirmar" em `ReadbackQuantidade`, de "iniciar" em
`OrdemCarregada`, de "cheguei" em `NavegandoParaEndereco`, de "concluir" em
`ConferenciaFinal` e de "encerrar" em `OrdemConcluida`, além da palavra original de cada
estado, reduzindo o vocabulário que o operador precisa lembrar para passos que apenas
avançam sem fornecer dado novo. Este ajuste não se estende a estados que não avançam por
uma única palavra de voz (`AguardandoCheckDigit`, que espera dois dígitos;
`ConfirmandoQuantidade`, que espera um número) nem
a estados que avançam por câmera ou rede, nem à palavra "corrigir".

#### Scenario: "Próximo" aloca o carrinho

- **WHEN** o estado é `AlocandoCarrinho` e o operador fala "próximo"
- **THEN** o sistema publica `ItemAlocado`, o mesmo evento de quando fala "alocado".

#### Scenario: "Próximo" confirma o readback

- **WHEN** o estado é `ReadbackQuantidade` e o operador fala "próximo"
- **THEN** o sistema publica `ReadbackConfirmado`, o mesmo evento de quando fala
  "confirmar".

#### Scenario: "Próximo" inicia a navegação

- **WHEN** o estado é `OrdemCarregada` e o operador fala "próximo"
- **THEN** o sistema publica o mesmo evento de quando fala "iniciar".

#### Scenario: "Próximo" confirma a chegada ao endereço

- **WHEN** o estado é `NavegandoParaEndereco` e o operador fala "próximo"
- **THEN** o sistema publica `EnderecoAlcancado`, o mesmo evento de quando fala "cheguei".

#### Scenario: "Próximo" conclui a conferência final

- **WHEN** o estado é `ConferenciaFinal` e o operador fala "próximo"
- **THEN** o sistema publica `ConferenciaConcluida`, o mesmo evento de quando fala
  "concluir".

#### Scenario: "Próximo" encerra a ordem

- **WHEN** o estado é `OrdemConcluida` e o operador fala "próximo"
- **THEN** o sistema publica `OrdemEncerrada`, o mesmo evento de quando fala "encerrar".

#### Scenario: Palavras originais continuam funcionando

- **WHEN** o operador fala a palavra original de qualquer um dos seis estados acima
  ("alocado", "confirmar", "iniciar", "cheguei", "concluir" ou "encerrar")
- **THEN** o comportamento é idêntico ao existente antes deste ajuste, sem regressão.

#### Scenario: "Próximo" não se estende a estados fora da lista

- **WHEN** o estado é `AguardandoCheckDigit` ou `ConfirmandoQuantidade`, e o operador fala
  "próximo"
- **THEN** nenhum evento é publicado, o mesmo comportamento de qualquer outra fala fora do
  contrato do estado.

### Requirement: A ocorrência tem saída por voz e por toque

`TratandoExcecao` NÃO DEVE (MUST NOT) depender apenas de um relato falado completo para ser
encerrado: é o estado em que o reconhecimento de voz pode estar justamente falhando, e a tela do
operador não tem botão de avanço, então um relato não reconhecido deixaria o operador sem
nenhuma forma de seguir (design.md - Decisão 12).

#### Scenario: Saída curta por voz

- **WHEN** o estado é `TratandoExcecao` e o operador fala "próximo"
- **THEN** o sistema publica `ExcecaoRegistrada`, o mesmo evento do relato falado
- **AND** o relato livre de três ou mais palavras continua funcionando sem regressão.

#### Scenario: Saída por toque na tela do operador

- **WHEN** o estado é `TratandoExcecao`
- **THEN** a tela do operador oferece uma ação de toque que publica `ExcecaoRegistrada`
- **AND** nenhum outro estado do fluxo ganha ação de toque para avançar.
