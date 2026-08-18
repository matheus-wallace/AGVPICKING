## Why

Trocar o modelo pt-BR do Vosk não resolveu a imprecisão de reconhecimento relatada por
Matheus. O histórico de bancada já registrado em `add-voice-recognition-reliability`
mostra que o problema não é só de gramática: ampliar o vocabulário de
`AguardandoCheckDigit` para aceitar extenso *piorou* a leitura dígito a dígito que já
funcionava ("quatro" virando "quatrocentos" no meio da fala), e restringir para só
dígito a dígito trouxe de volta o sintoma oposto (só um algarismo reconhecido por
elocução). Duas rodadas de bancada em direções opostas, nenhuma resolvendo o problema de
forma estável, é evidência de que o teto está no modelo acústico pequeno do Vosk, não em
como a gramática está desenhada em cima dele — trocar de motor é a via que resta.

## What Changes

- **BREAKING: motor de ASR trocado de Vosk para sherpa-onnx.** `ReconhecedorDeComando`
  deixa de carregar um `Model`/`Recognizer` do Vosk e passa a rodar um pipeline
  sherpa-onnx: Silero VAD (embutido no toolkit) detecta início/fim de fala, e um modelo
  Whisper multilíngue (quantizado, tamanho a decidir em design.md) exportado para ONNX
  decodifica o trecho já cortado. Pesquisa feita antes desta proposta: o model zoo do
  sherpa-onnx **não tem nenhum modelo de streaming (online) nativo para português** — a
  única via real de pt-BR nesse toolkit é Whisper offline, então o pipeline muda de
  streaming (Vosk decodificando amostra a amostra, endpoint pelo próprio `Recognizer`)
  para VAD-then-ASR (corta o trecho de fala, decodifica de uma vez).
- **Gramática fechada por estado deixa de restringir o decodificador e passa a validar o
  texto reconhecido.** O Vosk aceitava uma gramática JSON que restringia as hipóteses do
  próprio decodificador (`ConfiguracaoDeEscuta.gramatica`, construída em
  `SeletorDeEscuta`); Whisper não tem esse mecanismo — é vocabulário aberto. A palavra
  esperada por estado continua existindo (`VocabularioDeVoz`/`SeletorDeEscuta` não mudam
  de forma), mas o filtro passa a rodar depois do reconhecimento, dentro do fluxo que já
  existe em `InterpretadorDeFala`/`PublicadorDeVoz` (que já descartam texto fora do
  contrato, hoje por não bater com a gramática do `Recognizer`; passam a descartar por não
  bater com o conjunto de palavras esperadas do estado).
- **VAD Silero substitui o endpointer por silêncio do Vosk e antecipa o item "Marco 2:
  Silero VAD" do roadmap** (estava listado como não iniciado) — não é uma etapa separada,
  sai junto da troca de motor. Os perfis de tempo hoje calibrados em
  `PerfilEndpoint`/`AjustesAsr.silencioFinalMs` continuam existindo como parâmetro do VAD
  (tempo de silêncio que fecha o corte), não é um mecanismo novo de configuração.
- **Modelo vendorizado troca de forma**: os 51 MB do modelo Vosk pt-BR em `assets/`
  saem; entram os arquivos do modelo Whisper ONNX + Silero VAD escolhidos em design.md.
  Tamanho final e impacto no APK de debug (hoje já grande por causa do ML Kit bundled) é
  uma decisão de design, não definida aqui.
- **Sem mudança de evento de domínio.** Os mesmos `PickingEvent`s que o Vosk produzia
  continuam sendo produzidos pelo novo motor — troca de decodificador, não de contrato
  observável do fluxo de picking.
- Este change é independente do trabalho ainda não commitado de
  `add-voice-recognition-reliability` (extenso do check digit, fechamento de gramática do
  `TratandoExcecao`, `NoiseSuppressor`/`AutomaticGainControl`) — não reconcilia nem
  modifica esse outro change; a superfície de troca (`ReconhecedorDeComando`) é a mesma,
  mas o conteúdo da gramática por estado que ele consome vem de onde aquele change deixar.

## Capabilities

### New Capabilities
(nenhuma)

### Modified Capabilities
- `audio-source`: a fonte de amostras continua substituível e o reconhecimento continua
  isolado da coroutine do ator e da UI, mas o mecanismo de reconhecimento deixa de ser
  streaming com endpoint embutido do Vosk e passa a ser VAD (Silero) cortando o trecho
  antes de um decodificador offline (Whisper via sherpa-onnx) rodar sobre ele — muda a
  latência observável entre o fim da fala e o evento publicado, que deixa de ser "dentro
  da janela de silêncio do perfil de comando curto" para "tempo de VAD + tempo de
  inferência do Whisper sobre o trecho".
- `state-driven-voice-flow`: os requisitos que descrevem gramática como restrição do
  decodificador (ex.: "essas palavras também não fazem parte da gramática do estado, de
  modo que o decodificador não as tem como hipótese", em `add-voice-recognition-reliability`)
  deixam de valer como estavam — o decodificador agora tem vocabulário aberto, e a
  restrição por palavra esperada do estado passa a acontecer depois do reconhecimento.
  Comportamento observável (fala fora do vocabulário do estado não produz evento) não
  muda; o mecanismo que garante isso, sim.

## Impact

- Código alterado: `audio/ReconhecedorDeComando.kt` (reescrita da superfície de
  reconhecimento — carga de modelo, thread dedicada, ciclo de escuta), `audio/AjustesAsr.kt`
  (parâmetros de calibração que hoje descrevem o endpointer do Vosk podem precisar de
  equivalentes para o VAD), `ajustes-asr.properties.exemplo`.
- Código novo: integração sherpa-onnx (dependência Gradle/AAR), carregamento dos modelos
  Whisper ONNX + Silero VAD, adaptação do resultado reconhecido (texto livre) para o
  contrato que `InterpretadorDeFala`/`PublicadorDeVoz` já esperam.
- Dependência removida: `com.alphacephei:vosk-android` e o modelo Vosk pt-BR vendorizado
  em `assets/`.
- Dependência nova: sherpa-onnx (Apache-2.0) + modelo(s) ONNX vendorizados em `assets/`
  (tamanho a decidir em design.md).
- Sem mudança nos pacotes `domain/`, `data/`, `vision/`, nem nos `PickingEvent`s.
- Bancada: toda a calibração registrada em [[reference-voz-bancada]] (limiar de -27 dBFS,
  protocolo de teste com voz humana direta no aparelho) precisa ser refeita para o novo
  motor — os números medidos para o Vosk não têm por que valer para outro decodificador.
