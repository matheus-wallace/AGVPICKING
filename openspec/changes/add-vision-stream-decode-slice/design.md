## Context

Ver proposal.md - Why. Ponto de partida técnico, tudo verificado neste checkout antes de escrever este documento:

- `mwdat-camera` **já é dependência** do módulo (`libs.mwdat.camera`), sem nenhum uso. A superfície pública foi lida com `javap` sobre o `.aar` — ver "Verificação da API de câmera" no fim deste documento. O caminho é `DeviceSession.addCamera(StreamConfiguration) → Camera.stream → Stream.videoStream: Flow<VideoFrame>`, e é exatamente o que o sample `samples/CameraAccess` exercita.
- `DatSessionController` já guarda a `DeviceSession` viva num campo privado, com o comentário de que ela existe para ser "o ponto de acoplamento das capabilities que virão (câmera, na fatia de visão)". É esta fatia.
- `PickingActor` já expõe `state: StateFlow<PickingState>`, hoje consumido só pelo `DevPanelViewModel`. `PickingState.EscaneandoProduto` existe desde a primeira mudança e o KDoc dele já diz "único estado, junto de `ConferenciaFinal`, em que a câmera liga (doc §3.4.3)".
- `PickingEvent.DecodificacaoConcluida(codigoLido)` existe e é tratado pelo reducer **apenas** em `DecodificandoProduto`, estado que só se alcança por `CapturaDisparada` — ou seja, hoje a máquina só modela o caminho com foto. Ver Decisão 4.
- O painel de dev já tem o botão "Decodificação OK (ean)", que publica o mesmo evento com o EAN da linha mockada. Ele continua existindo depois desta fatia, como o botão "parar" continuou existindo depois da fatia de voz.
- `AjustesAsr` estabeleceu o mecanismo de calibração de bancada do projeto: `data class` com defaults de produção, sobreposto por um `.properties` opcional em `getExternalFilesDir`, alterável por `adb push` + `force-stop` sem recompilar. O APK de debug tem 127 MB por causa do modelo Vosk, então recompilar para trocar um número custa perto de um minuto — a mesma razão vale aqui.

## Goals / Non-Goals

**Goals:**
- Provar, contra frames de verdade, o passo 1 da cascata do doc §6.3 — o passo que o §6.2 chama de caminho comum e que, se funcionar, evita foto, latência e bateria.
- Fazer da câmera o primeiro componente que **também lê** o estado do ator, estabelecendo o padrão de gate por estado que `ConferenciaFinal` e o gatilho de captura vão reusar.
- Implementar o ponto de liberação determinística do doc §4.4 agora, enquanto ele é uma linha, e não depois de a cascata ter cinco passos — a afirmação de privacidade do §9.2 depende dele.
- Deixar recorte, resolução e taxa de quadros ajustáveis no aparelho, para que a varredura de distância do §10.2 na manhã de 18/09 seja medição, não recompilação.

**Non-Goals:**
- Gatilho de captura (§6.2: variância do Laplaciano, estabilidade temporal, cooldown de 1,5 s, máximo 3 tentativas) e `capturePhoto()` — passo 2 da cascata, Marco 2.
- Variantes de pré-processamento, zxing-cpp, OCR com fuzzy match, verificação por VLM e check digit de produto — passos 3 a 7 da cascata.
- Parsing GS1 (§6.5). Esta fatia publica a string crua que o leitor devolveu; quem interpreta AIs é o Marco 2.
- Comparação do código lido contra a linha esperada da ordem. O reducer já leva o código para `ValidandoContraDados`; quem produz `ValidacaoOk`/`ValidacaoDivergente` continua sendo o painel de dev.
- Tela espelho (§12) e preview de vídeo. Não há `Surface` nesta fatia: os frames existem para serem decodificados e descartados, não para serem vistos.
- Log estruturado em JSON (§4.5). A tentativa de decodificação é registrada com os campos que o §4.5 pede, mas no `Log` de sempre — o logger JSON é dívida assumida do projeto inteiro, não desta fatia.
- `ConferenciaFinal`, o segundo estado com câmera. O componente é escrito de forma a aceitar mais de um estado-gatilho, mas só o escaneamento é ligado aqui.

## Decisions

