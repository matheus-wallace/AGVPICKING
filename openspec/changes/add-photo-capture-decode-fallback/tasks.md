# Tasks: fallback de leitura por captura de foto

## 1. Métricas e decisão pura

- [x] 1.1 Criar métricas de detalhe, desfoque e estabilidade a partir da ROI NV21,
  retendo no máximo uma miniatura de luminância.
- [x] 1.2 Criar `GatilhoDeCaptura` com elegibilidade, três quadros estáveis, cooldown,
  máximo de tentativas e sinal de orientação após timeout.
- [x] 1.3 Adicionar os parâmetros necessários a `AjustesVisao` e testes unitários para
  quadros nítidos/desfocados, estáveis/em movimento, cooldown e limite.

## 2. Pipeline de foto privado

- [x] 2.1 Criar adaptador para `PhotoData.Bitmap` e HEIC, com normalização de
  orientação e recorte central antes da leitura.
- [x] 2.2 Estender `LeitorDeCodigo` para analisar a ROI da foto com o mesmo leitor,
  formatos e executor serial do stream.
- [x] 2.3 Garantir `try/finally`, reciclagem de bitmaps/buffers, limpeza defensiva de
  temporários e testes dos caminhos de sucesso, erro e cancelamento.

## 3. Integração de visão e domínio

- [x] 3.1 Integrar o gatilho a tentativas sem leitura no `ControladorDeVisao`, sem
  análise ou captura concorrente.
- [x] 3.2 Chamar `capturePhoto()` no estado `EscaneandoProduto`; somente após processar
  a foto publicar os eventos de domínio na ordem correta.
- [x] 3.3 Atualizar `DiagnosticoVisao`, logs e UI de diagnóstico com contadores,
  cooldown, sinais e resultado sem conteúdo visual.

## 4. Simulação e validação

- [x] 4.1 Adicionar ao `MockDeviceBootstrap` uma entrada debug para configurar a imagem
  retornada por `setCapturedImage(uri)`.
- [ ] 4.2 Cobrir em teste a confirmação por foto, falha com cooldown, esgotamento em
  três tentativas e descarte ao sair do escaneamento.
- [x] 4.3 Executar `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` e
  `./gradlew lintDebug`.
- [ ] 4.4 Em óculos físicos, registrar métricas para calibrar os limiares com EAN
  legível, borrado e em movimento, sem salvar imagens.
