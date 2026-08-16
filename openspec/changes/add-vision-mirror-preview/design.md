## Context

Ver `proposal.md - Why` e `specs/vision-mirror-preview/spec.md`. O `ControladorDeVisao` atual abre a única capability de câmera somente em `EscaneandoProduto`, recebe HEVC comprimido e o entrega a `DecodificadorHevc` sem `Surface`. Esse decodificador produz uma `Image` YUV apenas pelo tempo necessário para criar o recorte NV21; o ML Kit lê o recorte e o frame completo é liberado imediatamente.

O SDK não fornece uma prévia pronta para esse caminho comprimido. O `HevcDecoder` do sample `CameraAccess` confirma que o mesmo HEVC pode ser renderizado por `MediaCodec` em uma `Surface`, com a conversão YUV→RGB feita pelo compositor. A documentação técnica (§10.2 e §12) exige essa visão para ajustar distância e recorte, mas também proíbe retenção ou persistência de imagem.

## Goals / Non-Goals

**Goals:**

- Renderizar no celular o que chega no stream durante o escaneamento, sem criar outro `Stream` nem outra sessão DAT.
- Tornar a ROI ativa observável por uma moldura geometricamente fiel, mesmo quando a resolução entregue for diferente da resolução nominal solicitada.
- Expor à UI um único estado de diagnóstico, sem acoplá-la às threads de `MediaCodec`, ML Kit ou ao ator.
- Preservar o caminho de leitura atual e o descarte imediato do frame usado pela análise.

**Non-Goals:**

- Exibir câmera fora de `EscaneandoProduto`, gravar vídeo, capturar tela, tirar foto, enviar imagem ou implementar o modo de calibração por corpus do Marco 3.
- Alterar o consenso, os formatos suportados, a cascata de foto, o reducer ou os eventos de picking.
- Substituir nesta fatia os controles temporários do painel de desenvolvimento, necessários para levar o mock até o estado de escaneamento.

## Decisions

1. **Dois consumidores independentes do mesmo frame HEVC.** O controlador entrega o frame comprimido tanto ao decodificador YUV já existente quanto a um novo `RenderizadorHevc`. Cada `MediaCodec` tem sua própria fila e sua própria cópia transitória do pacote comprimido; o segundo decodificador só existe enquanto houver uma `Surface` válida. Não há segunda capability de câmera nem segunda sessão. Alternativa rejeitada: trocar o decodificador atual para saída em `Surface`. Um codec não entrega, ao mesmo tempo, a `Surface` para a prévia e a `Image` YUV com planos/strides para o recorte e ML Kit.

2. **Renderização direta em `SurfaceView`.** Um `AndroidView` hospeda um `SurfaceView`, e um composable desenha a moldura e a telemetria sobre ele. O renderizador configura `MediaCodec` com a `Surface` e libera cada buffer de saída para exibição; a imagem completa permanece em buffers de codec/GPU e nunca é copiada para `Bitmap`, heap de UI ou disco. Alternativa rejeitada: converter `Image` para `Bitmap` ou alimentar uma `ImageBitmap` no Compose. Além do custo de cópia/conversão, essas opções violariam a regra de não reter o frame completo após o recorte.

3. **O preview é opcional e governado pelo ciclo de vida da `Surface`.** A tela registra `SurfaceHolder.Callback`; criação ou troca de superfície chama `anexarPreview(surface)` e destruição chama `removerPreview()`. O controlador cria ou encerra somente o `RenderizadorHevc`, preservando a câmera e a leitura quando a tela gira ou a prévia fica temporariamente indisponível. `parar()`, perda de sessão e saída de `EscaneandoProduto` encerram os dois decodificadores e liberam a superfície. Alternativa rejeitada: manter referência da `Surface` na UI ou no `ViewModel`; a superfície pertence à view e pode se tornar inválida sem que o ViewModel seja destruído.

4. **Moldura calculada sobre a área realmente renderizada.** O renderizador publica largura e altura efetivas quando `MediaCodec` anuncia seu formato de saída. A UI usa `ContentScale.Fit` e calcula primeiro o retângulo da imagem dentro do contêiner; só então aplica o mesmo `fatorRecorte` central a esse retângulo. Assim, as barras laterais/superior-inferior não deslocam a ROI e um stream entregue, por exemplo, em 480×640 continua alinhado ao recorte analisado. Alternativa rejeitada: desenhar a moldura sobre todo o contêiner; ela ficaria errada sempre que a proporção da tela diferisse do frame.