1. **A visão consome a sessão do `DatSessionController`, não cria a sua.** O doc §2.3 é claro: uma sessão por dispositivo por vez. Se o componente de visão chamasse `Wearables.createSession`, ele competiria com o controlador que já existe e a segunda sessão falharia. O controlador passa a expor a sessão corrente como `StateFlow<DeviceSession?>` (nula quando não há sessão viva), o que também resolve de graça o caso de queda de Bluetooth no meio de um escaneamento: a sessão vira `null`, o stream é encerrado, e quando a reconexão criar outra sessão o componente reage à nova referência. Alternativa rejeitada: passar a `DeviceSession` por construtor — ela nasce depois do `AppContainer` e é substituída a cada reconexão.

2. **O stream continua comprimido (`compressVideo = true`) e nós decodificamos.** `StreamConfiguration` tem um `compressVideo = false` que faz o SDK decodificar internamente, mas o `javap` mostra que, nesse caminho, o `VideoDecoderBufferHandler` lê apenas `width` e `height` do formato de saída e repassa o `ByteBuffer` cru do `MediaCodec` — **o formato de cor não é declarado em lugar nenhum da API pública**, e `VideoFrame` não o carrega. ML Kit exige formato exato (NV21 ou YV12) para ler um buffer. Aceitar um buffer de formato dependente de aparelho para adivinhar o layout depois é exatamente o tipo de armadilha silenciosa que custou a fatia de áudio (escala `±32767`). Decodificando nós mesmos, o formato é escolha nossa. Alternativa rejeitada: sondar o formato na bancada e assumir — funciona no Galaxy S20 FE e quebra no celular que a Meta entregar no dia 18.

3. **Decodificador com saída por buffer, não por `Surface`, e `getOutputImage` como fonte do frame.** O `HevcDecoder` do sample renderiza direto numa `Surface` e a GPU faz YUV→RGB; ali o destino é a tela, aqui é um leitor de código. Configurando o `MediaCodec` com `surface = null` e `KEY_COLOR_FORMAT = COLOR_FormatYUV420Flexible`, `MediaCodec.getOutputImage(index)` devolve uma `android.media.Image` em `YUV_420_888`, com planos e strides declarados — o formato que ML Kit e o recorte sabem ler sem adivinhação. A lógica de parsing de NAL units (encontrar o prefixo, cachear VPS/SPS/PPS, ativar no primeiro keyframe) é portada do `HevcDecoder` do sample, que por sua vez replica o decodificador interno do SDK; é código chato e já validado, e reescrevê-lo do zero não traria nada. Alternativa rejeitada: `ImageReader` como `Surface` de saída — mais uma peça para gerenciar (e mais um ponto onde vazar um `Image` trava o pipeline) pelo mesmo resultado.

4. **O reducer ganha uma transição em vez de o componente forjar uma captura.** Para chegar ao único estado que hoje aceita `DecodificacaoConcluida`, o componente teria que publicar `CapturaDisparada` antes — anunciando uma captura de foto que não aconteceu, num evento cujo KDoc diz "gatilho de captura disparou (doc §6.2)". Isso envenenaria o log de calibração do §4.5, que precisa distinguir leitura por stream de leitura por foto, justamente a métrica que decide se o passo 2 da cascata é necessário. A transição nova (`EscaneandoProduto` + `DecodificacaoConcluida` → `ValidandoContraDados`) é a tradução literal do §6.2: "se decodificou, acabou: sem foto, sem latência extra, sem gasto de bateria". `CapturaDisparada` e `DecodificandoProduto` ficam intactos para o Marco 2. Alternativa rejeitada: publicar os dois eventos em sequência — menos uma linha de reducer, mas um dado falso no lugar onde a decisão de arquitetura vai ser tomada.

