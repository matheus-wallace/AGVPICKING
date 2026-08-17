## 1. Fala no readback

- [x] 1.1 `ProjetorDeFalaPicking.kt`: `ReadbackQuantidade` passa a retornar
  "Confirma {quantidadeInformada}?" em vez de `null`.
- [x] 1.2 Ao voltar para `ConfirmandoQuantidade` via `ReadbackCorrecaoSolicitada`, repetir por
  voz a quantidade esperada da linha (mesma mensagem já usada na primeira entrada nesse estado).
  Nenhum código novo necessário: `ControladorDeFala` já limpa as chaves emitidas a cada troca de
  estado (comparação por igualdade de `PickingState`), então o branch de `ConfirmandoQuantidade`
  de 1.1 reemite sozinho ao ser reocupado. Coberto pelo teste de integração de 1.4.
- [x] 1.3 Confirmar que a nova fala respeita o gate `SaidaDeAudio.falando` já existente
  (design.md - Risco 1), sem nenhum mecanismo novo de sincronização TTS/ASR.
  Confirmado por leitura: toda mensagem passa por `ControladorDeFala.emitirUmaVez` ->
  `saida.falar`, e `SaidaTextToSpeechAndroid.falar`/`iniciarElocucao` erguem `falando=true` para
  qualquer `MensagemFalavel`, sem distinguir origem. Nenhuma mudança de código.
- [x] 1.4 Teste de unidade em `ControladorDeFalaTest.kt`/`ProjetorDeFalaPickingTest` cobrindo os
  dois cenários do spec.

## 2. Log de reconhecimento do ASR

- [x] 2.1 `ReconhecedorDeComando.kt`: logar todo resultado final do Vosk, aceito ou não, no
  mesmo canal estruturado já usado para gramática/nível ([[reference-voz-bancada]]).
  Já logava todo texto final não vazio antes desta fatia; o que faltava era diferenciar o motivo
  do descarte (2.2).
- [x] 2.2 Diferenciar no log os dois motivos de descarte: fora da gramática do estado atual vs.
  resultado de versão de estado obsoleta. Novo `ResultadoDePublicacao` (sealed interface:
  `Aceito`/`ForaDaGramatica`/`VersaoObsoleta`) substitui o `IntencaoDeVoz?` que
  `PublicadorDeVoz.publicar` devolvia; `ReconhecedorDeComando` usa isso para logar o motivo.
- [x] 2.3 Teste de unidade cobrindo que um resultado fora da gramática e um resultado obsoleto
  são ambos logados e nenhum publica `PickingEvent`. O `Log.i` do `ReconhecedorDeComando` em si
  não é testável em JVM (mesmo padrão do gate `BuildConfig.DEBUG` do painel de dev); o teste
  cobre o dado estruturado que alimenta o log — os dois motivos são distintos e nenhum dos dois
  muda o estado do ator. Verificação do texto do log em si fica para a bancada (4.2).

## 3. Diagnóstico da janela de câmera (bloqueia a implementação)

- [x] 3.1 Ler `ControladorDeVisao.kt` por completo: o que hoje encerra a produção de eventos em
  `EscaneandoProduto` — teto de tempo fixo, leitura prematura satisfazendo `ConsensoDeLeitura`,
  ou outra causa. Responder a Open Question do design.md antes de seguir.
  Nenhuma das duas hipóteses originais: era o orçamento de 3 tentativas do fallback por foto
  (`GatilhoDeCaptura`/`maxTentativasCaptura`), que disparava sem sinal de que o operador já
  abriu a caixa e esgotava em poucos segundos, escalando para `VerificacaoAssistida`. Ver
  design.md - Decisão 4.
