## Context

`MotorDeAsr` (`audio/MotorDeAsr.kt`) já existe como fronteira de troca de motor desde
`add-sherpa-onnx-asr-engine`: `carregar(): Boolean` uma vez na subida do app,
`abrirSessao(configuracao, sampleRate): SessaoDeAsr?` por elocução, e `SessaoDeAsr.aceitar`
devolvendo `ResultadoDeAsr` — sempre **texto**, nunca intenção estruturada. `MotorVosk`
(ativo) e `MotorSherpaOnnx` (avaliado e revertido) implementam essa interface hoje.
`InterpretadorDeFala` compara o texto normalizado contra `VocabularioDeVoz` (palavras
esperadas pelo `PickingState` atual, resolvidas por `SeletorDeEscuta`), e é essa camada —
não o motor — que decide se o texto reconhecido "conta" como comando.

Picovoice Rhino não decodifica texto: é fala-para-intenção. Um contexto `.rhn`, compilado
previamente no Picovoice Console, define expressões (`expressions`), intenções (`intents`)
e argumentos de intenção (`slots`); em runtime, o SDK Android devolve `isUnderstood` +
`intent` + mapa de `slots` já preenchidos, ou `isUnderstood = false` para qualquer fala
fora do contexto. Ver proposal.md para o histórico de por que essa é a alternativa de motor
ainda não testada neste projeto.

## Goals / Non-Goals

**Goals:**
- Decidir, antes de escrever qualquer código de integração, como a saída de intenção do
  Rhino se encaixa (ou não) no contrato de `MotorDeAsr` já existente.
- Decidir a granularidade de contexto (`.rhn`) por estado, já que troca de contexto no
  Android SDK significa destruir/recriar a instância do `Rhino`, não trocar um parâmetro.
- Deixar rastreável o que precisa de confirmação por bancada antes de qualquer decisão de
  troca de motor completa — como o suporte real a pt-BR, não apenas assumido pela
  documentação pública.

**Non-Goals:**
- Não é objetivo deste change trocar `MotorVosk` como motor ativo — assim como
  `MotorSherpaOnnx`, `MotorPicovoiceRhino` fica disponível no binário, trocável por uma
  linha em `AppContainer.kt`, sem afetar produção enquanto não houver bancada favorável.
- Não é objetivo implementar Porcupine (wake word) — Rhino já tem detecção de fim de
  elocução própria, mesma forma de consumo de `FonteAudio` que os outros motores já usam.
- Não é objetivo migrar `MotorVosk`/`MotorSherpaOnnx` para o formato de intenção — eles
  continuam devolvendo texto normalmente.
- Não é objetivo desta rodada resolver o check digit por slot numérico enumerado no
  Console — fica registrado como decisão de implementação a validar na tarefa de bancada
  (ver Risks/Trade-offs), não decidido aqui.

## Decisions

### Decisão 1: Saída do motor vira texto sintetizado a partir do intent/slot, não um segundo caminho de consumo

`SessaoDeAsr.aceitar` de `MotorPicovoiceRhino` sintetiza um texto a partir do
`intent`/`slots` reconhecidos (ex.: intent `comando_parar` → texto `"parar"`; intent
`check_digit` com slot `numero=47` → texto `"47"`) e devolve como `ResultadoDeAsr.Fechada`,
igual às outras duas implementações. `InterpretadorDeFala`/`VocabularioDeVoz`/
`ResolvedorDeIntencao` continuam intocados.