5. **Recorte de 60% central para NV21 como função pura, e esse é o ponto de liberação do §4.4.** O recorte lê os três planos da `Image` em `YUV_420_888` — **direto dos `ByteBuffer` do codec, sem copiá-los para o heap**, senão existiria por um instante uma segunda cópia do quadro completo, que é justamente o que a §9.2 diz que não acontece — e escreve um `ByteArray` NV21 contendo **apenas a ROI**; logo depois, em `finally`, a `Image` é fechada e o buffer do codec liberado. Daí em diante o frame completo não existe em lugar nenhum do processo, que é literalmente o que o §9.2 afirma em público e o que o §4.4 exige que não dependa do coletor de lixo. Ganho colateral: a conversão vira Kotlin puro testável com planos sintéticos, sem Android — o mesmo padrão que pegou um defeito real no band-pass da fatia de áudio (`DegradacaoCanalTelefonicoTest`). Alternativa rejeitada: entregar a `Image` inteira ao ML Kit via `InputImage.fromMediaImage` e deixar o recorte para depois — é menos código, mas inverte a ordem que o §6.3 chama de obrigatória e derruba a afirmação de privacidade.

6. **Um frame por vez, os demais descartados.** O `Flow<VideoFrame>` do SDK entrega no ritmo do stream e o doc §4.1 proíbe bloquear o produtor. O componente mantém um único frame em análise e descarta o que chegar enquanto isso — 7 fps dá ~143 ms por frame, e uma análise que passe disso significa apenas que o próximo frame vira o seguinte, não que a fila cresça até estourar memória com buffers de imagem. Alternativa rejeitada: `Channel` com buffer — enfileirar frames de câmera é o caminho conhecido para OOM, e um frame de 500 ms atrás não tem valor nenhum para quem está mirando um código agora.

7. **Thread própria para decodificação e leitura, nunca o dispatcher do ator.** Mesma regra que a fatia de áudio aplicou ao Vosk, e pela mesma razão: `appScope` roda o reducer em `Dispatchers.Default` e não pode ser ocupado por trabalho de imagem. O `MediaCodec` já entrega callbacks numa `HandlerThread` própria (padrão herdado do sample), e o ML Kit roda num executor de thread única dedicado.

8. **Formatos restritos a `CODE_128`, `DATA_MATRIX` e `EAN_13`, distribuição bundled.** Restringir formatos é a recomendação do próprio ML Kit para velocidade, e são os três que a operação usa: Code 128 na etiqueta de expedição (§6.2), DataMatrix na embalagem farmacêutica (§15) e EAN-13 no produto — que é o campo `ean` do mock. Bundled por exigência explícita do §6.3: a variante via Play Services baixa o modelo no primeiro uso e falharia em silêncio num celular recém-recebido, sem rede confiável, na manhã do evento.

9. **`AjustesVisao` no mesmo molde de `AjustesAsr`.** `fatorRecorte` (0,60), `qualidade` (MEDIUM), `fps` (7), `rotacaoGraus` (0) e `formatos`. Os três primeiros são valores que o doc fixa (§6.3, §8) mas que a varredura do §10.2 pode contrariar com medição; o quarto existe porque a orientação do frame que chega do óculos não é conhecida hoje — no MockDeviceKit ela depende de como o feed foi configurado, e descobrir isso por recompilação seria desperdiçar bancada. Arquivo ausente = valores de produção, exatamente como no áudio.

10. **Na bancada, a fonte de imagem é a câmera traseira do celular.** `MockGlasses.services.camera.setCameraFeed(CameraFacing)` faz o óculos simulado transmitir a câmera do aparelho, então dá para apontar o celular para uma etiqueta impressa e exercitar o caminho inteiro — stream, decodificação, recorte, leitura, evento — sem óculos físico. É o mais próximo do real que a bancada permite e a razão de a permissão `CAMERA` do Android aparecer nesta fatia. `setCameraFeed(Uri)` (vídeo gravado) fica como alternativa reproduzível para quando fizer sentido rodar sempre o mesmo material; nenhuma das duas existe em release, onde o feed é o óculos.

11. **Sem permissão, o escaneamento vira no-op silencioso.** Mesma postura das fatias anteriores
    (`RECORD_AUDIO`), e por um motivo a mais aqui: não existe `PickingEvent` de "câmera
    indisponível", e criar um puxaria estado novo na máquina — fora do escopo. O caminho de
    degradação que o doc já prevê para leitura impossível é o check digit de produto por voz
    (§7.2), que pertence ao Marco 2. Até lá, o painel de dev continua dirigindo o fluxo.

