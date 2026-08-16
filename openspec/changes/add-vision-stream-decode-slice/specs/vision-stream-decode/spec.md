## Purpose

Define o comportamento observável da leitura de código de barras a partir do stream de câmera do óculos: quando a câmera pode estar ligada, o que acontece com o frame depois de examinado, e o que o sistema publica quando um código é lido — sem amarrar biblioteca de decodificação nem hardware específico.

## ADDED Requirements

### Requirement: Câmera ligada apenas no estado de escaneamento
O sistema DEVE manter o stream de câmera ativo exclusivamente enquanto o fluxo de picking estiver no estado de escaneamento de produto, e DEVE encerrá-lo ao sair desse estado, por qualquer motivo — leitura bem-sucedida, pausa, exceção ou perda de conexão.

#### Scenario: Entrada no escaneamento liga a câmera
- **WHEN** o fluxo de picking entra no estado de escaneamento de produto
- **THEN** o stream de câmera é iniciado e passa a entregar frames para análise

#### Scenario: Saída do escaneamento desliga a câmera
- **WHEN** o fluxo de picking sai do estado de escaneamento de produto, seja porque o código foi lido, seja porque a sessão foi pausada ou interrompida
- **THEN** o stream de câmera é encerrado e nenhum frame adicional é analisado

#### Scenario: Fora do escaneamento a câmera nunca liga
- **WHEN** o fluxo de picking está em qualquer estado que não seja o escaneamento de produto
- **THEN** nenhum stream de câmera está ativo, independente de a sessão do óculos estar viva

### Requirement: Código lido no stream é publicado como evento
Enquanto o stream estiver ativo, ao reconhecer um código de barras, o sistema DEVE publicar o conteúdo lido como evento de decodificação concluída para o ator único de picking, exatamente uma vez por escaneamento. O sistema NÃO DEVE publicar um valor lido num único frame: um mesmo conteúdo DEVE ser reconhecido em frames consecutivos antes de valer como leitura, e uma leitura divergente no meio DEVE reiniciar essa contagem.

#### Scenario: Código legível no campo de visão é lido
- **WHEN** um código de barras suportado está visível e legível nos frames entregues pelo stream
- **THEN** o conteúdo do código é publicado como evento de decodificação concluída, produzindo a mesma transição observável que a ação equivalente do painel de dev já produz

#### Scenario: Leitura isolada não é suficiente
- **WHEN** um conteúdo é reconhecido em um único frame e não se repete no frame seguinte
- **THEN** nenhum evento é publicado, e a contagem de confirmações recomeça a partir do que for reconhecido depois

#### Scenario: Código permanece no campo de visão depois de lido
- **WHEN** o mesmo código continua visível nos frames seguintes ao da leitura
- **THEN** nenhum evento adicional de decodificação é publicado para aquele escaneamento

#### Scenario: Nada legível no campo de visão
- **WHEN** os frames entregues não contêm nenhum código de barras suportado
- **THEN** nenhum evento é publicado e o sistema continua analisando os frames seguintes enquanto o estado de escaneamento durar

### Requirement: Frame completo é descartado antes de qualquer análise
O sistema DEVE reter apenas a região central de interesse de cada frame e DEVE liberar o frame completo antes de executar qualquer passo de decodificação, inclusive quando a análise falhar ou lançar erro. O sistema NÃO DEVE gravar nenhum frame, completo ou recortado, em armazenamento persistente.

#### Scenario: Liberação acontece mesmo quando a análise falha
- **WHEN** a análise de um frame termina com erro
- **THEN** o frame completo já foi liberado antes do início da análise, e a liberação não depende de coleta de lixo nem do sucesso do passo seguinte

#### Scenario: Nenhum frame é persistido
- **WHEN** um escaneamento inteiro acontece, com ou sem leitura bem-sucedida
- **THEN** nenhum arquivo de imagem é criado pelo aplicativo em armazenamento interno, externo, cache ou galeria

### Requirement: Região de interesse e qualidade do stream são configuráveis sem recompilar
O sistema DEVE obter a fração de recorte da região de interesse, a resolução e a taxa de quadros do stream de uma configuração ajustável no próprio aparelho, e DEVE operar com valores padrão equivalentes aos de produção quando essa configuração estiver ausente.

#### Scenario: Configuração ausente não muda comportamento
- **WHEN** o aplicativo é iniciado sem nenhum arquivo de configuração de visão presente no aparelho
- **THEN** o stream opera na resolução, taxa de quadros e fração de recorte padrão, sem erro nem aviso

#### Scenario: Configuração presente altera o recorte sem nova instalação
- **WHEN** uma fração de recorte diferente é informada na configuração do aparelho e o aplicativo é reiniciado
- **THEN** o novo valor passa a valer para os frames analisados, sem exigir uma nova instalação do aplicativo

### Requirement: Ausência de permissão de câmera degrada sem derrubar o fluxo
Quando a permissão de câmera — do sistema operacional ou do dispositivo vestível — não estiver concedida, o sistema NÃO DEVE interromper o fluxo de picking nem impedir as demais fontes de evento de operar.

#### Scenario: Permissão negada mantém o restante do fluxo utilizável
- **WHEN** o estado de escaneamento de produto é alcançado sem permissão de câmera concedida
- **THEN** nenhum stream é iniciado, nenhum evento de decodificação é publicado, e o fluxo continua operável pelas demais fontes de evento já existentes

### Requirement: Análise de frames roda isolada do ator e da UI
O sistema DEVE decodificar e analisar frames fora da coroutine do ator de picking e fora da thread de UI, de forma que a análise nunca bloqueie a renderização de tela nem o processamento de outros eventos já enfileirados no ator.

#### Scenario: Análise em andamento não trava a tela
- **WHEN** frames estão sendo analisados continuamente durante um escaneamento
- **THEN** a tela continua respondendo a toques e refletindo o estado atual do ator normalmente

#### Scenario: Frames chegam mais rápido do que a análise consegue processar
- **WHEN** o stream entrega frames enquanto uma análise anterior ainda não terminou
- **THEN** os frames excedentes são descartados em vez de acumulados, e a leitura segue funcionando com os frames seguintes
