## Why

Hoje o pipeline de visão informa somente se conseguiu ou não ler um código; quem está depurando não consegue ver o enquadramento que chega dos óculos, a distância efetiva nem se o recorte central de 60% cortou a etiqueta. A tela espelho prevista no §12 é o instrumento necessário para calibrar distância e recorte antes da demonstração e para tornar a operação compreensível quando projetada no celular.

## What Changes

- Criar uma tela espelho de visão no celular, disponível enquanto o fluxo de picking estiver no estado de escaneamento, que renderiza ao vivo o stream recebido dos óculos.
- Desenhar sobre a prévia a moldura correspondente ao mesmo recorte central usado pelo decodificador, para tornar visível quando o código está fora ou no limite da área analisada.
- Exibir sinais de diagnóstico de baixo custo: estado da sessão/stream, qualidade e FPS configurados, dimensões efetivas do frame, último resultado de leitura e o tempo da última tentativa.
- Alimentar a prévia por um decodificador HEVC de saída em `Surface`, separado do caminho existente de decodificação para ML Kit; o preview não altera o evento, o consenso nem a cascata de leitura.
- Desligar e liberar a prévia junto com a câmera ao sair de `EscaneandoProduto`, ao perder a sessão ou quando a tela não tiver uma `Surface` válida.
- Manter a garantia de privacidade: nenhum frame, recorte ou captura é salvo, compartilhado ou mantido após a renderização; a prévia é exclusivamente volátil na tela do aparelho.

## Capabilities

### New Capabilities

- `vision-mirror-preview`: prévia ao vivo e efêmera do stream da câmera, com moldura de ROI e telemetria de diagnóstico para calibração do enquadramento e da legibilidade.

### Modified Capabilities

- Nenhuma.

## Impact

- Código novo em `ui/mirror/` para o estado e a tela Compose, e em `vision/` para a renderização HEVC em `Surface` e a telemetria da prévia.
- O controlador de visão passa a aceitar uma superfície de renderização opcional e a expor estado diagnóstico observável pela UI; a tela espelho convive temporariamente com os controles de desenvolvimento que ainda dirigem o fluxo mockado.
- Não adiciona dependências nem chamadas de rede. Reutiliza `MediaCodec`, `SurfaceView`/`Surface` e o stream DAT já presentes no Android e no projeto.
