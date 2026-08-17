## Context

Ver `proposal.md` - Why. Achados de bancada de 17/08/2026, ainda não commitados em código:

- `ProjetorDeFalaPicking.kt` (branch de `ReadbackQuantidade`, `AlocandoCarrinho`,
  `ConferenciaFinal`, `SessaoPausada`) confirma retorno `null` — nenhuma fala nesses estados.
  Só `ReadbackQuantidade` é o alvo desta fatia; os outros três já têm cobertura textual na tela
  (`OperationScreen`) e não foram reportados como confusos.
- O reducer já resolve `ReadbackCorrecaoSolicitada` → `ConfirmandoQuantidade` preservando o item
  em andamento (`PickingReducer.kt:175-179`) — a fatia só precisa acrescentar a fala, não mudar
  transição nenhuma.
- `AjustesVisao.kt` tem `cooldownCapturaMs` (1500ms, intervalo entre tentativas de captura) e
  `timeoutOrientacaoMs` (8000ms, aviso de orientação incorreta) — nenhum dos dois é obviamente
  "a câmera fecha depois de N segundos". A causa relatada por Matheus (câmera "fecha" antes de
  ele terminar de abrir a caixa e contar) ainda não foi localizada no código nesta sessão; ver
  Open Questions.

## Goals / Non-Goals

**Goals:**
- Fala clara em `ReadbackQuantidade`, sem exigir leitura de tela.
- Log estruturado de 100% dos resultados finais do ASR, aceitos ou não, para medir taxa de
  reconhecimento por comando em bancada.
- Diagnosticar e corrigir a causa real do fechamento prematuro da câmera em `EscaneandoProduto`.

**Non-Goals:**
- Não é um retrabalho do pipeline de ASR/VAD em si (silêncio final, limiar dBFS) — isso já está
  documentado em [[reference-voz-bancada]] e é ajuste de calibração, não de arquitetura.
- Não adiciona um passo de leitura óptica da etiqueta de posição (a ideia de bipar a etiqueta na
  chegada, hoje substituída por check digit falado) — é uma mudança de fluxo maior, fica para
  decisão futura separada se o check digit falado não se provar suficiente.
- Não altera `PickingReducer`/`PickingEvent`/`PickingRepository`.

## Decisions

1. **Fala do readback é só mais um branch em `ProjetorDeFalaPicking`, mesmo padrão dos outros
   estados.** Nenhuma mudança estrutural — o projetor já é um `when` sobre `PickingState`
   retornando mensagem opcional; hoje `ReadbackQuantidade` retorna `null` só porque nunca foi
   preenchido, não por decisão deliberada registrada em nenhum design anterior.

2. **Log de ASR descartado usa o mesmo canal estruturado já usado para gramática/nível
   (`ReconhecedorDeComando`), não um canal novo.** Bancada já lê logcat nessa mesma convenção
   ([[reference-voz-bancada]]); um formato novo só para resultados descartados fragmentaria a
   leitura em vez de ajudar a medir precisão.

3. **A causa raiz do fechamento prematuro da câmera fica para diagnóstico amanhã, não para
   suposição hoje.** Duas hipóteses concorrentes, ambas plausíveis e com correções diferentes:
   (a) existe um teto de tempo que encerra `EscaneandoProduto` cedo demais — a correção é
   estender ou remover esse teto; (b) não há teto nenhum, e o que Matheus está vendo é uma
   leitura prematura/errada (algo no campo de visão antes da caixa estar pronta) que já satisfaz
   `ConsensoDeLeitura` e avança o estado sozinho — a correção seria outra (gating por sinal
   explícito do operador antes de aceitar leitura, não estender tempo). Decidir errado aqui
   custaria uma correção que não resolve o sintoma relatado.

## Risks / Trade-offs

- **[Risco]** Falar "Confirma {quantidade}?" produz uma fala nova exatamente no estado em que o
  TTS já compete com o ASR (Decisão 6 de `add-state-driven-voice-flow`) — precisa respeitar o
  mesmo gate de `SaidaDeAudio.falando` já existente, não introduzir uma janela nova de disputa.
  **Mitigação:** reaproveitar o mecanismo já implementado, não criar um novo.