12. **Uma leitura só não publica: exige-se o mesmo código em frames consecutivos.** Decisão
    tomada **depois** da primeira bancada, contra a evidência dela — o desenho original publicava
    na primeira leitura bem-sucedida, e isso produziu falso positivo em 3 de 5 execuções, dois
    deles com dígito verificador de EAN-13 válido (ver "Falso positivo de leitura"). A regra vive
    em `ConsensoDeLeitura`, Kotlin puro e testado: valor diferente **reinicia** a contagem em vez
    de decrementá-la, senão dois valores alternando acabariam publicando um deles. O número é
    `AjustesVisao.confirmacoesDeLeitura`, default 2, e `1` restaura o comportamento anterior —
    existe como ajuste porque é o parâmetro que troca latência por segurança, e a calibração do
    §10 precisa poder varrer os dois lados. Alternativa rejeitada: apenas restringir formatos por
    estado (tirar o `CODE_128` do escaneamento de produto). Ajuda, é grátis, mas não resolve —
    dois dos três falsos positivos eram EAN-13 legítimos e não podiam ter vindo do decodificador
    de Code 128.

## Riscos / Trade-offs

- **[Risco]** `getOutputImage` pode devolver `null` se o decodificador escolhido não honrar `COLOR_FormatYUV420Flexible`. → **Mitigação**: o mesmo `MediaCodecList` que o sample usa para preferir decodificador de software também informa os formatos suportados; a escolha filtra por isso, e a falha é logada de forma inequívoca em vez de virar tela preta. Verificar na tarefa de bancada antes de qualquer ajuste de qualidade.
- **[Risco]** A orientação do frame é desconhecida até rodar, e código de barras lido de lado decodifica pior. → **Mitigação**: `rotacaoGraus` é ajuste de arquivo (Decisão 9), e ML Kit tolera rotação razoavelmente bem em Code 128; medir na bancada com o valor 0 antes de mexer.
- **[Risco]** O doc §2.2 avisa que a compressão adapta-se à banda do Bluetooth e "a imagem pode ser pior que a resolução nominal" — o stream a 504×896 pode simplesmente não ter informação suficiente para DataMatrix. → **Mitigação**: é precisamente a hipótese que esta fatia existe para testar, e o resultado (mesmo negativo) é o número que justifica ou dispensa o passo 2 da cascata. O MockDeviceKit não reproduz a degradação do enlace Bluetooth, então o número honesto só sai em 18/09; o que a bancada mede é o pipeline, não o enlace.
- **[Risco]** A câmera traseira do celular não tem a lente grande-angular de foco fixo do óculos, então nada do que se medir sobre distância mínima de foco (§6.1) transfere. → **Mitigação**: não medir isso agora. A varredura de 15/20/25/30/40 cm é tarefa de 09h30 do dia 18 (§13.3) e continua sendo; a bancada valida o software.
- **[Trade-off]** Portar o parsing de NAL units do sample duplica ~150 linhas de código que o SDK já tem internamente. → Aceito: a classe interna do SDK não é pública, e a alternativa (`compressVideo = false`) troca a duplicação por um formato de pixel não especificado (Decisão 2).
- **[Risco]** ML Kit bundled acrescenta alguns MB ao APK, que já tem 127 MB. → **Medido**: **23 MB**, não "alguns" — o APK de debug foi para 150 MB, quase tudo `libbarhopper_v3.so` empacotado para todas as ABIs. Não muda a decisão (a alternativa unbundled é proibida pelo §6.3), mas o `installDebug` fica mais lento ainda, o que reforça a Decisão 9: calibrar por arquivo, não por recompilação. Se o tempo de instalação virar problema em bancada, um `abiFilters += "arm64-v8a"` no build de debug corta a maior parte disso em uma linha — não feito aqui porque mexer em empacotamento sem necessidade é risco desnecessário perto de 18/09.
- **[Risco]** A câmera ligada e o microfone aberto passam a coexistir pela primeira vez, e o doc §2.1 diz que a ordem HFP → câmera importa e que o inverso "faz a rota falhar em silêncio". → **Mitigação**: no MockDeviceKit não há rota HFP nenhuma — o áudio vem do microfone do celular —, então esta fatia não pode verificar a ordem; ela só vale quando `AudioHfpOculos` existir. Registrado aqui para que quem escrever aquela fatia saiba que a interação é conhecida e não verificada.

