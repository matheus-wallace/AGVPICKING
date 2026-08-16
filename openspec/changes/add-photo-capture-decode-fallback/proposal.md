# Proposal: fallback de leitura por captura de foto

## Why

O stream de vídeo já permite ler códigos de barras em tempo real, mas sua imagem é
comprimida e pode não conter detalhe suficiente em situações de pouca luz, movimento
ou código pequeno. Hoje, quando o stream não obtém consenso, não há uma escalada
controlada para a imagem de maior qualidade disponível no sensor dos óculos.

## What Changes

- Adicionar um gatilho local e configurável que só solicita uma foto quando a ROI
  central estiver estável, com detalhe suficiente e sem leitura no stream.
- Usar `Stream.capturePhoto()` como fallback, com no máximo três tentativas e
  intervalo mínimo de 1,5 segundo após uma tentativa sem leitura.
- Decodificar a foto exclusivamente em memória: normalizar orientação, recortar a
  mesma ROI central usada pelo stream e descartar a imagem completa antes da leitura.
- Reutilizar o resultado normal do domínio (`DecodificacaoConcluida` ou
  `DecodificacaoFalhou`), sem criar um segundo fluxo de validação de produto.
- Expor telemetria sem conteúdo visual para calibrar o gatilho e permitir configurar
  uma imagem de foto no MockDeviceKit durante o desenvolvimento.

## Capabilities

### New Capabilities

- `photo-capture-decode-fallback`: captura e decodificação por foto como escalada
  limitada do stream, incluindo privacidade, tentativas e diagnóstico.

### Modified Capabilities

Nenhuma. A capacidade de leitura pelo stream ainda não possui uma especificação
arquivada em `openspec/specs`; este slice acrescenta uma capacidade nova.

## Impact

- Afeta `ControladorDeVisao`, `LeitorDeCodigo`, ajustes e diagnósticos de visão.
- Acrescenta adaptadores locais para `PhotoData` e para a foto configurável do mock.
- Não adiciona armazenamento de imagem, serviços externos, OCR, VLM ou bibliotecas
  novas de leitura de código.