- **[Risco]** Logar 100% dos resultados do ASR pode incluir texto sensível de exceção em
  `TratandoExcecao` (texto livre) em log de bancada. **Mitigação:** aplicar a mesma regra que já
  vale para o painel/áudio — nenhum valor protegido (check digit esperado) pode aparecer; texto
  livre do operador não é dado protegido pelo doc §9, mas vale confirmar no diagnóstico de
  amanhã antes de logar em produção (só bancada/debug por ora).

## Open Questions

- ~~**Qual é a causa real do fechamento prematuro da câmera?**~~ Respondida (ver Decisão 4).

## Contexto (rodada 2 — pedidos adicionais de 17/08/2026)

Quatro pedidos que chegaram depois da bancada, fora do escopo original do `Why` (que era só as
três lacunas testadas), mas que Matheus optou por manter neste mesmo slice em vez de abrir
changes novas:

- "Próximo" hoje só é sinônimo em dois estados (`AlocandoCarrinho`, `ReadbackQuantidade`,
  design.md - Decisão 8 de `add-state-driven-voice-flow`), deliberadamente estreito naquele
  momento. O pedido agora é estender a todo estado que só espera uma palavra de avanço.
- `PreviaEspelho` (`ui/mirror/PreviaEspelho.kt`) é `.fillMaxWidth().aspectRatio(proporcao)` — com
  `QualidadeStream.MEDIA` (504×896), a proporção é ~0,56, ou seja, a altura do card quase dobra a
  largura da tela. É embutida em largura total tanto em `OperationScreen.ConteudoProduto` quanto
  em `MirrorScreen.PreviewCard`, e o cartão do `MirrorScreen` fica sempre visível mesmo com
  `estadoStream == DESLIGADO` (uma caixa preta). Os dois pontos são o que Matheus chamou de
  "ocupando muito espaço".
- Não existe hoje nenhuma indicação em tela de qual palavra o operador precisa dizer — só a
  instrução textual do passo (`OperationScreen.CartaoDaEtapa`), que descreve o que fazer, não o
  que falar.
- Não existe `ui/theme/` — as duas telas usam `MaterialTheme` default do Compose, sem paleta
  nem tipografia definidas para o app.

## Decisões (atualização pós-diagnóstico)

4. **A causa não era nenhuma das duas hipóteses da Decisão 3 — era o orçamento de tentativas do
   fallback por foto.** `GatilhoDeCaptura` (`MetricasCaptura.kt`) dispara uma captura assim que 3
   quadros consecutivos (`quadrosEstaveisParaCaptura`) ficam nítidos e estáveis o bastante — sem
   nenhum sinal de que o operador já abriu a caixa. Cada tentativa fracassada custava só 1,5s de
   cooldown (`cooldownCapturaMs`) até a próxima, e só existiam 3 tentativas
   (`maxTentativasCaptura`). Esgotadas — o que acontecia em poucos segundos, muitas vezes em cima
   de conteúdo que não era o código (caixa fechada, mão do operador) — `publicarEsgotamento()`
   mandava `DecodificacaoFalhou`, e o reducer tirava o app de `EscaneandoProduto` para
   `VerificacaoAssistida` (cascata de nuvem do doc §6.4). Era isso que o operador via como "a
   câmera fecha".
5. **Decisão do dono do produto: remover o escalonamento automático por esgotamento, não só
   recalibrar os números.** Testes de bancada de 17/08/2026 não registraram nenhuma falha real de
   leitura — o reconhecimento direto do stream (ML Kit por frame, já sem teto) deu conta sozinho
   em todos os casos testados. `maxTentativasCaptura` e `EstadoCapturaFoto.ESGOTADA` foram
   removidos; `GatilhoDeCaptura.registrarFracasso` só aplica cooldown e reinicia a sequência de
   estabilidade, sem sinalizar esgotamento. `EscaneandoProduto` agora só termina por sucesso
   (código confirmado) ou por comando de voz transversal já existente (`avaria`, `divergência`,
   `ruptura`, `parar` — todos válidos nesse estado porque `ehOperacional` é `true` por padrão e
   `EscaneandoProduto` não o sobrescreve). `VerificacaoAssistida` fica sem caminho automático de
   entrada por ora; o código e o evento `DecodificacaoFalhou` continuam existindo no domínio
   (usados por `DecodificandoProduto`, caminho que este slice não toca), então reativar a
   escalação automática mais tarde é só voltar a chamá-la em `ControladorDeVisao`, não uma
   reintrodução de arquitetura.

