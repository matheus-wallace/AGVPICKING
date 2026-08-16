## Purpose

Define o comportamento de transição observável do fluxo de picking, de forma que toda fonte de input — voz, câmera, check digit, ciclo de vida da sessão, ou um painel de teste manual — dirija exatamente a mesma máquina de estados determinística, testável independentemente de qualquer fonte de input específica.

## ADDED Requirements

### Requirement: Transições de estado determinísticas
O sistema DEVE transicionar entre estados de picking somente em resposta a um `PickingEvent`. O estado resultante DEVE depender exclusivamente do estado anterior e do evento recebido, sem nenhum outro estado mutável influenciando o resultado.

#### Scenario: Confirmação de ordem inicia navegação
- **WHEN** o estado é `AguardandoOrdem` e um evento de ordem confirmada é recebido
- **THEN** o estado se torna `OrdemCarregada`, e um evento subsequente de início de navegação move para `NavegandoParaEndereco`

#### Scenario: Check digit errado repete a navegação
- **WHEN** o estado é `AguardandoCheckDigit` e um evento de check digit errado é recebido
- **THEN** o estado volta para `NavegandoParaEndereco` e o endereço anterior é repetido

#### Scenario: Check digit correto inicia o escaneamento
- **WHEN** o estado é `AguardandoCheckDigit` e um evento de check digit correto é recebido
- **THEN** o estado se torna `EscaneandoProduto`

#### Scenario: Validação divergente encaminha pro tratamento de exceção
- **WHEN** o estado é `ValidandoContraDados` e o resultado da validação diverge dos dados mockados esperados
- **THEN** o estado se torna `TratandoExcecao`

#### Scenario: Correção de quantidade volta pra confirmação
- **WHEN** o estado é `ReadbackQuantidade` e um evento de "corrigir" é recebido
- **THEN** o estado volta para `ConfirmandoQuantidade`

#### Scenario: Conclusão do último item move pra conferência final
- **WHEN** o estado é `ItemConcluido` e não restam itens na ordem
- **THEN** o estado se torna `ConferenciaFinal`

### Requirement: Eventos transversais têm precedência sobre qualquer estado operacional
A partir de qualquer estado operacional, o sistema DEVE honrar estas transições independente do estado atual: um evento de parar/emergência move para `SessaoPausada`; um evento de gatilho de exceção (avaria/ruptura/divergência) move para `TratandoExcecao`; um evento de pausa do DAT (hastes fechadas, óculos removidos, toque) move para `SessaoPausada`; um evento de perda de conexão Bluetooth move para `Erro`, preservando o item em andamento para retomada.

#### Scenario: Parada de emergência a partir de qualquer estado operacional
- **WHEN** um evento de parar/emergência é recebido enquanto o sistema está em qualquer estado operacional
- **THEN** o estado se torna `SessaoPausada`, independente de qual era o estado anterior

#### Scenario: Perda de Bluetooth preserva o item em andamento
- **WHEN** um evento de perda de conexão Bluetooth é recebido enquanto um item está em andamento
- **THEN** o estado se torna `Erro`, e o item sendo trabalhado permanece identificado para retomada assim que a máquina de estados se recuperar

### Requirement: Processamento sequencial único de eventos
O sistema DEVE processar todos os `PickingEvent`s através de exatamente um consumidor sequencial. Nenhum dois eventos DEVEM ser aplicados ao estado concorrentemente.

#### Scenario: Eventos são aplicados na ordem recebida
- **WHEN** dois eventos são enviados para a máquina de estados em sucessão imediata
- **THEN** o segundo evento só é aplicado depois que o estado resultante do primeiro evento foi totalmente computado e publicado, nunca intercalado com ele
