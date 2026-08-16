## MODIFIED Requirements

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

#### Scenario: Leitura pelo stream conclui o escaneamento sem captura de foto
- **WHEN** o estado é `EscaneandoProduto` e um evento de decodificação concluída é recebido, sem que nenhum evento de captura disparada o tenha precedido
- **THEN** o estado se torna `ValidandoContraDados` carregando o código lido, sem passar por `DecodificandoProduto`

#### Scenario: Validação divergente encaminha pro tratamento de exceção
- **WHEN** o estado é `ValidandoContraDados` e o resultado da validação diverge dos dados mockados esperados
- **THEN** o estado se torna `TratandoExcecao`

#### Scenario: Correção de quantidade volta pra confirmação
- **WHEN** o estado é `ReadbackQuantidade` e um evento de "corrigir" é recebido
- **THEN** o estado volta para `ConfirmandoQuantidade`

#### Scenario: Conclusão do último item move pra conferência final
- **WHEN** o estado é `ItemConcluido` e não restam itens na ordem
- **THEN** o estado se torna `ConferenciaFinal`