## Verificação da API de câmera

Feita com `javap` sobre o `classes.jar` do `mwdat-camera-0.9.0.aar` e por leitura de `samples/CameraAccess`, antes de escrever qualquer integração:

- `DeviceSession.addCamera(StreamConfiguration): DatResult<Camera, …>` (extensão em `SessionCameraExtensionsKt`); `Camera.stream: Stream`; `Stream.start()`, `Stream.stop()`, `Stream.state: StateFlow<StreamState>`, `Stream.errorStream: Flow<StreamError>`, `Stream.videoStream: Flow<VideoFrame>`, `Stream.capturePhoto(): DatResult<PhotoData, CaptureError>` (não usado aqui).
- `StreamConfiguration(videoQuality: VideoQuality, frameRate: Int, compressVideo: Boolean)`; `VideoQuality` é `HIGH`/`MEDIUM`/`LOW`, batendo com as resoluções do doc §2.2.
- `VideoFrame(buffer: ByteBuffer, width, height, presentationTimeUs, isCompressed, isCodecConfig)` — **não há campo de formato de pixel**. É a base da Decisão 2.
- O `Camera` precisa de `close()`/`stop()` explícito: o comentário do sample registra que sem isso o próximo `addCamera()` falha com "a capability of this type is already active". Vale para cada saída do estado de escaneamento.
- `MockGlasses.services.camera` expõe `setCameraFeed(Uri)`, `setCameraFeed(CameraFacing)` e `setCapturedImage(Uri)` — a base da Decisão 10.
- Permissão de câmera do DAT é separada da permissão Android: `Wearables.checkPermissionStatus(Permission.CAMERA)` consulta sem redirecionar, e a solicitação de fato manda o usuário para o app Meta AI. Ainda **não verificado**: se o MockDeviceKit já responde `Granted` sem redirecionamento nenhum, como faz com o registro. É a primeira coisa a olhar na bancada.

## Verificação em bancada

Feita no mesmo Galaxy S20 FE (SM-G780F) da fatia de áudio, com o óculos simulado transmitindo a
câmera traseira do celular (Decisão 10) e o painel de dev dirigido por `adb` (dump de UI +
`input tap`), o que torna cada execução repetível: `force-stop` → abrir → quatro toques até
`EscaneandoProduto` → esperar a leitura.

**O caminho inteiro fecha.** Da subida do stream ao evento publicado, com o código certo:

| O que | Medido |
|---|---|
| Permissão de câmera do DAT no MockDeviceKit | `Granted` direto, **sem redirecionar** para o app Meta AI (resolve a pendência da seção anterior e a tarefa 1.3) |
| Decodificador escolhido | `c2.android.hevc.decoder`, software — como planejado |
| Formato de cor negociado | `2135033992` = `COLOR_FormatYUV420Flexible`; `getOutputImage` nunca devolveu `null` |
| Resolução real do frame | **480×640**, com `stride=512` e `max-width=504, max-height=896` só como teto |
| ROI resultante | 288×384 (60% do que chegou de fato) |
| Tempo por tentativa de leitura | 17–109 ms, mediana ~23 ms |
| Da subida do stream à leitura | 1,9 s no melhor caso, 1 a 13 tentativas |
| Eventos publicados por escaneamento | 1, sempre — a guarda funciona |
| Arquivos de imagem em `cache`/`files`/externo | nenhum, com o escaneamento rodando (spec - Requirement: Frame completo é descartado) |

Três coisas que só a bancada mostrou:

1. **A resolução nominal do doc §2.2 não é a que chega.** `MEDIUM` deveria ser 504×896 e o frame
   veio 480×640. O recorte usa `imagem.width/height`, não a constante de [AjustesVisao], então
   saiu correto — se tivesse confiado na constante, a ROI estaria deslocada e o sintoma seria
   "não decodifica", sem nenhuma pista. Vale como aviso para todo código futuro que for tentado a
   assumir a resolução configurada.
2. **O `stride` (512) é maior que a largura (480)**, confirmando na prática o padding de fim de
   linha que o teste de `RecorteRoi` já cobria. Ignorá-lo produziria uma imagem enviesada em
   diagonal — que continua parecendo uma imagem.