5. **Um `StateFlow` de diagnóstico sem dados de imagem.** `ControladorDeVisao` expõe um modelo imutável com estado do stream, resolução efetiva, qualidade/FPS configurados, último resultado e duração da tentativa. A tentativa é atualizada pelo mesmo callback que hoje recebe a resposta do leitor, sem guardar NV21 nem objetos `Image`. A tela espelho combina esse flow com o estado de picking e os controles provisórios do `DevPanel`. Alternativa rejeitada: a UI ler `Logcat` ou observar objetos de codec; isso não é confiável, não é testável e cruzaria limites de thread.

6. **O renderizador usa a compatibilidade HEVC já descoberta pelo sample.** Ele reaproveita a política de seleção que evita os decodificadores conhecidos por corromper esse stream e registra o codec e a resolução negociados. Uma falha de preview é apresentada como diagnóstico indisponível, sem interromper a leitura de código. Alternativa rejeitada: exigir preview para iniciar o stream; a leitura é a função operacional e deve continuar quando a tela não puder renderizar.

## Risks / Trade-offs

- **[Risco] Dois decodificadores elevam CPU, memória transitória e bateria.** → O segundo é criado apenas com a tela espelho visível, compartilha a mesma câmera limitada a 7 FPS e é destruído assim que a `Surface` some; a verificação em aparelho mede se o preview causa perda de leitura.
- **[Risco] Um codec disponível renderiza imagem corrompida ou não entrega formato.** → Reutilizar a lista de bloqueio e a seleção comprovadas no sample, registrar nome/formato e manter a leitura independente da prévia.
- **[Risco] A moldura parece desalinhada por rotação ou letterboxing.** → Aplicar a rotação configurada tanto à renderização quanto ao cálculo do retângulo, e validar visualmente com etiqueta colocada nos limites da ROI.
- **[Trade-off] `SurfaceView` não é uma textura Compose e exige callbacks imperativos.** → O custo é isolado no adaptador de preview; em troca, evita cópia para bitmap e usa o caminho de composição mais econômico.
- **[Trade-off] A prévia mostra o frame completo de forma volátil, enquanto a análise retém apenas a ROI.** → É a exceção estritamente local prevista pela tela espelho; não há retenção, serialização, captura de tela automática ou saída de rede.

## Migration Plan

1. Introduzir a prévia como consumidor opcional, mantendo a leitura atual intacta quando nenhuma `Surface` estiver conectada.
2. Integrar a UI mantendo os controles de desenvolvimento até existir a operação completa por voz.
3. Validar no MockDeviceKit e no aparelho físico que saída do escaneamento, rotação e perda de sessão removem a imagem e liberam o renderizador.
4. Se o segundo decodificador degradar a taxa de leitura ou falhar em um aparelho, desabilitar somente a prévia; o pipeline de escaneamento continua na versão atual sem migração de dados.

## Verificação em aparelho

Em 16/08/2026, a implementação foi instalada num Samsung SM-G780F com Android 13 e MockDeviceKit usando a câmera traseira do celular. As duas instâncias selecionaram `c2.android.hevc.decoder` (`software=true`) e negociaram 480×640: a saída de `Surface` informou `color-format=2130708361`, enquanto a saída de análise manteve `YUV420Flexible` (`2135033992`, stride 512).

Com os dois codecs ativos, o ML Kit continuou concluindo tentativas sem código em aproximadamente 10–33 ms e o stream chegou a `STREAMING`; não houve erro de codec ou congelamento observado. O teste ainda não mede taxa de acerto porque não havia uma etiqueta EAN posicionada diante da câmera — tarefas 5.2, 5.3 e 5.5 permanecem abertas até essa validação física.

O teardown foi validado por dois caminhos. Ao enviar o app para segundo plano, `ControladorDeVisao` encerrou o stream e o reabriu em `STREAMING` ao retornar, preservando o estado `EscaneandoProduto`. Ao desparear o óculos simulado, o stream emitiu `CLOSED`, a sessão chegou a `STOPPED` e a UI mudou para `DESLIGADO`. Uma busca em `cache`, `files` e no diretório externo do app não encontrou arquivos de imagem ou vídeo.
