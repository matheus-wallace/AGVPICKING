## Why

O trial do Picovoice foi liberado — antes disso, tanto Picovoice Rhino quanto Vivoka estavam
presos em vendor review, não self-serve apesar da documentação do Picovoice sugerir emissão
instantânea de `AccessKey` (achado registrado em `add-sherpa-onnx-omnilingual-decoder`). Com o
trial liberado, Rhino volta a ser uma via real de avaliação, e é a única alternativa de motor
ainda não testada neste projeto depois de duas rodadas de troca via sherpa-onnx (Whisper-tiny
autoregressivo e Omnilingual ASR CTC) terem sido fechadas por falha — a primeira alucinando
texto em comandos curtos isolados mesmo com sinal limpo, a segunda decodificando para outros
idiomas/scripts por o checkpoint não ter seletor de idioma em runtime, sem `hotwords` disponível
porque esse mecanismo só existe em modelos transducer e o sherpa-onnx nunca publicou um
transducer pt-BR (`add-sherpa-onnx-omnilingual-decoder`). Vosk segue como motor em produção,
funcional mas com as limitações de precisão já documentadas em `add-voice-recognition-reliability`.

Rhino é arquiteturalmente diferente das duas tentativas anteriores: é um motor de
fala-para-intenção de vocabulário fechado, treinado por contexto, categoria mais próxima do que
faz o Vosk funcionar hoje (gramática fechada por estado) do que dos decodificadores de texto
livre já testados. Vale avaliar antes de investir mais bancada só em calibração do Vosk.

## What Changes

- Nova implementação de `MotorDeAsr` (`audio/MotorDeAsr.kt`) usando Picovoice Rhino
  (`ai.picovoice:rhino-android`), convivendo no binário com `MotorVosk` e `MotorSherpaOnnx` sem
  substituir nenhuma das duas — mesmo padrão de troca por uma linha em `AppContainer.kt` que já
  vale para `FonteAudio`/`MotorDeAsr` desde `add-sherpa-onnx-asr-engine` (Decisão 1 daquele
  change).
- **Decisão de design em aberto, a resolver em design.md antes de qualquer código**: Rhino não
  decodifica texto livre — a saída nativa é `isUnderstood` + `intent` + `slots` estruturados, o
  que não bate diretamente com o contrato de `SessaoDeAsr.aceitar(): ResultadoDeAsr`
  (`ResultadoDeAsr.Fechada(texto: String)`). Duas rotas possíveis: (a) sintetizar um texto a
  partir do intent/slot reconhecido, mantendo `InterpretadorDeFala`/`VocabularioDeVoz` intocados;
  ou (b) abrir um caminho de consumo que aceite intenção estruturada direto do motor, o que
  quebra a promessa atual de `MotorDeAsr` de que "o motor não conhece `PickingState`/
  `PickingEvent`" (`MotorDeAsr.kt`, linhas 13-20).
- **Segunda decisão em aberto**: cada contexto Rhino é um arquivo `.rhn` compilado previamente no
  Picovoice Console, específico de idioma e plataforma — não existe troca de gramática em
  runtime como a que `SeletorDeEscuta` já faz hoje para o Vosk (`ConfiguracaoDeEscuta.gramatica`
  reconstruída por `PickingState`). Trocar de contexto no SDK Android
  (`Rhino.Builder.setContextPath`) significa destruir e recriar a instância do `Rhino`, não
  trocar um parâmetro leve. A proposta cobre a avaliação de um contexto único (cobrindo todo o
  vocabulário de todos os estados, com o filtro por estado continuando a acontecer depois, como
  hoje) contra N contextos pré-compilados trocados por transição de estado.
- **Segredo novo**: `AccessKey` do Picovoice Console, nunca hardcoded nem commitado — entra em
  `local.properties` ou mecanismo equivalente já usado no projeto para segredos, a confirmar em
  design.md.
- **Risco a fechar cedo, antes de qualquer integração**: a documentação pública do Rhino lista
  suporte a "Portuguese" sem confirmar explicitamente a variante pt-BR. Gerar um contexto de
  teste no Console com o vocabulário fechado de `VocabularioDeVoz` e confirmar reconhecimento
  em pt-BR real é tarefa de bancada antes de qualquer decisão de troca completa.
- Sem mudança de evento de domínio: os mesmos `PickingEvent`s continuam sendo produzidos,
  independente de qual das duas rotas acima for escolhida.
- Este change é planning-only, como todos os changes recentes deste projeto — nenhuma
  implementação começa aqui.

## Capabilities

### New Capabilities
(nenhuma)

### Modified Capabilities
- `audio-source`: o mecanismo de reconhecimento pode passar a incluir um motor de
  fala-para-intenção (Rhino) ao lado dos motores de fala-para-texto já existentes (Vosk,
  sherpa-onnx) — o requisito de que um comando reconhecido publica o `PickingEvent`
  correspondente continua valendo, mas a via de decisão de intenção pode deixar de ser
  "decodificar texto, depois casar contra o vocabulário esperado" e passar a ser "o motor
  já decide a intenção", dependendo de qual das duas rotas de design for escolhida.
- `state-driven-voice-flow`: caso a rota escolhida seja de múltiplos contextos pré-compilados
  por agrupamento de estado, a troca de gramática por estado deixa de ser uma reconstrução de
  configuração em memória (`SeletorDeEscuta` hoje) e passa a incluir destruir/recriar a
  instância do motor — muda o mecanismo, não o comportamento observável de "o vocabulário
  aceito depende do estado atual".

## Impact

- Código novo: integração Rhino (`audio/MotorPicovoice.kt` ou nome equivalente a decidir em
  design.md), dependência Gradle `ai.picovoice:rhino-android`, contexto(s) `.rhn` vendorizados
  em `assets/` (pegada de armazenamento muito menor que as alternativas já testadas: menos de
  2,5 MB para runtime + contexto Rhino, contra 51 MB do modelo Vosk pt-BR e ~146-292 MB dos
  modelos sherpa-onnx já revertidos).
- Segredo novo: `AccessKey` do Picovoice Console, fora do controle de versão.
- Código alterado, dependendo da rota de design escolhida: possivelmente `InterpretadorDeFala`,
  `SeletorDeEscuta`, `AppContainer.kt` (wiring). Sem mudança em `domain/`, `data/`, `vision/`.
- Sem remoção de `MotorVosk` nem `MotorSherpaOnnx` — ambos continuam no binário, motor ativo
  segue sendo decisão de uma linha em `AppContainer.kt`.
- Bancada: Rhino nunca foi testado neste projeto. Toda a calibração de bancada registrada para
  Vosk/sherpa-onnx (limiar de dBFS, protocolo com voz humana direta) não tem por que valer para
  um motor de arquitetura tão diferente — precisa de rodada própria, começando pela confirmação
  de pt-BR real antes de qualquer investimento maior.