- [x] 3.2 Com a causa confirmada, escrever a tarefa de correção específica (estender/remover
  teto, ou gating por sinal explícito do operador antes de aceitar leitura) — **tarefa a
  detalhar depois de 3.1**, não implementar às cegas.
  Decisão do dono do produto (design.md - Decisão 5): remover o escalonamento automático, não
  recalibrar. Bancada de 17/08/2026 não registrou nenhuma falha real de leitura direta do
  stream. `maxTentativasCaptura`/`EstadoCapturaFoto.ESGOTADA`/`publicarEsgotamento()` removidos;
  `GatilhoDeCaptura.registrarFracasso` só aplica cooldown. `EscaneandoProduto` só termina por
  sucesso ou por comando de voz transversal (`avaria`/`divergência`/`ruptura`/`parar`, já
  aceitos nesse estado).
- [x] 3.3 Teste (unidade e/ou bancada, conforme a causa) cobrindo que o operador tem tempo real
  de abrir a caixa e contar antes de a janela de leitura se fechar.
  `MetricasCapturaTest.kt`: "fracasso aplica cooldown mas nao limita tentativas futuras" prova
  que falhas sucessivas nunca bloqueiam uma nova captura elegível, só aplicam o cooldown. Medir
  o tempo real de bancada (abrir caixa + contar) fica para a task 4.2, como as demais fatias.

## 4. Verificação

