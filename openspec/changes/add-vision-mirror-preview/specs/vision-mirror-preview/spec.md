## Purpose

Permite que a equipe visualize, de forma local e efêmera, o enquadramento recebido dos óculos para diagnosticar distância, legibilidade e recorte durante o escaneamento.

## ADDED Requirements

### Requirement: Prévia espelho durante o escaneamento

O sistema DEVE (MUST) exibir no celular uma prévia ao vivo do vídeo recebido da câmera enquanto o estado de picking estiver em `EscaneandoProduto` e houver uma sessão de câmera ativa. A prévia DEVE (MUST) parar e deixar de mostrar imagem ao sair desse estado, ao encerrar a sessão, ao interromper o stream ou ao deixar de haver uma superfície de exibição válida.

#### Scenario: Escaneamento com sessão ativa

- **WHEN** o picking entra em `EscaneandoProduto` com uma sessão DAT ativa e a tela espelho está visível
- **THEN** o celular mostra uma prévia atualizada do stream de câmera

#### Scenario: Saída do escaneamento

- **WHEN** o picking deixa o estado `EscaneandoProduto`
- **THEN** a prévia para de receber e exibir a imagem do stream

### Requirement: Diagnóstico visual do recorte

O sistema DEVE (MUST) desenhar sobre a prévia uma moldura que represente a área central efetivamente analisada pelo decodificador. A moldura DEVE (MUST) refletir o fator de recorte de visão ativo, permitindo identificar visualmente quando um código está parcial ou totalmente fora da região analisada.

#### Scenario: Recorte padrão visível

- **WHEN** a prévia está sendo exibida com o fator de recorte padrão de 60%
- **THEN** a moldura central ocupa 60% da largura e 60% da altura visíveis da prévia

#### Scenario: Recorte calibrado

- **WHEN** o fator de recorte de visão é alterado pela configuração de calibração
- **THEN** a moldura exibida acompanha o novo fator de recorte

### Requirement: Telemetria de diagnóstico local

O sistema DEVE (MUST) apresentar, junto à prévia, o estado atual do stream, as dimensões efetivas do vídeo, a qualidade e taxa de quadros configuradas, além do último resultado de decodificação e da duração da última tentativa concluída. A ausência de tentativa ou de leitura DEVE (MUST) ser apresentada como estado não disponível, sem simular um resultado.

#### Scenario: Tentativa de leitura sem código

- **WHEN** uma tentativa de decodificação termina sem encontrar código
- **THEN** a tela mantém a prévia e informa que a última tentativa não produziu leitura, com sua duração

#### Scenario: Código confirmado

- **WHEN** o consenso de leitura confirma um código no stream
- **THEN** a tela exibe o código confirmado como último resultado de decodificação

### Requirement: Privacidade da prévia

O sistema NÃO DEVE (MUST NOT) gravar, enviar, compartilhar, incluir em logs ou reter em memória de longa duração frames completos, recortes ou capturas usados pela prévia. A imagem exibida DEVE (MUST) existir somente nos buffers transitórios necessários para a renderização local e ser liberada ao encerrar a prévia.

#### Scenario: Encerramento da prévia

- **WHEN** a prévia é encerrada por saída do escaneamento, interrupção do stream ou destruição da superfície
- **THEN** o sistema libera seus recursos de renderização e não deixa arquivo de imagem, vídeo ou recorte no armazenamento do aplicativo
