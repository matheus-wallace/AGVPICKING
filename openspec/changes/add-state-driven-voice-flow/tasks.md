# Tasks: fluxo de separação comandado por voz

## 1. Contratos puros

- [ ] 1.1 Criar um mapeamento puro `PickingState` → configuração de escuta (gramática,
  `PerfilEndpoint` e versão do estado), cobrindo cada estado operacional e os comandos
  transversais permitidos.
- [ ] 1.2 Criar interpretador puro de resultado ASR → intenção/evento, incluindo números
  pt-BR, dígitos isolados, confirmar/corrigir, cheguei, iniciar, alocado, próximo,
  concluir e encerrar; texto fora do contrato não produz evento.
- [ ] 1.3 Criar testes de fronteira para quantidade, check digit, ruído e resultado ASR
  atrasado de uma versão anterior de estado.

## 2. Validação de domínio

- [ ] 2.1 Criar adaptador que consulta o `PickingRepository` para validar check digit sem
  expor o valor esperado, emitindo exclusivamente `CheckDigitCorreto` ou
  `CheckDigitIncorreto`.
- [ ] 2.2 Resolver a próxima linha ao receber “próximo” e publicar `ItemFinalizado` com o
  item correto, preservando a semântica atual do reducer.
- [ ] 2.3 Testar o adaptador com `MockPickingRepository`, inclusive ordem de múltiplas
  linhas e última linha.

## 3. Reconhecimento e ciclo de vida

- [ ] 3.1 Refatorar `ReconhecedorDeComando` para observar `PickingActor.state`, recriar a
  gramática somente na thread dedicada e descartar resultado que não corresponda à versão
  atual do estado.
- [ ] 3.2 Integrar o estado da `SaidaDeAudio`: durante uma fala, não aceitar resultado ASR
  residual; ao fim, reiniciar a escuta do estado atual sem bloquear UI, ator ou câmera.
- [ ] 3.3 Garantir que falha de ASR/gramática deixa o estado intacto e que os botões do
  painel continuam disponíveis apenas para diagnóstico.

## 4. Verificação

- [ ] 4.1 Adicionar testes unitários do mapeamento, interpretador e integração com o ator;
  executar `./gradlew testDebugUnitTest` a partir de `AgvPickVoice/`.
- [ ] 4.2 Executar `./gradlew assembleDebug lintDebug` a partir de `AgvPickVoice/`.
- [ ] 4.3 Em aparelho físico, concluir uma ordem mockada com múltiplas linhas sem tocar nos
  botões após a seleção inicial: chegada, check digit, leitura de câmera, quantidade,
  readback, alocação, próximo item e encerramento.
- [ ] 4.4 No mesmo ensaio, validar fala fora da gramática e fala enquanto TTS toca: nenhuma
  deve avançar o estado; registrar taxa de reconhecimento, tentativas e ponto de falha.