**Alternativa considerada e descartada**: abrir um segundo caminho de consumo em
`ReconhecedorDeComando` que aceitasse intenção estruturada direto do motor, pulando
`InterpretadorDeFala`. Descartada porque quebra a garantia central que `MotorDeAsr.kt`
documenta ("nada de domínio atravessa esta fronteira" — o motor não conhece
`PickingState`/`PickingEvent`) e duplicaria a lógica de decisão de comando em dois lugares
(o contexto Rhino de um lado, `VocabularioDeVoz` do outro) — risco de os dois divergirem
silenciosamente ao longo do tempo. O custo da rota escolhida é perder a granularidade nativa
do slot (ex.: Rhino já entrega `numero=47` tipado; sintetizar texto e reanalisar em
`InterpretadorDeFala` joga essa estrutura fora só para reconstruí-la) — aceito porque o
projeto já pagou, com `AudioHfpOculos`/`MotorSherpaOnnx`, para aprender que manter a
fronteira estreita vale mais que aproveitar cada bit de informação do motor
(add-sherpa-onnx-asr-engine — Decisão 1).

### Decisão 2: Um contexto único cobrindo todo o vocabulário, filtro por estado continua depois

Um único arquivo `.rhn`, compilado no Console com todas as expressões de todos os estados
(comandos transversais + check digit + quantidade + confirmações), carregado uma vez em
`carregar()`. A restrição de "só esse comando vale neste estado" continua acontecendo depois
do reconhecimento, em `InterpretadorDeFala`, exatamente como já acontece hoje para o
sherpa-onnx (vocabulário aberto, filtro pós-reconhecimento).

**Alternativa considerada e descartada, por ora**: N contextos pré-compilados, um por
agrupamento de estado, trocados por destruir/recriar a instância do `Rhino` a cada
transição de `PickingState` que muda de agrupamento. Tecnicamente mais fiel à proposta de
valor do Rhino (contexto pequeno e específico tende a ser mais preciso, segundo a própria
documentação do produto), mas: (a) introduz custo de latência não medido a cada troca de
estado — inicializar o Rhino envolve carregar o `.rhn` e o modelo de idioma, e nenhuma
bancada mediu esse tempo neste projeto; (b) cada `.rhn` extra é compilado manualmente no
Console, não gerado em código — N contextos viram N artefatos versionados e N pontos de
manutenção manual toda vez que o vocabulário de um estado muda; (c) contradiz o contrato de
`MotorDeAsr.carregar()`, pensado para "os segundos de carga aconteçam durante a subida do
app e não na frente do operador" (`MotorDeAsr.kt`, linha 34-39) — recriar a instância em
troca de estado reintroduz exatamente esse custo no meio do fluxo. Fica registrado como
alternativa a revisitar se a bancada do contexto único mostrar precisão insuficiente
especificamente nos estados de vocabulário mais amplo (check digit extenso).

### Decisão 3: `AccessKey` do Picovoice Console entra em `local.properties`, exposto via `BuildConfig`

Este é o primeiro segredo de terceiro que o projeto precisa gerenciar — não há precedente
anterior (Vosk e sherpa-onnx são modelos vendorizados sem chave de conta). `local.properties`
já é gitignorado neste módulo (`AgvPickVoice/.gitignore`, linhas 4 e 11) e já existe como
arquivo no working tree, usado hoje só para `sdk.dir`. Uma chave nova
(`picovoiceAccessKey=...`) lida em `build.gradle.kts` e exposta como campo de `BuildConfig`
segue o padrão Android mais comum para esse tipo de segredo, sem inventar mecanismo novo.
`ajustes-asr.properties.exemplo` não é o lugar certo — aquele arquivo é para parâmetros de
calibração de bancada trocáveis via `adb push` sem recompilar; a `AccessKey` é uma
credencial de build, não um parâmetro de tempo de execução.

### Decisão 4: Confirmar pt-BR real e viabilidade do trial antes de qualquer código de integração

Mesma disciplina que `add-sherpa-onnx-asr-engine` aplicou (Decisão 5 daquele change:
"verificar a API antes de escrever qualquer código"): antes de implementar
`MotorPicovoiceRhino`, gerar no Picovoice Console um contexto de teste mínimo cobrindo os
comandos transversais já existentes em `VocabularioDeVoz` (`parar`, `repetir`, `próximo`) e
confirmar, com voz humana real, que o modelo de idioma reconhece pt-BR — não apenas
"Portuguese" genérico, que é tudo que a documentação pública confirma hoje. Se essa bancada
mínima falhar, o resto deste change não deve prosseguir, e a alternativa restante volta a
ser bancada de calibração fina do Vosk (`add-voice-recognition-reliability`, grupo 6, ainda
não verificado com voz real).

