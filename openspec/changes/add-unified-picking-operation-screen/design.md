# Design: tela operacional unificada de separação

## Estrutura

A tela mantém três regiões estáveis para não obrigar o operador a reaprender a interface a cada estado:

1. **Cabeçalho de contexto:** ordem, progresso (`item atual / total`) e conexão/sessão.
2. **Cartão da etapa atual:** uma das três validações principais, com instrução curta, estado de escuta/fala e confirmação da última ação.
3. **Rodapé de contingência:** estado resumido e acesso discreto ao painel de debug; não oferece botão de avanço no modo de operação.

O conteúdo central muda conforme o estado, sem ser uma navegação de telas:

| Validação WMS | Estados | Conteúdo visível |
|---|---|---|
| Endereço | `NavegandoParaEndereco`, `AguardandoCheckDigit` | endereço em destaque, produto/descrição como contexto e instrução de chegada/check digit; nunca o check digit esperado |
| Produto | `EscaneandoProduto`, `DecodificandoProduto`, `VerificacaoAssistida`, `ValidandoContraDados` | prévia espelho efêmera, ROI, status de leitura e código confirmado apenas depois do consenso |
| Quantidade | `ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho`, `ItemConcluido` | produto, quantidade esperada/informada, readback e compartimento/progresso |

Estados como `AguardandoOrdem`, pausa, erro, exceção e ordem concluída reutilizam a mesma estrutura com uma mensagem de estado, sem abrir uma quarta tela operacional.

## Decisões

1. **A UI é uma projeção, nunca uma fonte de transições.** `OperationViewModel` combina flows e transforma dados em um `OperationUiState` imutável. Ele não chama `reduce` e não envia eventos do fluxo principal; os produtores continuam sendo voz, visão e DAT.
2. **Dados sensíveis são minimizados.** A tela pode mostrar endereço, produto, descrição, quantidade e progresso necessários para a tarefa. Não exibe senha/check digit esperado, lote completo ou conteúdo de frames/buffers; o código lido aparece somente quando já foi confirmado pelo pipeline de visão.
3. **A prévia preserva o lifecycle atual.** A `Surface` só é anexada quando o cartão de produto está visível e o stream está ativo. Sair do estado de produto, trocar para o painel debug ou destruir a tela remove a `Surface`; nenhum frame é persistido.
4. **Debug é uma superfície distinta.** Um controle identificado como desenvolvimento pode alternar para `DevPanelScreen`; retornar não reinicia sessão, ASR, TTS ou visão. O painel mantém seus botões, mas a tela operacional não os replica.
5. **Layout resiliente.** Em retrato, as regiões empilham e o cartão de produto mantém a proporção do stream. Em paisagem, o contexto e a etapa podem dividir a largura. Textos de endereço e quantidade devem continuar legíveis com fonte do sistema ampliada.

## Non-goals

- Substituir a tela de divergência por uma experiência sem toque.
- Criar a seleção de ordem por voz.
- Redesenhar o painel de debug ou remover seus botões.
- Gravar vídeo, imagens ou qualquer telemetria visual adicional.

## Bancada (16/08/2026)

Verificação em SM-G780F, pedido mockado 408176 (274K5010000), item 1/3 (Loratadina 10mg, EAN 7896523202204). Percurso conduzido pelos botões do painel de debug (a voz ainda não avança estado — depende de `add-state-driven-voice-flow`), com alternância para `OperationScreen` a cada etapa via `adb`/`uiautomator`:

- **Endereço → produto → quantidade na mesma tela:** confirmado sem navegação/tela intermediária; card muda de conteúdo por `OperationUiState`. Senha do endereço (47) e check digit nunca apareceram em nenhum card.
- **Cartão de produto:** prévia + ROI presentes com o stream `DESLIGADO` (bancada sem `MockGlasses.setCameraFeed`) e diagnóstico textual ("Procurando o código" / "Aponte para o código do produto"); nenhum código de barras ou EAN aparece antes da confirmação.
- **Alternância operação ↔ debug durante `EscaneandoProduto`:** repetida 3x sem crash, sem `FATAL EXCEPTION`, sem erro de `Surface`; o painel manteve o último código já confirmado ao invés de reabrir a captura, confirmando que a troca não duplica nem reinicia o stream.
- **Fonte ampliada (`font_scale=1.3`):** layout permanece legível e sem corte na tela inicial.
- **Rotação — achado, não regressão desta mudança:** `MainActivity` já tinha `android:screenOrientation="portrait"` fixo no manifest antes desta fatia. A tela nunca roda em paisagem, então a resiliência a paisagem descrita na decisão 5 acima é teórica — não há como validá-la ou exercitá-la enquanto essa trava existir. Se o modo paisagem for um requisito real (ex.: montagem do celular no carrinho), remover a trava é uma mudança à parte, fora do escopo desta.
