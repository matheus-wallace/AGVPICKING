# photo-capture-decode-fallback Specification

## Purpose

Permitir que a leitura de produto escale de modo seguro e observável do stream
comprimido para uma foto de maior qualidade quando a tentativa pelo stream não for
suficiente para confirmar um código de barras.

## ADDED Requirements

### Requirement: Gatilho qualificado para captura

Durante `EscaneandoProduto`, o aplicativo DEVE (MUST) solicitar uma foto somente
depois de uma tentativa sem leitura no stream e quando a ROI central apresentar
detalhe de bordas suficiente, ausência de desfoque e estabilidade temporal por três
quadros consecutivos. Os limiares e a dimensão da ROI DEVEM (MUST) ser configuráveis
em `AjustesVisao` para permitir calibração sem alterar o fluxo de domínio.

#### Scenario: ROI estável sem leitura pelo stream

- **WHEN** três quadros elegíveis da ROI não produzem código e satisfazem os sinais
  de detalhe, nitidez e estabilidade
- **THEN** o controlador inicia uma única chamada a `Stream.capturePhoto()`
- **AND** registra os sinais usados no diagnóstico sem registrar pixels ou imagens.

#### Scenario: Código confirmado antes da captura

- **WHEN** o consenso do stream confirma um código antes de o gatilho disparar
- **THEN** nenhuma captura de foto é solicitada
- **AND** o fluxo segue a transição normal de decodificação concluída.

### Requirement: Retentativas limitadas e serializadas

O aplicativo DEVE (MUST) manter no máximo uma captura em andamento, aplicar um
cooldown de pelo menos 1,5 segundo após uma foto sem leitura e limitar o fallback a
três tentativas por ciclo de escaneamento. Após esgotar o limite, o aplicativo DEVE
(MUST) publicar a falha no fluxo de domínio já existente e não solicitar novas fotos
até o próximo ciclo de produto.

#### Scenario: Foto sem código durante o cooldown

- **WHEN** uma foto é processada sem código confirmado
- **THEN** uma nova foto não é solicitada antes de transcorridos 1,5 segundo
- **AND** o stream continua sendo a fonte primária de leitura nesse intervalo.

#### Scenario: Limite de tentativas atingido

- **WHEN** três capturas elegíveis terminam sem código confirmado
- **THEN** o controlador publica `DecodificacaoFalhou` no ciclo atual
- **AND** não inicia uma quarta captura nesse ciclo.

### Requirement: Processamento efêmero e privado da foto

O aplicativo DEVE (MUST) tratar `PhotoData` apenas em memória, normalizar a
orientação quando necessária e recortar a ROI central antes de chamar o leitor. A
imagem completa, buffers intermediários e arquivos temporários DEVEM (MUST) ser
liberados ou removidos em `finally`, inclusive em erro ou cancelamento. O aplicativo
NÃO DEVE persistir, exibir, registrar nem transmitir a foto completa ou a ROI.

#### Scenario: Foto HEIC ou bitmap recebida com sucesso

- **WHEN** `capturePhoto()` retorna uma foto válida
- **THEN** a implementação produz somente a ROI orientada necessária para o ML Kit
- **AND** descarta a representação completa antes de concluir a análise.

#### Scenario: Erro durante decodificação

- **WHEN** a conversão, o recorte ou o leitor falha
- **THEN** todos os recursos temporários são liberados
- **AND** o diagnóstico contém apenas a categoria do erro e métricas não visuais.

### Requirement: Resultado integrado ao fluxo atual

Uma foto que confirma o código DEVE (MUST) produzir `CapturaDisparada` e
`DecodificacaoConcluida` na ordem necessária para reutilizar a validação já existente.
Uma foto sem leitura antes do limite DEVE (MUST) manter o ciclo de escaneamento apto
a receber novas tentativas do stream e do gatilho, sem criar sessão, câmera ou preview
adicionais.

#### Scenario: Foto confirma um EAN

- **WHEN** o leitor confirma um código a partir da ROI da foto
- **THEN** o ator recebe a captura e a decodificação concluída em ordem
- **AND** o produto segue para a validação contra os dados existentes.

#### Scenario: Fim do estado de escaneamento

- **WHEN** o usuário sai do ciclo de escaneamento, a sessão encerra ou o stream fecha
- **THEN** uma captura pendente é cancelada com limpeza garantida
- **AND** nenhuma leitura tardia altera o estado seguinte.

### Requirement: Diagnóstico e teste com dispositivo simulado

O aplicativo DEVE (MUST) disponibilizar no diagnóstico o número de tentativas, o
estado da captura, o cooldown e o último resultado sem incluir material visual. No
modo de desenvolvimento, o MockDeviceKit DEVE (MUST) aceitar uma URI de imagem de
captura para exercitar o fallback sem óculos físicos.

#### Scenario: Calibração local

- **WHEN** uma captura é disparada, falha ou confirma um código
- **THEN** os logs e o estado de diagnóstico mostram evento, contadores e duração
- **AND** não incluem bytes, caminhos nem representação da imagem.