6. **"Próximo" se estende só a estados com uma única palavra de avanço, não a todo estado
   operacional.** Ganham o sinônimo: `OrdemCarregada` ("iniciar"), `NavegandoParaEndereco`
   ("cheguei"), `ConferenciaFinal` ("concluir"), `OrdemConcluida` ("encerrar"). Não se estende a
   `AguardandoCheckDigit` (espera dois dígitos, não uma palavra), `ConfirmandoQuantidade` (espera
   um número), `EscaneandoProduto`/`DecodificandoProduto`/`VerificacaoAssistida`/
   `ValidandoContraDados` (avançam por câmera/rede, nunca por voz — spec de
   `add-state-driven-voice-flow`) nem `TratandoExcecao` (vocabulário aberto, não uma palavra
   fixa). "Próximo" sozinho não resolveria nenhuma dessas entradas.

7. **A miniatura de câmera é um componente Compose interno, não Picture-in-Picture do Android.**
   `PictureInPictureParams` exige lidar com o ciclo de vida da `Activity` fora do processo do
   app (a janela sobrevive à navegação do sistema), o que não se paga no prazo do hackathon para
   um preview que só existe durante `EscaneandoProduto`. A miniatura envolve a `PreviaEspelho`
   já existente (reaproveitando a lógica de anexar/remover `Surface` já verificada em bancada)
   num tamanho fixo pequeno, ancorada a um canto, arrastável e com um botão de dispensar — só a
   apresentação muda, não o ciclo de vida da câmera. Vale para as duas telas
   (`OperationScreen`, `MirrorScreen`) e só é composta quando `diagnostico.estadoStream ==
   EstadoStreamVisao.ATIVO`: sem isso, `MirrorScreen` continuaria mostrando uma caixa preta com
   a câmera desligada, o mesmo problema de espaço que o pedido quer resolver, só que sempre
   visível em vez de só durante o escaneamento.

8. **A dica de comando de voz é função pura, mesmo padrão de `ProjetorDeFalaPicking`.** Mapeia
   `PickingState` para o texto da instrução esperada (ex.: "Diga: cheguei"), lendo do mesmo
   vocabulário que `InterpretadorDeFala` já usa — não duplica a lista de palavras aceitas em uma
   segunda fonte de verdade que poderia divergir dela. Só aparece nos estados com avanço por voz
   (a mesma lista da Decisão 6, mais os que já aceitavam "próximo"); estados que avançam por
   câmera/rede não têm dica, porque não há nada para o operador dizer ali.

9. **O redesign usa os princípios confirmados do íon Itaú, não cores copiadas da marca.** O
   artigo de referência (texto extraído do HTML salvo por Matheus, não o site com hex fabricados
   que se provou inconsistente com a resenha confiável) descreve: base WCAG 2.0/2.1, nível AA
   exige contraste mínimo 4,5:1, AAA exige 7:1; paleta com "dois tons de verde como cores
   principais, com detalhes de verde-limão e cinza. O laranja Itaú está presente na paleta, mas
   sua utilização é pontual e exclusiva". O artigo é um relato de processo, não uma folha de
   tokens — não há hex exatos publicados. `add-operator-feedback-improvements` define uma paleta
   própria para o AGV Pick Voice seguindo esses princípios (duotom verde primário, acento
   limão/cinza, laranja só em destaques pontuais, contraste mínimo AA em toda combinação
   texto/fundo usada, alvos de toque de pelo menos 48dp) — não reaproveita logotipo, nome ou
   qualquer ativo de marca do Itaú.

## Non-Goals (rodada 2)

- Não implementa Picture-in-Picture nativo do Android (`PictureInPictureParams`) — a miniatura é
  um componente Compose interno ao app (Decisão 7).
- Não estende "próximo" a estados sem uma única palavra de avanço por voz (Decisão 6).
- Não usa logotipo, nome ou qualquer ativo de marca do Itaú — só os princípios de acessibilidade
  publicados sobre o íon (Decisão 9).

## Bancada (rodada 3 — 17/08/2026, noite)

Primeira execução real da tela nova com o operador separando. Três defeitos, todos com causa
confirmada por evidência (logcat), nenhum por hipótese.

