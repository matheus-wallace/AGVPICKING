## Purpose

Mostrar a prévia da câmera dos óculos como uma miniatura ancorada e dispensável, em vez
de ocupar a largura total da tela, e só enquanto há câmera em curso.

## ADDED Requirements

### Requirement: Prévia da câmera é uma miniatura ancorada

O sistema DEVE (MUST) exibir a prévia de vídeo da câmera como um componente pequeno
ancorado a um canto da tela, e não em largura total, em toda tela que hoje a exibe.

#### Scenario: Miniatura em vez de preview em largura total

- **WHEN** a câmera está ativa e a tela exibe a prévia de vídeo
- **THEN** a prévia aparece como uma miniatura ancorada a um canto, não ocupando a
  largura total da tela.

### Requirement: Miniatura é arrastável e dispensável

O sistema DEVE (MUST) permitir que o operador arraste a miniatura para outro canto da
tela e a dispense, sem afetar a câmera nem o fluxo de separação.

#### Scenario: Dispensar a miniatura não desliga a câmera

- **WHEN** o operador dispensa a miniatura de câmera
- **THEN** a superfície de exibição é removida
- **AND** a câmera continua ligada e nenhum `PickingEvent` é publicado.

### Requirement: Miniatura só aparece quando há câmera em curso

O sistema NÃO DEVE (MUST NOT) exibir a miniatura de câmera quando o stream está desligado ou em
erro — nem como um espaço vazio, nem como uma superfície preta.

O sistema DEVE (MUST) exibir a miniatura desde o momento em que a câmera **começa** a subir, e
não apenas quando ela já transmite: a superfície de exibição precisa existir antes do primeiro
NAL do stream, porque um decodificador de preview criado no meio do stream não recebe os
cabeçalhos VPS/SPS/PPS e nunca chega a decodificar quadro nenhum (design.md - Decisão 11).
Enquanto ainda não houver quadro, o sistema DEVE (MUST) indicar que a câmera está iniciando, em
vez de apresentar um retângulo preto sem explicação.

#### Scenario: Stream desligado não mostra nada de câmera

- **WHEN** o stream de câmera está desligado ou em erro
- **THEN** nenhuma miniatura de câmera é exibida na tela.

#### Scenario: Câmera iniciando já hospeda a superfície

- **WHEN** o stream de câmera está iniciando e ainda não entregou nenhum quadro
- **THEN** a miniatura é exibida com a indicação de que a câmera está iniciando
- **AND** a superfície de exibição já está anexada, para receber o stream desde o primeiro NAL.
