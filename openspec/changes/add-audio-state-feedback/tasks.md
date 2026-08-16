# Tasks: saída de áudio orientada ao estado

## 1. Contratos e regras puras

- [x] 1.1 Criar `MensagemFalavel`, prioridade e `ProjetorDeFalaPicking` sem Android.
- [x] 1.2 Mapear a primeira tabela de estados em mensagens curtas e adicionar testes para
  endereço, quantidade, check digit protegido, erros e estados silenciosos.
- [x] 1.3 Criar deduplicação por entrada de estado e por ciclo de escaneamento; testar a
  orientação da visão uma única vez por ciclo.

## 2. Adaptador de saída Android

- [x] 2.1 Criar `SaidaDeAudio` e `SaidaTextToSpeechAndroid`, com inicialização assíncrona,
  pt-BR, estado de disponibilidade e erro sem texto sensível.
- [x] 2.2 Implementar fila de rotina e preempção de alerta crítico, sem bloquear ator, UI,
  decodificador ou thread de ASR.
- [ ] 2.3 Parar e fechar recursos de síntese no background e cobrir o adaptador com testes
  instrumentados onde o motor Android for necessário.

## 3. Integração

- [x] 3.1 Criar `ControladorDeFala` que observa estado e diagnóstico de visão, sem publicar
  eventos de domínio.
- [x] 3.2 Registrar no `AppContainer` e iniciar/parar na `MainActivity` de modo idempotente.
- [x] 3.3 Exibir no painel de desenvolvimento somente o estado da saída e a última chave de
  mensagem, nunca o conteúdo de imagem ou buffers de visão.

## 4. Validação

- [x] 4.1 Executar `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` e
  `./gradlew lintDebug`. **109 testes, 0 falhas; build e lint concluídos com sucesso.**
- [ ] 4.2 No Galaxy, validar fala pt-BR, preempção de erro e a orientação após oito segundos
  sem enquadramento; registrar somente duração e resultado.
- [ ] 4.3 Validar que pausar/voltar ao app não deixa fala tocando nem duplica instrução.