## Verificação da API do Rhino

Mesma disciplina que `add-sherpa-onnx-asr-engine` aplicou (Decisão 5 daquele change): a API foi
conferida **no artefato**, não na documentação do produto, antes de escrever código. O que foi
inspecionado, em 19/08/2026: `ai.picovoice:rhino-android:4.0.2` — a versão que o
`maven-metadata.xml` do Maven Central declara como `<release>` —, o `.aar` (`unzip -l` + `javap`
sobre o `classes.jar`) e o `-sources.jar` do mesmo GAV.

**(a) `.pv` e `.rhn` são artefatos diferentes, e o `.aar` só traz o de inglês.** O `.aar` embute
`res/raw/rhino_params.pv` (2.108.464 B), que é o **modelo de idioma inglês**, e é o que o
`Rhino.Builder` usa quando `setModelPath` não é chamado (`Rhino.java`, `build()`:
`if (modelPath == null) modelPath = DEFAULT_MODEL_PATH`). Sem chamar `setModelPath`, o motor
rodaria em inglês **sem reclamar de nada** — não há erro, há reconhecimento ruim. Os modelos dos
outros idiomas ficam em `lib/common/` do repositório `Picovoice/rhino` (Apache-2.0, confirmado em
`GET /repos/Picovoice/rhino/license`); `rhino_params_pt.pv` está vendorizado em
`app/src/main/assets/modelo-picovoice-rhino/` com PROVENIENCIA.md. O `.rhn` é outra coisa: o
contexto deste projeto, compilado à mão no Console, e continua sem existir (grupo 3).

**(b) "Portuguese" é `pt` no SDK, e isso ainda não é pt-BR.** `Rhino.VALID_LANGUAGES` lista
`de, en, es, fr, it, ja, ko, pt` — confirma português como idioma suportado, e só isso. Nada no
artefato distingue a variante. A Decisão 4 continua de pé; o que mudou foi só *quando* ela é
respondida (durante a implementação, não antes).

**(c) 16 kHz e quadro de tamanho exato.** `Rhino.getSampleRate()` devolve 16.000 e
`process(short[])` recusa qualquer array que não tenha exatamente `getFrameLength()` amostras —
com exceção (`RhinoInvalidArgumentException`), não com degradação silenciosa. A `FonteAudio`
entrega 8 kHz em janelas de 512 `float`, então o motor reamostra (`ReamostradorLinear`, já
existente) e acumula até fechar um quadro. Escala de int16, como o Vosk e ao contrário do
sherpa-onnx.

**(d) Falha degrada em vez de matar o processo — o oposto do sherpa-onnx.** `setModelPath` e
`setContextPath` aceitam caminho **relativo a `assets/`**: o `build()` testa o sistema de arquivos
e, se não achar, abre o asset e o copia para `getFilesDir()` (`extractResource`). Asset ausente
vira `IOException` → `RhinoIOException` → `RhinoException` → `Exception` comum, que um
`runCatching` pega. Por isso este motor **não** precisa da conferência defensiva de assets que o
`MotorSherpaOnnx` faz para escapar do `_Exit` do C++ — e por isso o contexto `.rhn` faltando hoje
resulta em `carregar()` devolvendo `false`, e não em crash.

