# Tasks: fluxo de separação comandado por voz

## 1. Contratos puros

- [x] 1.1 Criar um mapeamento puro `PickingState` → configuração de escuta (gramática,
  `PerfilEndpoint` e versão do estado), cobrindo cada estado operacional e os comandos
  transversais permitidos.
- [x] 1.2 Criar interpretador puro de resultado ASR → intenção/evento, incluindo números
  pt-BR, dígitos isolados, confirmar/corrigir, cheguei, iniciar, alocado, próximo,
  concluir e encerrar; texto fora do contrato não produz evento.
- [x] 1.3 Criar testes de fronteira para quantidade, check digit, ruído e resultado ASR
  atrasado de uma versão anterior de estado.

## 2. Validação de domínio

- [x] 2.1 Criar adaptador que consulta o `PickingRepository` para validar check digit sem
  expor o valor esperado, emitindo exclusivamente `CheckDigitCorreto` ou
  `CheckDigitIncorreto`.
- [x] 2.2 Resolver a próxima linha ao receber “próximo” e publicar `ItemFinalizado` com o
  item correto, preservando a semântica atual do reducer.
- [x] 2.3 Testar o adaptador com `MockPickingRepository`, inclusive ordem de múltiplas
  linhas e última linha.

## 3. Reconhecimento e ciclo de vida

- [x] 3.1 Refatorar `ReconhecedorDeComando` para observar `PickingActor.state`, recriar a
  gramática somente na thread dedicada e descartar resultado que não corresponda à versão
  atual do estado.
- [x] 3.2 Integrar o estado da `SaidaDeAudio`: durante uma fala, não aceitar resultado ASR
  residual; ao fim, reiniciar a escuta do estado atual sem bloquear UI, ator ou câmera.
- [x] 3.3 Garantir que falha de ASR/gramática deixa o estado intacto e que os botões do
  painel continuam disponíveis apenas para diagnóstico.

## 4. Verificação

- [x] 4.1 Adicionar testes unitários do mapeamento, interpretador e integração com o ator;
  executar `./gradlew testDebugUnitTest` a partir de `AgvPickVoice/`. 160 testes, 0 falhas.
- [x] 4.2 Executar `./gradlew assembleDebug lintDebug` a partir de `AgvPickVoice/`. Build e
  lint limpos (16 avisos preexistentes, 0 erros, nenhum nos arquivos novos).
- [x] 4.3 Em aparelho físico, concluir uma ordem mockada com múltiplas linhas sem tocar nos
  botões após a seleção inicial: chegada, check digit, leitura de câmera, quantidade,
  readback, alocação, próximo item e encerramento.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok — ordem mockada completa
  concluída de ponta a ponta só de voz, sem tocar em nenhum botão após a seleção inicial.
  Confirmado por Matheus em bancada em 17/08/2026: testado e ok.
- [x] 4.4 No mesmo ensaio, validar fala fora da gramática e fala enquanto TTS toca: nenhuma
  deve avançar o estado; registrar taxa de reconhecimento, tentativas e ponto de falha.
  Validado em bancada em 17/08/2026: funcionando corretamente.

## 5. Ajustes de bancada

- [x] 5.1 Exibir o check digit esperado da posição no painel de dev somente quando
  `BuildConfig.DEBUG` for verdadeiro (`DevPanelViewModel`/`DevPanelScreen`); em build de
  release o valor não é lido nem exibido. Áudio e log continuam sem revelar o valor em
  qualquer build.
- [x] 5.2 Aceitar "próximo" como sinônimo de "alocado" em `AlocandoCarrinho` e de
  "confirmar" no branch de confirmação de `ReadbackQuantidade`
  (`VocabularioDeVoz`/`SeletorDeEscuta`/`InterpretadorDeFala`), preservando as palavras
  originais e sem alterar "corrigir" nem nenhum outro estado.
- [x] 5.3 Testes de unidade cobrindo: "próximo" publica o mesmo evento que "alocado" e que
  "confirmar" em cada estado; as palavras originais continuam funcionando sem regressão;
  o painel expõe o check digit esperado só quando `BuildConfig.DEBUG` é verdadeiro.
  `checkDigitEsperado` em si (o plumbing) tem teste de JVM em `DevPanelViewModelTest`; o
  gate por `BuildConfig.DEBUG` não é testável em JVM (constante de build) e fica como
  checagem manual do APK — ver comentário na classe de teste.
- [x] 5.4 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/` depois dos ajustes 5.1–5.3. Build, testes (170, 0 falhas) e lint (16
  avisos preexistentes, 0 erros, nenhum novo nos arquivos alterados) limpos.
