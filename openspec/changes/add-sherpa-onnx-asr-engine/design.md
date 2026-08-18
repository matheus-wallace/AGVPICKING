## Context

Ver proposal.md - Why. Ponto de partida técnico, levantado antes de escrever este design:

- Pesquisa no model zoo do sherpa-onnx (k2-fsa): **não existe modelo de streaming (online)
  nativo para português**. A via real de pt-BR nesse toolkit é Whisper multilíngue
  exportado para ONNX, que é offline — decodifica um trecho de áudio já delimitado, não
  amostra a amostra como o Vosk fazia via `Recognizer.acceptWaveForm`.
- O Silero VAD que substitui o endpointer do Vosk já era esperado pela arquitetura do
  projeto desde o início: o contexto deste repositório já lista "Silero VAD (ONNX
  Runtime)" como parte da pilha de IA local, e já documenta que sessões ONNX Runtime **não
  são thread-safe** — a mesma restrição de confinamento de thread que hoje vale só para o
  `Model`/`Recognizer` do Vosk (`ReconhecedorDeComando`, `dispatcherAudio`) passa a valer
  também para o VAD e para o decodificador Whisper, sem mudar o padrão.
- `ReconhecedorDeComando` é hoje "a superfície do Vosk, e só ela" por design explícito
  (KDoc da classe, `add-audio-single-grammar-slice`): o resto do pipeline
  (`SeletorDeEscuta`, `InterpretadorDeFala`, `ResolvedorDeIntencao`, `PublicadorDeVoz`)
  não conhece Vosk, só consome `PickingEvent`/texto. Essa fronteira é o que torna a troca
  de motor uma mudança localizada em vez de uma reescrita do pipeline inteiro.
- `InterpretadorDeFala.interpretar` já faz comparação **exata** de string contra as
  constantes de `VocabularioDeVoz` (`normalizado == VocabularioDeVoz.PROXIMO`, etc.), após
  só `trim().lowercase()`. Isso já era, na prática, uma validação pós-reconhecimento — o
  Vosk é que também restringia as hipóteses do decodificador (gramática JSON), então a
  rejeição de fala fora do contrato tinha duas camadas redundantes. Com Whisper, só a
  camada que já existe em `InterpretadorDeFala` continua existindo.
- O histórico de bancada de `add-voice-recognition-reliability` (17–18/08/2026, ainda não
  commitado) documenta duas rodadas de calibração de gramática em direções opostas, nenhuma
  estável: ampliar o vocabulário do check digit para aceitar extenso piorou o dígito a
  dígito; restringir só a dígito a dígito trouxe de volta reconhecimento parcial (só um
  algarismo por elocução). Esse histórico é o dado concreto que motiva não insistir em mais
  ajuste de gramática sobre o mesmo modelo acústico.
- `AudioHfpOculos` foi implementado, verificado e **revertido no mesmo dia** (`0c5d39b` →
  `05fcb2f`) simplesmente trocando uma linha em `AppContainer`, porque `FonteAudio` é uma
  interface e as duas implementações convivem no código. Esse é o padrão de reversibilidade
  que este design segue para o motor de ASR (Decisão 1).

## Goals / Non-Goals

**Goals:**
- Trocar o decodificador de comando de voz de Vosk para sherpa-onnx (Whisper multilíngue +
  Silero VAD embutido), mantendo o contrato observável do pipeline: os mesmos
  `PickingEvent`s, a mesma fronteira de responsabilidade entre `SeletorDeEscuta` (o que
  pode ser dito), `InterpretadorDeFala` (o que significa) e `ReconhecedorDeComando` (a
  superfície do motor).
- Deixar a troca de motor reversível em uma linha, mesmo padrão já usado para
  `FonteAudio`/`AudioHfpOculos`, dado que não há ainda nenhuma medição de bancada com o
  motor novo.
- Antecipar o Silero VAD do roadmap ("Marco 2") como consequência da troca, não como
  trabalho extra.

**Non-Goals:**
- Reranking contra a expectativa do mock (doc §5, última etapa do diagrama) — continua
  fora do escopo, como já era antes desta mudança.
- Mudar o texto de `VocabularioDeVoz`/`SeletorDeEscuta` (quais palavras cada estado
  aceita) — isso é conteúdo do change `add-voice-recognition-reliability`, independente
  deste. Este change troca o motor que produz o texto a ser validado, não o vocabulário
  em si.
- Calibrar os parâmetros do VAD/Whisper em bancada — os valores desta proposta são ponto
  de partida documentado, não resultado medido; bancada é tarefa de `tasks.md`, como toda
  calibração deste projeto.
- Suporte a mais de um idioma simultâneo — o modelo Whisper é multilíngue por
  característica do modelo, não porque o app precisa detectar idioma; a app continua
  assumindo pt-BR.

## Decisions

### Decisão 1: Motor de reconhecimento vira uma interface trocável, Vosk não é apagado