**(e) `reset()` depois de cada inferência não é opcional.** O `RhinoManager` do próprio SDK não o
chama, mas só porque **para de gravar** depois do primeiro resultado (`RhinoManager.java`,
`onFrame`: `callback.invoke(inference); stop();`) — comportamento inútil para um fluxo de picking
contínuo. O demo oficial em C, que escuta em loop, chama `pv_rhino_reset` logo depois de ler a
intenção (`demo/c/rhino_demo_mic.c`). Sem isso o motor ficaria preso na inferência já lida e nunca
fecharia a elocução seguinte.

**(f) `endpointDurationSec` é `[0.5, 5.0]` s e é parâmetro de construção.** Fora da faixa, o
`Builder` lança. Os 280 ms do `PerfilEndpoint.COMANDO_CURTO` **não cabem** — ver o risco
correspondente abaixo.

## Risks / Trade-offs

- **[Risco] Suporte a pt-BR não confirmado** → Mitigação: Decisão 4 — bancada mínima de
  contexto de teste é a primeira tarefa, antes de qualquer outro código.
- **[Risco] Check digit fica mal representado como slot enumerado** — o check digit aceita
  tanto dígito a dígito quanto extenso (`VocabularioDeVoz.checkDigitExtenso`,
  `add-voice-recognition-reliability` — Decisão 1), e um slot Rhino é tipicamente uma lista
  de valores enumerados definida em tempo de autoria do contexto. Um slot numérico 0-99
  cobre o caso dígito-a-dígito fundido (`"47"`) sem problema, mas as variações de fala
  extenso ("quarenta e sete", "quatro sete") precisam entrar como expressões alternativas
  do mesmo slot no Console — não é gerado em código, é trabalho manual de autoria que cresce
  com o vocabulário → Mitigação: nenhuma, por ora — registrado como custo real da rota
  escolhida, a medir na tarefa de bancada antes de decidir se compensa frente ao Vosk.
- **[Risco] Contexto único reduz a vantagem de precisão de um contexto pequeno e
  específico** — a própria decisão 2 troca especificidade por simplicidade de ciclo de vida;
  se a bancada mostrar confusão entre comandos de estados diferentes (ex.: "confirmar"
  reconhecido fora do estado que o espera), a Alternativa de N contextos volta à mesa →
  Mitigação: nenhuma preventiva; é o trade-off aceito na Decisão 2, a re-abrir só se medido.
- **[Risco] Limites do trial não documentados publicamente com precisão (nº de dispositivos,
  expiração, cota de reconhecimentos)** → Mitigação: conferir o painel do Picovoice Console
  assim que a `AccessKey` for gerada, antes de assumir uso irrestrito durante a avaliação.
- **[Trade-off aceito] Pegada de armazenamento muito menor que as duas alternativas já
  testadas** (< 2,5 MB contra 51 MB do Vosk e 146-292 MB do sherpa-onnx) — motivação
  positiva registrada em proposal.md - Impact, não um risco.
- **[Risco, descoberto na implementação] O perfil de endpoint por estado se perde neste motor.**
  `endpointDurationSec` é parâmetro de construção do `Rhino`, não ajuste de runtime (verificação
  (f) acima), então honrar `PerfilEndpoint` por estado exigiria recriar a instância a cada
  transição — exatamente o custo que a Decisão 2 recusou. Pior: a faixa aceita é `[0.5, 5.0]` s e
  os **280 ms do `COMANDO_CURTO` não cabem nela**, ou seja, nem recriando a instância seria
  possível reproduzir o perfil curto que o Vosk usa hoje. O `MotorPicovoiceRhino` usa o perfil
  mais longo em uso (`DIGITOS`, 700 ms) com `coerceIn` na faixa do SDK, porque errar para o lado
  longo faz o operador esperar e errar para o lado curto come dígito (doc §5.1) → Mitigação:
  nenhuma possível no SDK. É medição de bancada (grupo 6): se o Rhino ficar perceptivelmente mais
  lento que o Vosk em comandos de uma palavra, esta é a causa, e ela não tem ajuste.