3. **O teardown gerava um `E` no logcat a cada escaneamento.** Callbacks do `MediaCodec` já em
   voo chegavam depois do `parar()` e lançavam ao tocar no codec. Corrigido com um `return`
   antes de qualquer uso do codec e rebaixando o log para `D` quando o decodificador já não
   deveria estar vivo: um erro real de codec não pode nascer afogado no ruído de encerramento.

### Falso positivo de leitura — o achado que importa

Nas primeiras quatro leituras com os três formatos ligados, **três vieram erradas**:
`7040242395062`, `551269381992`, `896344202244`, contra o `7896523202204` esperado. O terceiro
denuncia o mecanismo: doze dígitos, reaproveitando pedaços do EAN correto (`...202244` contra
`...202204`). Não é a câmera pegando outro código na cena — é o decodificador lendo um **trecho**
do mesmo código e devolvendo um valor plausível.

A primeira hipótese foi ter `CODE_128` ligado junto de `EAN_13`: Code 128 aceita conteúdo de
tamanho variável, então um pedaço parcial de um EAN-13 pode ser interpretado como um Code 128
válido, com checksum fechando — o "short read" clássico. Duas baterias de cinco execuções cada,
alternadas pelo arquivo de ajustes (`adb push` + `force-stop`, sem recompilar), com o mesmo alvo:

| Formatos ligados | Corretas | Erradas | Sem leitura | Códigos errados observados |
|---|---|---|---|---|
| `EAN_13` apenas | 4 | **0** | 1 | — |
| `CODE_128` + `DATA_MATRIX` + `EAN_13` | 2 | **3** | 0 | `4796578601904`, `7793563202204`, `001242395022` |

A hipótese explica parte do fenômeno, mas **não tudo**, e o dado que a derruba como explicação
única está na própria tabela: `4796578601904` e `7793563202204` têm treze dígitos e **dígito
verificador válido** — são EAN-13 legítimos, não podem ter vindo do decodificador de Code 128.
O segundo é assustador de perto: `7793563202204` contra `7896523202204`, mesmo comprimento, mesma
cauda `3202204`, e passa no check digit. O check digit de EAN-13 só pega cerca de 90% dos erros
aleatórios; este caiu nos 10%.

**A conclusão que interessa é independente da causa:** publicar na primeira leitura bem-sucedida
de um único frame não é seguro. Em separação farmacêutica o falso positivo é a pior falha
possível — o sistema confirma o produto errado com a mesma confiança do certo —, e é exatamente
o que o doc §10.4 manda medir. A correção robusta é **consenso temporal**: só publicar depois de
ler o mesmo valor em dois ou três frames consecutivos. A 7 fps isso custa ~0,3–0,6 s, dentro do
orçamento medido (as leituras saem em 1 a 16 tentativas), e protege contra a classe inteira de
erro, não só contra o Code 128. Restringir formatos por estado ajuda e é grátis, mas sozinho não
resolve.

### O consenso temporal resolveu

Implementado como [ConsensoDeLeitura] (Decisão 12) e medido na mesma bancada, mesmo alvo, com os
**três formatos ligados** — ou seja, sem tirar o `CODE_128` do caminho:

| Configuração | Corretas | Erradas | Sem leitura | Tentativas até publicar |
|---|---|---|---|---|
| Publicando na primeira leitura | 2 | 3 | 0 | 1–65 |
| `confirmacoesDeLeitura = 2` | **5** | **0** | 0 | 2–3 |

Cinco em cinco, nenhum falso positivo, e o custo ficou em uma tentativa a mais — não nos frames
inteiros que o cálculo teórico previa, porque a leitura correta se repete no frame seguinte
enquanto o falso positivo não. É o resultado que justifica manter os três formatos ligados: com
consenso, o `CODE_128` volta a ser aceitável no estado de escaneamento, e a etiqueta de expedição
do §6.2 continua legível sem um segundo modo de operação.

### Encerramento do stream

Verificado nos três caminhos de saída de `EscaneandoProduto`, todos dirigidos por `adb`:

| Gatilho | Resultado |
|---|---|
| Leitura publicada | stream encerrado 69 ms depois do evento |
| Perda de Bluetooth (`DEBUG_DESLIGAR_OCULOS`) | `DeviceSessionState = STOPPED` → stream encerrado 17 ms depois |
| Transversal de emergência (equivalente ao "parar" por voz — mesmo ramo do reducer) | stream encerrado |