Alternativa considerada e rejeitada: remover o código do Vosk (dependência, modelo em
`assets/`, lógica de `ReconhecedorDeComando`) por completo nesta mudança. Rejeitada porque
não há ainda nenhuma medição de bancada do motor novo, e o projeto já tem um precedente
direto do custo de not ter um caminho de volta: `AudioHfpOculos` foi revertido no mesmo dia
em que foi implementado, só porque `FonteAudio` já era uma interface — a reversão foi trocar
uma linha em `AppContainer`, não desfazer um commit.

`ReconhecedorDeComando` passa a depender de uma nova interface `MotorDeAsr` (nome sujeito a
ajuste na implementação), com dois métodos essenciais — carregar o(s) modelo(s) e, dado um
fluxo de amostras, produzir texto reconhecido — e duas implementações:
- `MotorVosk`: o que hoje está dentro de `ReconhecedorDeComando` (carga do `Model`,
  `Recognizer` com gramática, endpoint embutido), extraído sem mudança de comportamento.
- `MotorSherpaOnnx`: Silero VAD + Whisper, implementação nova desta mudança.

`AppContainer` escolhe qual motor instanciar em uma linha, mesmo padrão de `fonteAudio`. O
resto de `ReconhecedorDeComando` (thread dedicada, observação de estado, publicação,
logging) não muda, porque já não sabia que o motor era Vosk especificamente — só chamava a
API do Vosk diretamente. Esta mudança formaliza essa fronteira em vez de inaugurá-la.

### Decisão 2: Validação de gramática por estado continua em `InterpretadorDeFala`, sem componente novo

`InterpretadorDeFala.interpretar` já compara o texto normalizado contra as constantes de
`VocabularioDeVoz` com igualdade exata — já é, na prática, uma validação pós-reconhecimento.
A única camada que deixa de existir é a restrição do **decodificador** (a gramática JSON que
o Vosk recebia no construtor do `Recognizer`); a camada que decide se o texto reconhecido
"conta" continua sendo exatamente a mesma função pura de hoje, sem mudança de assinatura ou
de comportamento.

Alternativa considerada: mover a validação para dentro do motor (`MotorSherpaOnnx` já
filtra hipóteses contra o vocabulário do estado antes de devolver texto). Rejeitada — faria
o motor precisar conhecer `SeletorDeEscuta`/`PickingState`, quebrando a mesma fronteira que
a Decisão 1 acabou de formalizar (`ReconhecedorDeComando`/motor não sabem de domínio); e
duplicaria uma comparação que `InterpretadorDeFala` já faz corretamente.

### Decisão 3: Normalização de pontuação entra na fronteira do motor, não em `InterpretadorDeFala`

Decodificadores de vocabulário aberto como o Whisper tendem a devolver texto pontuado
("Próximo.", "quarenta e sete,") — o Vosk de gramática fechada nunca produzia pontuação,
porque as únicas hipóteses possíveis eram as palavras da gramática. Sem tratar isso, toda
igualdade exata em `InterpretadorDeFala` passaria a falhar por causa de um ponto final.

A remoção de pontuação (e qualquer outra normalização específica do motor, como
capitalização de início de frase) é responsabilidade de `MotorSherpaOnnx`/`ReconhecedorDeComando`
antes de entregar o texto a `InterpretadorDeFala` — a mesma fronteira que já existe hoje
para outras peculiaridades de decodificador (o JSON do Vosk, por exemplo, nunca vaza para
fora de `ReconhecedorDeComando`). `InterpretadorDeFala` continua recebendo uma `String` já
normalizada de espaços e agora também de pontuação, sem precisar saber que o motor mudou.

Alternativa considerada: strip de pontuação dentro de `InterpretadorDeFala.interpretar`.
Rejeitada — pontuação é uma característica do decodificador (o Vosk nunca a produziu), não
do domínio; colocar esse tratamento na função pura acoplaria seu comportamento a um detalhe
de motor específico.

### Decisão 4: Modelo Whisper multilíngue tiny quantizado (int8) como ponto de partida

Entre os tamanhos publicados de Whisper (tiny ~39M parâmetros, base ~74M, ambos
multilíngues), a escolha inicial é **tiny int8**, pelo mesmo motivo que levou o modelo Vosk
a ser o menor pt disponível (design.md de `add-audio-single-grammar-slice`, Decisão 5): o
APK de debug já é grande (150 MB, por causa do ML Kit bundled do pipeline de visão) e o
vocabulário reconhecido por comando é curto (uma palavra, dois dígitos) — não há indício de
que o modelo maior seja necessário para esse tipo de elocução curta, e cada MB a mais custa
tempo de `installDebug` na bancada que falta até 18/09.

Isso é uma hipótese de partida, não uma medição — `tasks.md` inclui a tarefa de bancada que
compara tiny contra base na mesma bateria de comandos que `add-audio-single-grammar-slice`
já usou para o Vosk (9 comandos, primeira tentativa, dBFS de pico), e o default só muda
para base se tiny não bater a taxa de acerto que o Vosk já tinha.

Alternativa considerada: baixar o modelo em tempo de execução em vez de vendorizar em
`assets/`. Rejeitada pelo mesmo motivo já registrado para o Vosk — sem dependência de rede
nesta camada, e um passo de setup que pode falhar na manhã do evento não é aceitável.

