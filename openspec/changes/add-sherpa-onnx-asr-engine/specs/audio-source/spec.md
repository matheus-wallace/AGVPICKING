## MODIFIED Requirements

### Requirement: Comando de voz reconhecido produz o evento correspondente
Enquanto a captura de áudio estiver ativa, ao pronunciar um dos comandos suportados pelo
estado atual, o sistema DEVE publicar o `PickingEvent` transversal correspondente para o
ator único de picking. O reconhecimento passa de decodificação em streaming (o próprio
decodificador fechando a elocução por endpoint embutido) para um corte por detecção de
atividade de voz seguido de reconhecimento sobre o trecho já cortado — a publicação do
evento DEVE (MUST) acontecer dentro de um tempo total limitado pela soma do corte de
silêncio do perfil de endpoint do estado com o tempo de inferência do reconhecimento
sobre o trecho capturado, não mais apenas pela janela de silêncio do perfil.

#### Scenario: Comando "parar" reconhecido
- **WHEN** o operador fala "parar" perto da fonte de áudio ativa, seguido de silêncio
- **THEN** o evento de comando de parar é publicado no ator, produzindo a mesma transição
  observável que o botão correspondente do painel de dev já produz

#### Scenario: Comando "repetir" reconhecido
- **WHEN** o operador fala "repetir" perto da fonte de áudio ativa, seguido de silêncio
- **THEN** o evento de comando de repetir é publicado no ator, produzindo a mesma transição
  observável que o botão correspondente do painel de dev já produz

#### Scenario: Publicação não é instantânea ao fim da fala
- **WHEN** o operador termina de falar um comando válido
- **THEN** o evento correspondente pode levar mais tempo para ser publicado do que levava
  com o decodificador anterior, porque o trecho de fala só é decodificado depois de o
  detector de atividade de voz confirmar o fim da elocução
- **AND** esse tempo adicional não pode impedir a publicação do evento nem exigir nova
  fala do operador quando o comando foi reconhecido corretamente