E o que o `close()` do `Camera` protege: **um segundo escaneamento no mesmo processo abriu a
câmera de novo**, depois de um stream anterior já ter sido encerrado. Sem o `close()`, o
`addCamera` falharia com "a capability of this type is already active" e a segunda linha da ordem
ficaria sem câmera.

Um detalhe de MockDeviceKit que custou tempo e vale registrar: **desligar o óculos simulado
(`powerOff`) derruba a sessão mas ela não volta** — `createSession` seguinte fica em `STARTING` e
estoura o timeout de 20 s. O caminho que recupera é despareamento e novo pareamento
(`DEBUG_DESPAREAR_OCULOS` + `DEBUG_LIGAR_OCULOS`), que reconectou normalmente (`STARTED`,
`fase=PERDIDA`). É limitação do simulador, não do controlador de sessão — mas quem for exercitar
queda de conexão em bancada precisa saber qual dos dois hooks usar.

### O que a caixa da bancada revelou sobre o próprio risco do §15

Ao procurar o DataMatrix da caixa para a tarefa 6.4, o único código 2D nela é um **QR Code** com
a bula digital — obrigatório em varejo desde a RDC 885/2024. `QR_CODE` não está na lista de
formatos aceitos (Decisão 8), então o leitor simplesmente não o reconhece: 2.643 tentativas,
`resultado=nada` em todas. Isso é o comportamento correto, não uma falha — mas também significa
que a **tarefa 6.4 ficou em aberto por falta de alvo**, não por limitação do pipeline.

A causa é maior que a bancada: a serialização SNCM que motivaria um DataMatrix de rastreabilidade
em caixa de varejo foi adiada e nunca entrou em vigor para esse canal. Hoje DataMatrix aparece em
linha hospitalar e alguns injetáveis, não na caixa de farmácia comum. **Se as caixas físicas da
demo (doc §14) forem de varejo — o caso mais provável —, o risco "DataMatrix não decodifica em
720p" do §15 (probabilidade alta, impacto alto) pode simplesmente não se materializar**, porque o
código 2D que vai aparecer é o QR da bula, não um DataMatrix de produto. Vale confirmar com quem
estiver providenciando as caixas antes de investir esforço de calibração nesse risco especificamente.

**Efeito colateral que vale registrar como decisão, não só como nota**: o QR da bula é um código
2D no mesmo campo de visão do código que interessa, e hoje é inofensivo só porque `QR_CODE` não
está entre os formatos aceitos. Se uma fatia futura adicionar `QR_CODE` "para testar outra coisa"
sem pensar em qual estado o adiciona, o leitor passa a poder publicar o link da bula no lugar do
GTIN do produto — um distrator plausível dentro do próprio campo de visão, e sem o dígito
verificador do EAN-13 para pegar o erro. Qualquer expansão de formato precisa considerar essa
vizinhança.

**Ressalva sobre este experimento**, para quem for repeti-lo: as duas baterias rodaram contra um
alvo que pode ter sido a caixa física ou um EAN-13 renderizado na tela do Mac — as duas coisas
estavam disponíveis na bancada e o registro não distingue. Tela tem refresh e moiré, que podem
degradar a leitura de um jeito que papel não degrada. A amostra de dez execuções é suficiente
para provar que **falso positivo acontece**, e não é suficiente para atribuir a taxa a um formato
específico. Antes de fixar qualquer número em documento de pitch, repetir com alvo declarado.

## Open Questions

- O `Stream` pausa junto com a sessão quando o óculos é retirado (`DeviceSessionState.PAUSED`), ou continua entregando frames? O gate por estado do ator cobre o caso de qualquer jeito — `PausaDat` tira o fluxo de `EscaneandoProduto` e o componente encerra o stream —, então a resposta muda no máximo a ordem de teardown, não o desenho.
- Se a leitura pelo stream se mostrar confiável demais na bancada (feed vindo da câmera do celular, sem degradação de enlace), o passo 2 da cascata pode parecer desnecessário por engano. A decisão sobre implementá-lo pertence ao Marco 2 e deve esperar o número de 18/09, não o da bancada.