### Decisão 5: Verificar a API do sherpa-onnx antes de escrever qualquer código de integração

A pesquisa que embasa esta proposta encontrou referências de dependência Gradle
(`com.github.k2-fsa:sherpa-onnx-android`) e de superfície Kotlin em buscas na web, não em
documentação oficial lida diretamente — o mesmo tipo de fonte que, para o Vosk, já se
mostrou não confiável nesta base de código (design.md de `add-audio-single-grammar-slice`
registra duas suposições erradas sobre o artefato Maven do Vosk, corrigidas só depois de
`javap` sobre o `.aar` real). Antes de qualquer código de `MotorSherpaOnnx`, `tasks.md`
inclui uma tarefa de verificação da API real do sherpa-onnx (artefato publicado vs. build a
partir do código-fonte, assinaturas de VAD/reconhecimento, formato de amostra esperado —
provavelmente `FloatArray` normalizado, dado que é ONNX Runtime e não Kaldi, mas isso
também precisa ser confirmado, não assumido, pela mesma razão que a escala `±32767` do Vosk
custou uma rodada inteira de bancada quando foi assumida errada).

### Decisão 6: Parâmetros de tempo do VAD partem dos valores já calibrados para o Vosk, sujeitos a nova bancada

`PerfilEndpoint`/`AjustesAsr.silencioFinalMs` continuam existindo como a configuração de
quanto silêncio fecha uma elocução — o mesmo conceito se aplica ao VAD, trocando "quando o
`Recognizer` do Vosk decide que a elocução acabou" por "quando o Silero VAD decide". Os
valores atuais (280 ms para comando curto, 700 ms para dígitos) são o ponto de partida, não
a calibração final: foram medidos contra o comportamento específico do endpointer do Vosk
(design.md de `add-audio-single-grammar-slice`, "Terceira rodada"), e nada garante que o
VAD feche a elocução no mesmo instante para o mesmo áudio. `AjustesAsr` ganha os campos
necessários para calibrar o VAD sem recompilar, seguindo o mecanismo já existente
(`ajustes-asr.properties`, `adb push` + `force-stop`).

## Risks / Trade-offs

- **[Risco]** Sem medição prévia, não há garantia de que Whisper tiny + VAD supere o Vosk
  em accuracy no ambiente ruidoso do galpão — a motivação desta troca é a hipótese de que o
  teto está no modelo acústico do Vosk, mas isso só se confirma em bancada. →
  **Mitigação**: Decisão 1 (motor reversível em uma linha) garante que, se a bancada mostrar
  piora, a volta para Vosk custa uma linha em `AppContainer`, não um revert de commit.
- **[Risco]** Whisper offline (VAD-then-ASR) tem latência de fim-de-fala até evento maior e
  menos previsível que o streaming do Vosk, que já decodificava enquanto o operador falava.
  → **Mitigação**: modelo tiny quantizado é escolhido justamente para minimizar tempo de
  inferência; a tarefa de bancada mede a latência real (ver spec `audio-source` desta
  mudança, cenário "Publicação não é instantânea ao fim da fala") antes de qualquer
  julgamento sobre usabilidade.
- **[Risco]** Dependência nova (sherpa-onnx) sem verificação prévia da API real, mesma classe
  de risco que já aconteceu com o Vosk (duas suposições erradas sobre o artefato Maven). →
  **Mitigação**: Decisão 5, tarefa de verificação dedicada antes de qualquer código de
  integração, mesma prática (`javap`/inspeção do artefato real) já validada neste projeto.
- **[Risco]** APK de debug cresce mais ainda (já em 150 MB) com os modelos Whisper + Silero
  VAD somados aos do Vosk, que continuam no repositório pela Decisão 1. → **Mitigação**:
  modelo tiny quantizado é a menor opção plausível; se o tamanho combinado se mostrar
  proibitivo na bancada, remover o Vosk deixa de ser opcional e vira uma tarefa de limpeza
  subsequente — não bloqueia esta mudança, mas fica registrado como consequência possível.
- **[Risco]** Toda a calibração de bancada registrada em [[reference-voz-bancada]] (limiar
  de -27 dBFS, protocolo de teste) foi medida contra o Vosk e pode não valer para o motor
  novo. → **Mitigação**: `tasks.md` trata a bancada deste change como uma calibração do
  zero, não como reaproveitamento dos números antigos — mesma disciplina que o projeto já
  aplicou toda vez que um componente de áudio mudou (ex.: `AudioHfpOculos`, que pedia
  remedição do mesmo limiar).

## Open Questions

- Artefato do sherpa-onnx: existe um AAR publicado em um repositório Maven confiável para
  este projeto usar diretamente, ou é preciso compilar a partir do código-fonte (NDK)? Isso
  muda o esforço de setup e o tempo de build, mas não muda a spec, a decisão de motor
  trocável, nem a quebra de tarefas — fica para a tarefa de verificação (Decisão 5)
  responder antes da tarefa de integração começar.
