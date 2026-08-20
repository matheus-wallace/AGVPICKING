## MODIFIED Requirements

### Requirement: Comando de voz reconhecido produz o evento correspondente
Enquanto a captura de áudio estiver ativa, ao pronunciar um dos comandos suportados pelo
estado atual, o sistema DEVE (MUST) publicar o `PickingEvent` transversal correspondente
para o ator único de picking. O mecanismo de reconhecimento pode passar a incluir um motor
de fala-para-intenção (Picovoice Rhino) ao lado dos motores de fala-para-texto já existentes
(Vosk, sherpa-onnx) — quando o motor ativo for de fala-para-intenção, ele DEVE (MUST) decidir
diretamente se a elocução corresponde a um comando válido do vocabulário fechado do estado
atual, em vez de decodificar texto livre para ser casado depois contra o vocabulário
esperado. O comportamento observável — falar um comando válido publica o evento
correspondente, falar algo fora do vocabulário do estado não publica nada — NÃO DEVE (MUST
NOT) mudar com a troca de motor.

#### Scenario: Comando "parar" reconhecido pelo motor de fala-para-intenção
- **WHEN** o operador fala "parar" perto da fonte de áudio ativa, com o motor Rhino carregado
  e um contexto que cobre o vocabulário do estado atual
- **THEN** o evento de comando de parar é publicado no ator, produzindo a mesma transição
  observável que o botão correspondente do painel de dev já produz

#### Scenario: Fala fora do vocabulário do estado não produz evento
- **WHEN** o operador fala algo que não corresponde a nenhuma expressão do contexto Rhino
  carregado para o estado atual
- **THEN** nenhum `PickingEvent` é publicado, mesmo que o motor tenha processado a elocução
  até o fim (`isUnderstood = false` ou equivalente)

#### Scenario: Motor ativo continua sendo uma troca de uma linha
- **WHEN** o time decide qual motor de ASR roda em produção
- **THEN** a escolha continua expressa em uma única linha de `AppContainer.kt`, com Vosk,
  sherpa-onnx e Picovoice Rhino convivendo no binário sem se excluírem