- [x] 4.1 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/` depois dos grupos 1 e 2 (grupo 3 depende do diagnóstico de 3.1).
  276 testes, 0 falhas. Build e lint limpos (17 avisos preexistentes, 0 erros, nenhum nos
  arquivos alterados).
- [x] 4.2 Em bancada: readback fala a quantidade e reforça em caso de correção; log mostra
  resultados de ASR aceitos e descartados; janela de câmera cobre o tempo real de abrir a caixa
  e contar. Confirmado por Matheus em bancada em 17/08/2026: testado e ok.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok.

## 5. Sinônimo "próximo" em todo estado com uma palavra de avanço

- [x] 5.1 `InterpretadorDeFala.kt`: aceitar "próximo" como sinônimo aditivo de "iniciar"
  (`OrdemCarregada`), "cheguei" (`NavegandoParaEndereco`), "concluir" (`ConferenciaFinal`) e
  "encerrar" (`OrdemConcluida`), preservando as palavras originais (design.md - Decisão 6).
- [x] 5.2 Teste de unidade cobrindo que "próximo" produz o mesmo evento que a palavra original
  nos quatro estados, sem regressão das palavras originais nem dos dois estados que já aceitavam
  "próximo" (`AlocandoCarrinho`, `ReadbackQuantidade`). Teste adicional confirma que os estados
  excluídos pela Decisão 6 (`AguardandoCheckDigit`, `ConfirmandoQuantidade`) continuam sem
  aceitar "próximo". `TratandoExcecao` saiu dessa lista na task 10.5: a bancada mostrou que sem
  uma saída curta aquele estado não tem saída nenhuma (design.md - Decisão 12).

## 6. Miniatura de câmera nas duas telas

- [x] 6.1 Criar um componente Compose que envolve `PreviaEspelho` num tamanho fixo pequeno,
  ancorado a um canto da tela, arrastável e com um botão de dispensar — sem duplicar a lógica de
  anexar/remover `Surface` (design.md - Decisão 7). `MiniaturaDeCamera.kt`; o botão de dispensar
  usa 48dp de alvo de toque (spec `accessible-visual-identity`, adiantado do grupo 8).
- [x] 6.2 O componente só é composto com a câmera subindo ou transmitindo; com o stream desligado
  ou em erro, nada da câmera aparece em nenhuma das duas telas. O gate era `estadoStream ==
  ATIVO` e passou a incluir `INICIANDO` na task 10.2 — com o gate só em `ATIVO` a superfície
  nascia depois do VPS/SPS/PPS e o preview ficava preto para sempre (design.md - Decisão 11).
  O gate vive no overload `MiniaturaDeCamera(viewModel: MirrorViewModel)`, fora do
  composable que guarda o estado de "dispensada"/arraste — assim, sair e voltar da composição
  descarta esse estado, e um novo escaneamento nunca herda o "dispensei" do escaneamento
  anterior.
- [x] 6.3 `OperationScreen.ConteudoProduto` passa a usar a miniatura em vez do preview em
  largura total. Trocado em `MainActivity`: `previa = { MiniaturaDeCamera(mirrorViewModel) }`
  no lugar de `PreviaEspelho(mirrorViewModel)` — `OperationScreen` continua sem conhecer o
  controlador de visão, só o slot muda.
- [x] 6.4 `MirrorScreen.PreviewCard` passa a usar a mesma miniatura em vez do cartão sempre
  visível. `PreviewCard` só compõe `MiniaturaDeCamera` quando `estadoStream == ATIVO`; antes o
  cartão inteiro (incluindo uma superfície preta) ficava sempre visível.
- [x] 6.5 Teste cobrindo que dispensar a miniatura remove a `Surface` de exibição sem desligar a
  câmera nem publicar evento no `PickingActor` (mesmo contrato de `removerPreview` já existente).
  Não há infraestrutura de teste de UI Compose neste projeto (sem `androidTest`, sem
  `compose-ui-test`), e `ControladorDeVisao`/`removerPreview` em si também não têm teste de JVM
  hoje (mesmo padrão de `ReconhecedorDeComando`, que depende do Vosk/Android). A garantia é
  estrutural: dispensar só troca um `remember` local (`dispensada = true`), que para de compor
  `PreviaEspelho` — o `DisposableEffect` que chama `aoRemover` já existe em `PreviaEspelho` e não
  foi alterado por este componente. Verificação visual em bancada na task 9.2.

## 7. Dica de comando de voz em tela

- [x] 7.1 Função pura mapeando `PickingState` para o texto da instrução de voz esperada (ex.:
  "Diga: cheguei"), cobrindo os estados da Decisão 6 mais os que já aceitam "próximo"
  (design.md - Decisão 8). Estados sem avanço por voz não têm dica. `DicaDeComandoDeVoz.kt`.
- [x] 7.2 `OperationUiState`/`OperationViewModel` passam a expor a dica; `OperationScreen` exibe.
  Novo campo `dicaDeVoz` em `OperationUiState`, calculado em `ProjetorDeOperacao.projetar`;
  `OperationScreen.CartaoDaEtapa` exibe logo abaixo da instrução do passo.
- [x] 7.3 Teste de unidade da função pura cobrindo todos os estados com avanço por voz e
  confirmando `null`/ausência nos estados que avançam por câmera ou rede.
  `DicaDeComandoDeVozTest.kt`.

## 8. Redesign visual (princípios do íon Itaú)

- [x] 8.1 Definir paleta e tipografia em um novo pacote `ui/theme/` (`Color.kt`/`Type.kt`/
  `Theme.kt`): duotom verde primário, acento limão/cinza, laranja só em destaques pontuais,
  contraste mínimo AA (4,5:1) em toda combinação texto/fundo usada nas duas telas, alvos de
  toque de pelo menos 48dp (design.md - Decisão 9). Um esquema só (sem dark theme) — o app roda
  em galpão iluminado; alternância de tema fica para quando houver cenário que precise dela.
- [x] 8.2 Aplicar o tema em `OperationScreen` e `MirrorScreen` (`MaterialTheme` custom em vez do
  default do Compose). `AgvPickVoiceTheme` substitui o `MaterialTheme { }` genérico em
  `MainActivity`; as duas telas herdam via `LocalColorScheme`/`LocalTypography` do Compose, sem
  mudança nelas mesmas.
- [x] 8.3 Verificar o contraste de cada combinação texto/fundo efetivamente usada nas duas
  telas contra o mínimo AA — função pura de checagem se viável, checklist manual documentado
  caso contrário. `Contraste.kt` (fórmula da WCAG 2.1, Kotlin puro) + `ContrasteTest.kt`
  cobrindo os pares de cor realmente usados no código (`onSurface`/`surface`,
  `onBackground`/`background`, `primary`/`secondary`/`error` sobre `surface`, e os três pares
  `onXContainer`/`XContainer` do esquema). O botão de dispensar da miniatura fica sobre vídeo,
  não sobre cor fixa — fora do escopo da fórmula, checagem visual em bancada (9.2).

## 9. Verificação (rodada 2)

- [x] 9.1 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/` depois dos grupos 5–8. 283 testes, 0 falhas. Build e lint limpos (17 avisos
  preexistentes, 0 erros, nenhum nos arquivos novos/alterados).