- **[Trade-off aceito, descoberto na implementação] 2,1 MB de modelo de inglês morto no APK.** O
  `.aar` embute `res/raw/rhino_params.pv` (inglês) e não há como excluí-lo sem reempacotar o
  artefato; o pt-BR entra como asset separado, do mesmo tamanho. O total do Rhino no APK fica em
  ~5,1 MB (dois modelos + `libpv_rhino.so` das quatro ABIs) em vez dos < 2,5 MB estimados em
  proposal.md — ainda uma ordem de grandeza abaixo dos 51 MB do Vosk, então o argumento de
  pegada continua valendo, só com o número corrigido.
- **[Bloqueio aberto] O contexto `.rhn` não existe, e nenhum código pode produzi-lo.** É artefato
  binário compilado no Picovoice Console, específico de idioma e plataforma, dependente da conta
  do trial (grupo 3 das tasks). Enquanto faltar, `MotorPicovoiceRhino.carregar()` devolve `false`
  e trocar a linha do `AppContainer` desligaria o reconhecimento em vez de trocá-lo — por isso o
  motor ativo continua sendo o `MotorVosk`. O diretório `assets/contexto-picovoice/` e o
  PROVENIENCIA.md dele já existem, com a tabela de nomes de intenção que o contexto tem de
  declarar; **nenhum `.rhn` placeholder foi criado de propósito**, porque um arquivo inventado
  trocaria uma falha legível ("asset ausente") por uma vinda de dentro do runtime nativo.

## Uso futuro compartilhado com o app-wms (19/08/2026)

O YAML fonte do contexto (`assets/contexto-picovoice/picovoice-pt.yaml`) passou a cobrir também o
vocabulário real do `app-wms` (app de produção do trabalho do Matheus, repositório separado —
[[reference-app-wms-voice]]), como ponto de partida pra uma avaliação futura de Rhino lá, ainda
não iniciada. Achados relevantes pra este change, confirmados no código de lá antes de estender o
YAML: o `app-wms` hoje reconhece frase inteira e descarta palavras de enchimento
(`confirmar`/`separa`/`quantidade`/`caixa`/...) antes de interpretar o número — isso não é um
requisito de produto, é contorno de uma limitação do reconhecedor de vocabulário aberto do Google
(`@react-native-voice/voice`) pra números pequenos falados isolados. A tela `CountBack` já
pergunta unidades e caixas em momentos separados (`ask_unit_count` → `ask_box_count`), então se o
Rhino reconhecer número isolado de forma confiável, a mesma modelagem de utterance única e fechada
que este change já usa (`numero_digitos`/`numero_extenso`) serve sem precisar da etapa de
enchimento — "23" dito no momento certo já resolve "23 caixas" sem o motor precisar entender
"caixas". Isso simplifica a integração futura lá, mas ainda não é uma decisão tomada — só uma
leitura mais otimista do problema do que a versão anterior deste documento registrava.

**Pendência aberta, a ajustar depois** (não bloqueia este change, registrado aqui pra não se
perder): `VocabularioDeVoz.VALOR_NUMERO`/`VALOR_DIGITO_EM_QUANTIDADE` (Kotlin, AGV) não aceitam
`"uma"`/`"duas"` — formas femininas que o contexto Rhino já reconhece (adicionadas aos slots
`unidade`/`digito` por causa do `app-wms`), mas que `InterpretadorDeFala` descarta (`null`) porque
o vocabulário do AGV nunca precisou de concordância de gênero. Enquanto não for ajustado, dizer
"duas" pro AGV com o motor Rhino é reconhecido e sem efeito — mesma categoria inerte que já vale
pra `"meia"` em quantidade.

### Casa dos milhares (19/08/2026)

Pedido do Matheus após reportar necessidade de aceitar quantidades grandes (`1200`, `5300`,
`100005`). `numero_extenso` ganhou ~50 expressões novas cobrindo 1.000-999.999, com um
multiplicador de token único (um número/dezena/centena antes de "mil") mais um resto de 0-999
depois — ver comentário "Casa dos milhares" no YAML pra forma exata. **Não cobre multiplicador
composto** ("vinte e cinco mil", "cento e vinte mil") — gap conhecido, registrado no próprio
arquivo, não fechado nesta rodada.

