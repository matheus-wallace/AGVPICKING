# audio-state-feedback Specification

## Purpose

Fornecer instruções de áudio locais, curtas e confiáveis para que o operador execute o
ciclo de separação sem depender continuamente da tela do celular.

## ADDED Requirements

### Requirement: Saída de áudio substituível

O aplicativo DEVE (MUST) emitir fala através de uma abstração de saída de áudio, sem
acoplar o projetor de mensagens ao `TextToSpeech`, ao alto-falante do celular ou ao
futuro HFP dos óculos. A implementação de desenvolvimento DEVE (MUST) falhar de modo
gracioso quando o mecanismo de fala ou o idioma pt-BR não estiver disponível.

#### Scenario: Motor de fala disponível

- **WHEN** o motor de síntese local estiver inicializado com pt-BR
- **THEN** uma mensagem aceita pelo projetor é reproduzida pelo dispositivo de saída ativo.

#### Scenario: Motor indisponível

- **WHEN** a inicialização do motor falha ou pt-BR não pode ser selecionado
- **THEN** o aplicativo registra uma categoria de erro sem bloquear o ator, a câmera ou a UI
- **AND** o fluxo continua disponível pelo painel de desenvolvimento.

### Requirement: Mensagens derivadas de estados operacionais

O aplicativo DEVE (MUST) derivar mensagens curtas dos estados de picking que exigem
ação ou confirmação do operador. A mesma entrada de estado DEVE (MUST) gerar a mesma
mensagem em uma função testável, sem objetos Android ou I/O.

#### Scenario: Início do escaneamento

- **WHEN** o ator entra em `EscaneandoProduto`
- **THEN** o operador recebe um earcon ou instrução curta de escaneamento
- **AND** a câmera permanece sob controle exclusivo do controlador de visão.

#### Scenario: Endereço ou quantidade a confirmar

- **WHEN** o ator entra em um estado que contém endereço ou quantidade para ação
- **THEN** a mensagem inclui apenas os dados operacionais daquele estado
- **AND** não revela check digits esperados nem dados de outra linha.

### Requirement: Orientação de enquadramento por áudio

Quando `DiagnosticoVisao.orientacaoPendente` se torna verdadeiro durante
`EscaneandoProduto`, o aplicativo DEVE (MUST) falar “aponte para o código do produto”
uma vez naquele ciclo. A mensagem NÃO DEVE (MUST NOT) incluir imagem, código lido,
métricas de visão ou caminho de arquivo.

#### Scenario: Stream sem enquadramento elegível

- **WHEN** a visão completa o timeout de orientação sem disparar captura
- **THEN** a saída de áudio reproduz a orientação uma única vez
- **AND** novas emissões idênticas do diagnóstico não repetem a fala.

### Requirement: Prioridade, deduplicação e ciclo de vida

O aplicativo DEVE (MUST) deduplicar mensagens iguais no mesmo ciclo de estado e
preemptar fala rotineira por alertas críticos de erro ou produto incorreto. A saída
DEVE (MUST) ser interrompida e liberada quando a Activity deixa o primeiro plano para
não tocar ou reter recursos em segundo plano.

#### Scenario: Alerta crítico durante uma instrução

- **WHEN** um estado de erro ou alerta de validação chega enquanto uma instrução rotineira toca
- **THEN** a instrução atual é interrompida
- **AND** o alerta é reproduzido antes de qualquer item pendente da fila.

#### Scenario: Retorno ao primeiro plano

- **WHEN** a Activity retorna ao primeiro plano após a saída ter sido parada
- **THEN** a saída pode ser reinicializada sem duplicar a última mensagem já entregue.