- [x] 9.2 Em bancada: confirmar visualmente a miniatura (aparece só com câmera ativa, nas duas
  telas), a dica de comando de voz e o tema novo; testar "próximo" nos quatro estados
  adicionais. Confirmado por Matheus em bancada em 17/08/2026: testado e ok.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok.

## 10. Correções da bancada de 17/08/2026 (noite)

- [x] 10.1 `DecodificadorHevc.kt`: trava (`travaDoCodec`) entre o consumo da `Image` de saída e o
  encerramento em `parar()`, com `ativo` reconferido dentro da trava — corrige o `SIGSEGV` por
  `use-after-free` que derrubava o app ao sair de `EscaneandoProduto` (design.md - Decisão 10).
  O callback de entrada fica fora da trava de propósito, para o desligamento não travar 1 s.
- [x] 10.2 `MiniaturaDeCamera.kt`: compor já em `EstadoStreamVisao.INICIANDO`, para a superfície
  do preview existir antes do primeiro NAL — sem isso o decodificador do preview nasce no meio do
  stream, nunca recebe VPS/SPS/PPS e a miniatura fica permanentemente preta
  (design.md - Decisão 11). Inclui o rótulo "Iniciando câmera…" enquanto não há frame.
- [x] 10.3 `ControladorDeVisao.ligar()`: repor `estadoStream` para `DESLIGADO` quando `addCamera`
  falha, senão o diagnóstico fica preso em `INICIANDO` e a miniatura nunca some.
- [x] 10.4 `MiniaturaDeCamera.kt`: tamanho de 160x160 para 200x266, na proporção 3:4 do frame —
  a caixa quadrada cortava a imagem.
- [x] 10.5 Saída de `TratandoExcecao` por voz: "próximo" passa a produzir `ExcecaoRegistrada`,
  somado ao relato livre (`InterpretadorDeFala`), com a dica correspondente em
  `DicaDeComandoDeVoz` (design.md - Decisão 12).
- [x] 10.6 Saída de `TratandoExcecao` por toque: `OperationUiState.podeRegistrarOcorrencia`,
  preenchido em `ProjetorDeOperacao`, botão em `OperationScreen` e o único `actor.send` do
  `OperationViewModel`. Nenhum outro estado ganha botão de avanço.
- [x] 10.7 Testes cobrindo as duas saídas da ocorrência e a ausência de botão nos demais estados
  (`InterpretadorDeFalaTest`, `DicaDeComandoDeVozTest`, `ProjetorDeOperacaoTest`); executar
  `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de `AgvPickVoice/`. 193 testes,
  0 falhas; build e lint limpos (17 avisos preexistentes, 0 erros).
- [x] 10.8 Em bancada: confirmar que o app não morre mais ao dizer "avaria" durante o
  escaneamento, que a miniatura mostra vídeo de verdade nas duas telas e que as duas saídas da
  ocorrência funcionam. Confirmado por Matheus em bancada em 17/08/2026: testado e ok — o
  app não crasha mais ao dizer "avaria" em escaneamento, a miniatura mostra vídeo de verdade e
  as duas saídas da ocorrência funcionam.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok.
