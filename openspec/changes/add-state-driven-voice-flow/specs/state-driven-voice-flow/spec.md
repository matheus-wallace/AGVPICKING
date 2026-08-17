# state-driven-voice-flow Specification

## Purpose

Permitir que um operador conclua a separação normal de uma ordem já selecionada usando
voz, leitura óptica e transições automáticas, sem depender de botões de avanço.

## ADDED Requirements

### Requirement: Gramática depende do estado operacional

O sistema DEVE (MUST) selecionar uma gramática fechada e um perfil de endpoint a partir
do `PickingState` atual. Uma troca de estado DEVE (MUST) invalidar qualquer resultado ASR
iniciado sob a configuração anterior.

#### Scenario: Resultado atrasado não avança o próximo estado

- **WHEN** o ator muda de `ConfirmandoQuantidade` para `ReadbackQuantidade` antes de um
  resultado final de quantidade ser entregue
- **THEN** o resultado atrasado é descartado
- **AND** nenhum evento novo é enviado ao ator.

### Requirement: Voz dirige os passos manuais normais da separação

Depois de a ordem estar selecionada, o sistema DEVE (MUST) aceitar por voz as ações
manuais previstas para navegação, chegada, check digit, quantidade, readback, alocação,
próximo item e fechamento, publicando o `PickingEvent` existente correspondente.

#### Scenario: Quantidade é confirmada sem toque

- **WHEN** o estado é `ConfirmandoQuantidade` e o operador fala uma quantidade válida
- **THEN** o sistema publica `QuantidadeInformada`
- **AND** o ator entra em `ReadbackQuantidade`.

#### Scenario: Readback protege o registro

- **WHEN** o estado é `ReadbackQuantidade` e o operador fala “confirmar”
- **THEN** o sistema publica `ReadbackConfirmado`
- **AND** nenhuma coleta é registrada antes desse evento.

### Requirement: Check digit falado é validado localmente

O sistema DEVE (MUST) comparar o check digit reconhecido com o dado operacional esperado
antes de enviar o evento ao ator. O valor esperado NÃO DEVE (MUST NOT) aparecer na saída
de áudio nem em logs de diagnóstico, em nenhum build. Em build de release, o valor
esperado também NÃO DEVE (MUST NOT) aparecer no painel. Em build de debug, o painel de
desenvolvimento PODE (MAY) exibir o valor esperado como apoio à depuração de bancada —
essa exceção é escopada exclusivamente ao build de debug.

#### Scenario: Check digit divergente

- **WHEN** o estado é `AguardandoCheckDigit` e os dígitos falados não correspondem ao
  dado esperado
- **THEN** o sistema publica `CheckDigitIncorreto`
- **AND** o ator retorna à navegação sem revelar os dígitos corretos.

#### Scenario: Painel de debug mostra o valor esperado, release não

- **WHEN** o app roda em build de debug e o estado é `AguardandoCheckDigit`
- **THEN** o painel de dev pode exibir o check digit esperado da linha
- **AND** em build de release esse mesmo valor nunca aparece no painel, na saída de áudio
  ou em log.

### Requirement: Sinônimo único para avanços sem dado novo

O sistema DEVE (MUST) aceitar "próximo" como sinônimo de "alocado" em
`AlocandoCarrinho` e de "confirmar" em `ReadbackQuantidade`, além da palavra original de
cada estado, reduzindo o vocabulário que o operador precisa lembrar para passos que
apenas avançam sem fornecer dado novo. Este ajuste não se estende a nenhum outro estado
nem à palavra "corrigir".

#### Scenario: "Próximo" aloca o carrinho

- **WHEN** o estado é `AlocandoCarrinho` e o operador fala "próximo"
- **THEN** o sistema publica `ItemAlocado`, o mesmo evento de quando fala "alocado".

#### Scenario: "Próximo" confirma o readback

- **WHEN** o estado é `ReadbackQuantidade` e o operador fala "próximo"
- **THEN** o sistema publica `ReadbackConfirmado`, o mesmo evento de quando fala
  "confirmar".

#### Scenario: Palavras originais continuam funcionando

- **WHEN** o operador fala "alocado" em `AlocandoCarrinho` ou "confirmar" em
  `ReadbackQuantidade`
- **THEN** o comportamento é idêntico ao existente antes deste ajuste, sem regressão.

### Requirement: Visão e voz mantêm responsabilidades separadas

O sistema NÃO DEVE (MUST NOT) aceitar comando de voz comum para confirmar um código em
`EscaneandoProduto`, `DecodificandoProduto` ou `ValidandoContraDados`. A confirmação do
produto permanece responsabilidade do pipeline de visão e validação.

#### Scenario: Código só vem do produtor óptico

- **WHEN** o operador fala uma sequência que se parece com um código durante o escaneamento
- **THEN** nenhum `DecodificacaoConcluida` é publicado pela camada de áudio.

### Requirement: Botões não são necessários no fluxo normal

O painel de desenvolvimento PODE (MAY) manter botões para diagnóstico, mas uma ordem
mockada de múltiplas linhas DEVE (MUST) poder ser concluída sem tocá-los após a seleção
inicial da ordem. A tela de divergência permanece um fallback por toque quando a voz
falhar.

#### Scenario: Ensaio hands-free completo

- **WHEN** o operador inicia uma ordem já selecionada e fornece as falas esperadas, e a
  câmera confirma os códigos dos itens
- **THEN** a ordem chega a `OrdemConcluida` sem acionamento de botão de avanço.
