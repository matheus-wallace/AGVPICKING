## 1. Contratos de diagnóstico e geometria

- [x] 1.1 Criar em `vision/` o modelo imutável de diagnóstico da prévia (estado do stream, qualidade/FPS solicitados, dimensões efetivas, último resultado e duração), sem campos que carreguem imagem ou buffer.
- [x] 1.2 Extrair uma função Kotlin pura que calcule o retângulo visível do vídeo com `Fit` e, dentro dele, a moldura central correspondente ao `fatorRecorte`.
- [x] 1.3 Cobrir a geometria com testes unitários para frame com a mesma proporção do contêiner, letterboxing vertical, pillarboxing lateral e fatores de recorte 50%, 60% e 70%.

## 2. Renderização HEVC efêmera

- [x] 2.1 Criar `RenderizadorHevc` reutilizando a lógica de NAL units e seleção segura de codec já portada do sample `CameraAccess`, configurado para saída direta em `Surface` e sem conversão para `Bitmap`.
- [x] 2.2 Publicar, a partir do renderizador, a resolução efetiva e falhas de renderização para o diagnóstico, sem derrubar o caminho de ML Kit.
- [x] 2.3 Implementar encerramento idempotente que pare o codec, solte sua referência à `Surface` (sem liberar o objeto pertencente ao `SurfaceView`) e descarte a fila de pacotes comprimidos ao remover a prévia.

## 3. Integração com o stream de visão

- [x] 3.1 Adicionar ao `ControladorDeVisao` pontos explícitos para anexar e remover uma `Surface` de preview, sem criar nova sessão ou capability de câmera.
- [x] 3.2 Alimentar o renderizador e o decodificador de análise com o mesmo frame HEVC apenas enquanto houver `Surface` válida; ausência ou falha de preview não pode interromper o scanner.
- [x] 3.3 Atualizar o `StateFlow` de diagnóstico com transições do stream, dimensões negociadas e resultados/duração do leitor, preservando o consenso e o evento único por escaneamento.
- [x] 3.4 Garantir que saída de `EscaneandoProduto`, `onStop`, perda/troca de sessão e destruição da `Surface` encerrem o renderizador e removam a imagem exibida.

## 4. Tela espelho

- [x] 4.1 Criar `ui/mirror/` com `MirrorUiState`, ViewModel/adaptador e um host `SurfaceView` via `AndroidView`, encaminhando corretamente os callbacks de ciclo de vida da superfície.
- [x] 4.2 Desenhar a moldura de ROI sobre a área efetivamente renderizada e apresentar a telemetria da especificação, com estados explícitos para preview indisponível, nenhuma tentativa e leitura não encontrada.
- [x] 4.3 Integrar a tela espelho ao fluxo atual sem remover os controles temporários do painel de desenvolvimento necessários para chegar a `EscaneandoProduto`.

## 5. Verificação

- [x] 5.1 Rodar `./gradlew testDebugUnitTest assembleDebug` e confirmar que os testes existentes e os novos testes de geometria passam. **92 testes, 0 falhas; `assembleDebug` e `lintDebug` concluídos com sucesso.**
- [ ] 5.2 Instalar em dispositivo com o MockDeviceKit, chegar a `EscaneandoProduto` e verificar visualmente que a prévia mostra a câmera traseira, a moldura acompanha o recorte configurado e a telemetria reflete a resolução/FPS recebidos.
- [ ] 5.3 Com uma etiqueta EAN física, validar que manter o código dentro da moldura leva à leitura atual e que colocá-lo parcialmente fora explica visualmente a falha, sem alterar o consenso de leitura.
- [x] 5.4 Sair do escaneamento, colocar o app em segundo plano, destruir/recriar a tela e simular perda de sessão; em todos os casos verificar que a prévia desaparece, o scanner pode reabrir depois e nenhum arquivo de imagem ou vídeo foi criado. **Validado no SM-G780F: background encerrou e reabriu o stream; despareamento levou o stream a `CLOSED`/`DESLIGADO`; nenhum arquivo visual foi encontrado.**
- [ ] 5.5 Medir no aparelho se a prévia reduz a taxa de leitura ou causa travamento perceptível; registrar codec escolhido, resolução efetiva, FPS configurado e decisão de manter/desabilitar a prévia em `design.md`.