Mecanismo novo, documentado no YAML: como `textoDosSlots` só junta VALORES de slot (nunca as
palavras literais da expressão — é assim que o conectivo "e" já é descartado hoje), a palavra
"mil" precisou virar um slot de valor único (`$mil:m2`) só pra sobreviver na reconstrução do
texto. Sem isso, "cem mil e cinco" seria sintetizado como "cem cinco", perdendo a multiplicação
inteira.

**Pendência maior que a de "uma"/"duas" acima, registrada e não aplicada**:
`VocabularioDeVoz.numero()` (Kotlin) hoje só soma magnitudes estritamente decrescentes — não tem
noção nenhuma de multiplicação. Fazer o AGV interpretar "cem mil e cinco" de verdade não é
acrescentar `"mil"` numa tabela (como seria pra "uma"/"duas"): é reescrever o algoritmo pra
reconhecer "mil" como âncora multiplicativa (tudo antes dele × 1000, resto somado depois), a
mesma classe de mudança que descobriu o bug de "oito dois" somando errado em
`add-voice-recognition-reliability` — Decisão 1, só que maior. Não decidido nem iniciado aqui.

Também vale registrar: `InterpretadorDeFala.QUANTIDADE_ACEITA` é `1..999` — mesmo que o texto
chegasse corretamente sintetizado como "cem mil e cinco", o AGV rejeitaria por estar fora do
intervalo aceito hoje. A necessidade de casa de milhar parece mais alinhada ao `app-wms`
(contagens de estoque maiores) do que ao fluxo de picking por linha do AGV — não assumido como
decisão, só uma leitura a confirmar antes de tocar em `QUANTIDADE_ACEITA`.

### Achados de bancada reportados, não verificados (19/08/2026)

Matheus reportou, testando o contexto: `"um"` não é entendido, `"doze"` é reconhecido como
`"dois"`, e `"21"` ("vinte e um") é reconhecido como `"20"` ("vinte"). Não investigado ainda —
depende de saber **como** o teste foi feito: se for reconhecimento de voz real (mic do Console ou
do app), são achados de modelo acústico, mesma categoria de limitação de palavra curta/isolada já
documentada nas rodadas de Vosk/sherpa-onnx, e não têm correção via YAML — só calibração de
bancada. Se for teste por texto digitado, "um" falhando seria bug de gramática real (a expressão
`$unidade:n1` cobre "um" e deveria bater) — nesse caso vale investigar antes de assumir limite
acústico. Registrado como pendência de investigação, não fechado.

## Open Questions

- **O contrato de nomes de intenção é convenção deste repositório, não algo que o Console
  imponha.** `SintetizadorDeIntencaoRhino.INTENCOES` define que o nome da intenção é a palavra do
  `VocabularioDeVoz` sem acento (`próximo` → `proximo`), porque nome de intenção no Console é
  identificador. Um teste unitário confere a regra entrada por entrada e o motor imprime a lista
  no logcat ao carregar, mas **nada impede o contexto de ser autorado com outros nomes** — se
  isso acontecer, a síntese devolve texto vazio, nenhum evento é publicado e a linha aparece no
  log com `entendido=true` e o `intent` que veio. É falha visível, não silenciosa, e a bancada do
  grupo 6 é onde ela apareceria.
- **A granularidade do slot de check digit continua em aberto** (Decisão 3.1/3.2 das tasks). A
  síntese aceita as duas formas sem saber qual veio — `47` cai em `VocabularioDeVoz.digitos`,
  `quarenta e sete` cai em `checkDigitExtenso` —, então a escolha de como enumerar o slot no
  Console é livre do lado do app e só a bancada decide qual funciona melhor.
