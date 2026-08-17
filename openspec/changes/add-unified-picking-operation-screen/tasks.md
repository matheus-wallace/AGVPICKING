# Tasks: tela operacional unificada de separação

## 1. Estado e componentes reutilizáveis

- [x] 1.1 Criar `OperationUiState` e um projetor/ViewModel que combine estado do ator, ordem/linha do repositório, diagnóstico de fala e diagnóstico de visão sem lógica de transição nem objetos Android de câmera.
- [x] 1.2 Criar testes unitários para as projeções de endereço, produto, quantidade, erro, pausa e ordem concluída; testar que senha/check digit esperado não entra no estado de UI.
- [x] 1.3 Extrair a prévia espelho e a moldura de ROI para componente reutilizável, sem mudar as regras de anexar/remover `Surface` já verificadas.

## 2. Tela operacional

- [x] 2.1 Implementar `OperationScreen` com cabeçalho de ordem/progresso e um cartão central que troca entre endereço, produto e quantidade pelo `OperationUiState`.
- [x] 2.2 No cartão de produto, hospedar prévia, ROI e diagnóstico operacional mínimo; garantir que frame/código não confirmado não seja retido ou exibido fora do escaneamento.
- [x] 2.3 Exibir o estado de fala/escuta e a última confirmação de forma legível, sem criar botão de avanço do fluxo principal.
- [x] 2.4 Adaptar os estados de início, pausa, erro, exceção e conclusão para a mesma tela, com mensagem de recuperação adequada.

## 3. Navegação e debug

- [x] 3.1 Fazer `MainActivity` abrir a tela operacional por padrão e preservar instâncias de `OperationViewModel`, `MirrorViewModel`/controlador e `DevPanelViewModel` conforme a superfície alterna.
- [x] 3.2 Adicionar acesso explícito ao painel de debug e retorno à operação; confirmar que a troca não envia `PickingEvent`, não reinicia sessão e não reinicia áudio.
- [x] 3.3 Manter todos os controles atuais em `DevPanelScreen`, sem incluí-los na tela de operação.

## 4. Verificação

- [x] 4.1 Executar `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` e `./gradlew lintDebug` a partir de `AgvPickVoice/`. **121 testes, 0 falhas; `assembleDebug` e `lintDebug` concluídos com sucesso, sem novo alerta de lint nos arquivos da mudança.**
- [x] 4.2 Em aparelho físico, percorrer endereço → produto → quantidade para uma linha mockada e verificar que a mesma tela atualiza o cartão sem navegação/tela intermediária. **Feito no SM-G780F, pedido 408176 (274K5010000), item 1/3 — Loratadina 10mg.** Percorrido via painel de debug (`Confirmar ordem` → `Iniciar navegação` → `Cheguei no endereço` → `Check digit correto` → `Disparar captura` → `Decodificação OK`) alternando para `OperationScreen` a cada etapa: cartão de endereço mostrou "Rua D, prédio 118, andar B" sem senha/check digit; cartão de produto mostrou a prévia + ROI com "Procurando o código"/"Aponte para o código do produto" sem código de barras nem EAN; cartão de quantidade mostrou "Esperado: 12" e compartimento ST01. Nenhuma tela intermediária, nenhuma navegação — só troca de conteúdo do cartão central.
- [x] 4.3 Validar rotação, fonte ampliada e alternância operação ↔ debug durante escaneamento: a prévia é liberada quando não visível e retorna sem duplicar stream nem reter imagem. **Fonte ampliada (`font_scale=1.3`) testada na tela inicial: layout resiliente, sem corte/sobreposição de texto.** **Rotação: não aplicável — `MainActivity` tem `android:screenOrientation="portrait"` fixo no manifest (decisão pré-existente, não desta mudança); `settings put system user_rotation` não teve efeito algum, confirmando o bloqueio.** **Alternância operação ↔ debug durante `EscaneandoProduto`: repetida 3x via `adb`; sem crash, sem `FATAL EXCEPTION` no logcat, sem erro de `Surface` duplicada; ao voltar para o painel, telemetria manteve `Último código: 7896523202204` (o valor já confirmado antes da troca), confirmando que a troca não reabre nem duplica o stream.**
- [x] 4.4 Quando `add-state-driven-voice-flow` estiver concluído, repetir uma ordem de múltiplas linhas sem tocar em avanço na tela operacional. `add-state-driven-voice-flow` está completo e sua tarefa 4.3 é este mesmo percurso — confirmado por Matheus em bancada em 17/08/2026: ordem mockada completa, só de voz, sem toque na tela operacional.
