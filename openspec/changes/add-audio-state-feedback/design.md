# Design: saída de áudio orientada ao estado

## Context

O projeto tem entrada de áudio real/simulada (`FonteAudio` e Vosk), mas não possui
saída. A tabela de estados do documento técnico declara explicitamente o que deve ser
falado em cada etapa. A visão recém-implementada também publica um sinal textual e
efêmero de enquadramento após oito segundos, hoje visível só na tela espelho.

O alvo de produção é Piper pré-renderizado a 8 kHz no HFP dos óculos. Esses assets e
essa rota ainda não existem no repositório; esta fatia não deve fingir que existem.

## Goals

- Ouvir instruções básicas de picking usando o celular em desenvolvimento.
- Isolar o projeto de mensagens da tecnologia de síntese e rota de saída.
- Levar a orientação de enquadramento ao operador uma única vez por escaneamento.
- Garantir preempção, deduplicação e parada no background.

## Non-Goals

- Piper, inventário completo de 150 fragmentos, HFP dos óculos ou AEC de playback.
- Troca de gramática/VAD/ASR, parsing de dígitos ou novos eventos de domínio.
- Tocar qualquer áudio de imagem, salvar transcrição ou enviar dados a serviços externos.
- Tornar a fala de `ComandoRepetir` completa; ela exige histórico de fala e será uma
  extensão posterior explícita.

## Decisions

### 1. Separar projeção de mensagem e reprodução

`ProjetorDeFalaPicking` será Kotlin puro: recebe `PickingState` e produz uma
`MensagemFalavel` com texto, identificador de deduplicação e prioridade. Ele não
conhece Android, corrotinas, TTS ou o ator. Isso mantém o texto testável e permite
trocar a implementação por clips Piper sem alterar regras operacionais.

`SaidaDeAudio` será uma interface pequena (`iniciar`, `falar`, `parar`, `fechar`).
`SaidaTextToSpeechAndroid` é a implementação de debug/desenvolvimento: usa pt-BR,
não persiste texto, trata inicialização assíncrona e expõe somente estado/erro pequeno
para diagnóstico. A implementação Piper/HFP futura satisfará o mesmo contrato.

### 2. Observador de estado fora do ator

`ControladorDeFala` observa `PickingActor.state` com `distinctUntilChanged`, converte
o estado no projetor e envia a mensagem à saída. Ele nunca envia eventos ao ator nem
altera estado. A mensagem inclui somente informação que já está no estado atual, como
endereço e quantidade; nunca inclui o valor esperado de check digit.

Estados iniciais a verbalizar: `PreparandoSessao`, `OrdemCarregada`,
`NavegandoParaEndereco`, `AguardandoCheckDigit`, `EscaneandoProduto`,
`ConfirmandoQuantidade`, `ItemConcluido`, `VerificacaoAssistida`, `Erro` e
`OrdemConcluida`. Mensagens de transição silenciosa permanecem silenciosas.

### 3. Ponte de orientação sem pixels

O mesmo controlador observa `DiagnosticoVisao`, mas só considera
`orientacaoPendente == true` se o ator estiver em `EscaneandoProduto`. Um identificador
de ciclo formado pela entrada no estado impede repetição até a próxima entrada. A
mensagem fixa “aponte para o código do produto” não recebe código, métricas, ROI ou
qualquer objeto visual.

### 4. Fila pequena com prioridades

Mensagens de rotina usam fila; erro e falha de validação são críticas e chamam
`parar()` antes de falar. O controlador deduplica por chave dentro da mesma entrada
de estado. A saída Android usa `QUEUE_ADD` para rotina e `QUEUE_FLUSH` para crítica,
mas essa decisão fica dentro do adaptador para que o contrato não dependa de TTS.

### 5. Ciclo de vida de Activity

`MainActivity.onStart` inicializa/inicia a saída depois das permissões; `onStop` para
qualquer fala e libera o motor. A criação é idempotente e uma falha de inicialização
não impede DAT, câmera ou reconhecimento. O estado de deduplicação vive no
controlador de processo e não é apagado por recomposição de Compose.

## Alternatives Considered

- **Implementar Piper/HFP agora:** rejeitado porque não há clips nem rota HFP para validar;
  criaria uma integração não exercitável antes de termos a projeção de mensagens.
- **Chamar `TextToSpeech` diretamente na tela:** rejeitado porque mistura UI, lifecycle e
  regras operacionais e duplicaria fala após recomposições.
- **Falar cada atualização de diagnóstico:** rejeitado por gerar loop de fala; somente a
  borda falsa→verdadeira de orientação é relevante.

## Risks and Mitigations

- O motor Android pode não ter voz pt-BR offline: diagnóstico claro e degradação sem crash.
- TTS pode conflitar com captura do microfone simulado: a fatia registra a limitação; AEC/HFP
  pertence ao slice de rota real.
- Mensagens excessivas cansam: tabela inicial curta, chaves de deduplicação e preempção.

## Migration Plan

Não há dados persistidos. A futura saída Piper/HFP substitui apenas a fiação de
`SaidaDeAudio` no `AppContainer`; projetor, prioridades e observadores permanecem.