10. **O app morria no meio da separação: `use-after-free` entre o codec e o desligamento da
    câmera.** `adb logcat -b crash` registrou `SIGSEGV` (`SEGV_MAPERR`) na thread
    `DecodificadorHe`, dentro de `recortarParaNv21` -> `DirectByteBuffer.get` ->
    `Memory_peekByte`. `DecodificadorHevc.aoReceberBufferDeSaida` lê a flag `ativo` **uma vez** e
    em seguida passa alguns milissegundos recortando uma `Image` cujos planos apontam para
    memória do próprio codec; nesse intervalo `ControladorDeVisao.desligar()` roda na main thread
    (qualquer saída de `EscaneandoProduto` — inclusive o transversal "avaria") e chama `parar()`
    -> `codec.release()`, liberando a memória debaixo de quem lê. Como a leitura é nativa, o
    processo morre em vez de lançar exceção. O logcat confirma a ordem: "Stream de câmera
    encerrado" às 18:02:33.442, sinal fatal às 18:02:34.441.

    Correção: um monitor `travaDoCodec` segurado durante todo o consumo da imagem de saída e
    durante o encerramento em `parar()`, com o `ativo` reconferido **dentro** da trava. O
    callback de **entrada** ficou deliberadamente de fora: ele bloqueia até 1 s em
    `fila.poll`, e segurar a trava lá transformaria cada desligamento de câmera numa pausa de 1 s
    na UI — aquele caminho continua protegido pela exceção de estado do próprio `MediaCodec`,
    que é de Java e já é tratada.

11. **A miniatura ficava permanentemente preta — a causa era o gate de visibilidade, não o
    `SurfaceView`.** A troca `SurfaceView` -> `TextureView` continua correta para arraste e
    transformação, mas não era o que causava a tela preta. O decodificador do preview só nasce
    quando a superfície da miniatura é anexada, e a miniatura só era composta em `estadoStream ==
    ATIVO` — ou seja, **depois** de o stream já ter emitido VPS/SPS/PPS. Um decodificador HEVC
    criado no meio do stream nunca recebe esses cabeçalhos, e por isso nunca ativa (`ativo` só
    vira `true` num keyframe, e o replay da configuração depende de um cache que ele nunca
    chegou a preencher). Evidência: só **uma** linha "Formato de saída negociado" por sessão no
    logcat, a do decodificador de análise; nunca a segunda, do preview.

    Correção: compor a miniatura já em `INICIANDO`, para a superfície existir antes do primeiro
    NAL — que é exatamente o arranjo de quando o cartão de preview do `MirrorScreen` era sempre
    visível (`6dd9ddf`) e o espelho funcionava. Junto: `ligar()` passou a repor `estadoStream`
    para `DESLIGADO` quando `addCamera` falha (antes o diagnóstico ficava preso em `INICIANDO`
    para sempre — inofensivo com o gate antigo, mas com o novo prenderia uma miniatura morta na
    tela), e a miniatura mostra "Iniciando câmera…" enquanto não há frame, para o instante
    anterior ao stream não ser um retângulo preto sem explicação. O tamanho foi de 160x160 para
    200x266: o quadrado **cortava** a imagem, porque `PreviaEspelho` ocupa a largura toda e
    deriva a altura da proporção 3:4 do frame (480x640), pedindo 213dp de altura numa caixa de
    160dp.

12. **`TratandoExcecao` era um beco sem saída.** O vocabulário é aberto e a única saída era
    `ExcecaoRegistrada`, produzido só por um relato falado de 3+ palavras
    (`PALAVRAS_MINIMAS_DO_RELATO`) ou pelo botão do painel de dev — e a tela de operação não tem
    botão. Quando o ASR não fechava um relato inteiro, o operador ficava preso. "parar" e
    "retomar" não resolvem: `SessaoPausada` guarda o estado anterior e devolve para
    `TratandoExcecao`, fechando um ciclo.

    Correção com duas saídas independentes, de propósito: (a) "próximo" sozinho também produz
    `ExcecaoRegistrada` ali, somando-se ao relato livre sem substituí-lo; (b) um botão de toque
    "Registrar ocorrência e seguir", exibido só quando o novo campo
    `OperationUiState.podeRegistrarOcorrencia` é verdadeiro. O item (b) obrigou o
    `OperationViewModel` a ganhar o seu **primeiro e único** `actor.send` — quebra deliberada e
    documentada da propriedade "só consome" daquela classe, justificada por este ser exatamente o
    estado em que a voz pode não estar dando conta. Fora de `TratandoExcecao` a tela continua sem
    nenhum botão de avanço.
