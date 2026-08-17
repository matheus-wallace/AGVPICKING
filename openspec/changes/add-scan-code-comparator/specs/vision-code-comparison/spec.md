## Purpose

Fecha o loop hands-free depois do escaneamento: compara automaticamente o código de barras lido pela câmera contra o dado esperado da linha da ordem mockada, e publica o resultado da validação no ator sem exigir toque do operador no painel de dev.

## ADDED Requirements

### Requirement: Comparação automática ao entrar em validação
O sistema DEVE (MUST) comparar automaticamente o `codigoLido` contra o EAN cadastrado na linha correspondente assim que o ator emitir o estado `ValidandoContraDados`, sem exigir nenhuma ação do operador.

#### Scenario: Código bate com o EAN esperado
- **WHEN** o ator está em `ValidandoContraDados` com `codigoLido` igual ao `ean` da linha do item em andamento
- **THEN** o sistema publica `PickingEvent.ValidacaoOk` com a quantidade esperada da linha, levando o ator a `ConfirmandoQuantidade`

### Requirement: Divergência não avança a operação silenciosamente
O sistema DEVE (MUST) publicar `ValidacaoDivergente` quando o código lido não corresponder ao EAN esperado, sem nunca revelar o valor esperado na saída de áudio, no painel ou em log.

#### Scenario: Código diverge do EAN esperado
- **WHEN** o ator está em `ValidandoContraDados` com `codigoLido` diferente do `ean` da linha do item em andamento
- **THEN** o sistema publica `PickingEvent.ValidacaoDivergente`, levando o ator a `TratandoExcecao`

### Requirement: Resultado obsoleto não é publicado
Quando o ator sair de `ValidandoContraDados` antes da consulta ao repositório terminar, o sistema NÃO DEVE (MUST NOT) publicar o resultado dessa consulta.

#### Scenario: Estado muda durante a consulta ao repositório
- **WHEN** o operador aciona um comando transversal (ex.: "parar") e o ator sai de `ValidandoContraDados` antes da comparação terminar
- **THEN** o sistema descarta o resultado da comparação e não publica `ValidacaoOk` nem `ValidacaoDivergente`

### Requirement: Fallback de check digit de produto não é comparado como EAN
Quando `ValidandoContraDados` for alcançado pelo fallback de check digit do produto (doc §7.2) em vez da leitura de câmera, o sistema DEVE (MUST) publicar `ValidacaoOk` diretamente — o check digit falado já confirmou o produto, e o código sentinela desse caminho nunca corresponde a um EAN real.

#### Scenario: ValidandoContraDados alcançado via check digit de produto
- **WHEN** o ator está em `ValidandoContraDados` com `codigoLido` igual ao sentinela do check digit de produto
- **THEN** o sistema publica `PickingEvent.ValidacaoOk` com a quantidade esperada da linha, sem comparar o código contra o EAN e sem gerar `ValidacaoDivergente`

### Requirement: Atalho manual de diagnóstico continua disponível
O painel de dev DEVE (MUST) continuar publicando `ValidacaoOk`/`ValidacaoDivergente` manualmente para diagnóstico, independente do comparador automático estar ativo.

#### Scenario: Uso do botão de diagnóstico durante ValidandoContraDados
- **WHEN** o operador aciona o botão "Validação OK" do painel de dev enquanto o ator está em `ValidandoContraDados`
- **THEN** o ator recebe o evento normalmente, sem conflito com o comparador automático
