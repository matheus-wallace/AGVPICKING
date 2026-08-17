## Purpose

Indicar em tela qual palavra de voz o operador precisa dizer para avançar o passo atual,
sem exigir que ele decore o vocabulário.

## ADDED Requirements

### Requirement: Tela operacional indica a palavra de voz esperada

O sistema DEVE (MUST) exibir na tela operacional a palavra de voz que avança o estado
atual, em todo estado que espera uma fala de avanço.

#### Scenario: Dica exibida em estado com avanço por voz

- **WHEN** o estado atual espera uma fala de avanço (ex.: `NavegandoParaEndereco`
  esperando "cheguei")
- **THEN** a tela exibe a palavra esperada.

### Requirement: Nenhuma dica em estados sem avanço por voz

O sistema NÃO DEVE (MUST NOT) exibir uma dica de comando de voz em estados que avançam
por câmera, rede ou vocabulário aberto.

#### Scenario: Sem dica durante o escaneamento

- **WHEN** o estado atual é `EscaneandoProduto` (o avanço vem da câmera, não da voz)
- **THEN** nenhuma dica de comando de voz é exibida.
