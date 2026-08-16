## Purpose

Define o comportamento observável de uma fonte de áudio substituível e de um reconhecimento de comando por voz restrito a uma gramática fixa, de forma que falar um dos comandos suportados produza o mesmo `PickingEvent` transversal que hoje só o painel de dev dispara, sem exigir hardware real de óculos.

## ADDED Requirements

### Requirement: Fonte de áudio é substituível sem mudar o reconhecimento
O sistema DEVE obter as amostras de áudio através de uma abstração de fonte, de forma que o restante do pipeline de reconhecimento não saiba nem dependa de qual captura de hardware está por trás.

#### Scenario: Amostras chegam no formato esperado pelo reconhecimento
- **WHEN** a fonte de áudio simulada está ativa
- **THEN** o reconhecimento recebe um fluxo contínuo de amostras na taxa declarada pela fonte, já com a degradação de canal (faixa de frequência estreita, downsample) aplicada, sem precisar saber que a origem é o microfone do celular

### Requirement: Comando de voz reconhecido produz o evento correspondente
Enquanto a captura de áudio estiver ativa, ao pronunciar um dos comandos da gramática fixa suportada nesta fatia, o sistema DEVE publicar o `PickingEvent` transversal correspondente para o ator único de picking, dentro da janela de silêncio do perfil de comando curto.

#### Scenario: Comando "parar" reconhecido
- **WHEN** o operador fala "parar" perto da fonte de áudio ativa, seguido de silêncio
- **THEN** o evento de comando de parar é publicado no ator, produzindo a mesma transição observável que o botão correspondente do painel de dev já produz

#### Scenario: Comando "repetir" reconhecido
- **WHEN** o operador fala "repetir" perto da fonte de áudio ativa, seguido de silêncio
- **THEN** o evento de comando de repetir é publicado no ator, produzindo a mesma transição observável que o botão correspondente do painel de dev já produz

### Requirement: Fala fora da gramática não produz evento
O sistema NÃO DEVE publicar nenhum `PickingEvent` quando o áudio capturado não corresponder a nenhum dos comandos da gramática fixa suportada nesta fatia.

#### Scenario: Ruído ou fala não reconhecida é descartada
- **WHEN** a fonte de áudio captura ruído de fundo ou uma palavra fora da gramática suportada
- **THEN** nenhum `PickingEvent` é publicado no ator, e o reconhecimento continua ouvindo normalmente em seguida

### Requirement: Reconhecimento roda isolado do ator e da UI
O sistema DEVE processar o áudio e o reconhecimento de comando fora da coroutine do ator de picking e fora da thread de UI, de forma que a captura e o reconhecimento de um comando nunca bloqueiem a renderização de tela nem o processamento de outros eventos já enfileirados no ator.

#### Scenario: Reconhecimento em andamento não trava a tela
- **WHEN** um comando de voz está sendo processado pelo reconhecimento
- **THEN** a tela do painel de dev continua respondendo a toques e refletindo o estado atual do ator normalmente
