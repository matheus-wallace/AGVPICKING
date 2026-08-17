# Handoff — AGV Pick Voice

**Atualizado em 17/08/2026 (sessão Claude, à noite)** · ponto de partida para a próxima
sessão de desenvolvimento.

Este arquivo resume o estado efetivamente entregue do protótipo em `AgvPickVoice/`.
Leia também `DOC-TECNICA-AGV-PICK-VOICE-v1.5.md` para as decisões de produto e os
documentos em `openspec/changes/` para requisitos, decisões e tarefas de cada fatia.

## Estado atual

O app abre por padrão na **tela operacional unificada** (`OperationScreen`), e desde hoje
o fluxo é hands-free de verdade: a gramática de voz muda por `PickingState`
(`add-state-driven-voice-flow`), então o operador não precisa mais tocar em nada depois de
escolher a ordem. O painel de debug continua acessível por um link, mas é só diagnóstico —
nada nele produz `PickingEvent` fora de bancada.

O último commit é `56c8eb0` (`Archive add-operation-step-indicator`), na `main`. **196
testes, 0 falhas**, `assembleDebug`/`lintDebug` limpos. Nada está pendente de commit.

Três defeitos reais foram encontrados e corrigidos rodando o app de verdade pela primeira
vez com o fluxo por voz — ver "Comportamento atual por subsistema" e a Decisão 10–12 de
`add-operator-feedback-improvements/design.md`:

1. **Crash nativo (`SIGSEGV`)** ao sair de `EscaneandoProduto` — corrida entre o decodificador
   HEVC e o desligamento da câmera. Corrigido com uma trava.
2. **Preview da câmera ficava preto para sempre** — a miniatura só compunha com o stream já
   `ATIVO`, tarde demais para o decodificador do preview receber VPS/SPS/PPS. Corrigido
   compondo desde `INICIANDO`.
3. **`TratandoExcecao` era beco sem saída** quando o ASR não fechava um relato completo —
   ganhou duas saídas: "próximo" por voz e um botão de toque.

## Entregas implementadas

| Área | Entrega | Pontos de entrada |
|---|---|---|
| Domínio | Máquina de estados, reducer puro e ator serial (`Channel` + `StateFlow`); dados WMS fictícios, mas com formato realista | `domain/statemachine/`, `data/` |
| DAT | Registro e sessão reais, mapeados para `PickingEvent`; reconexão cria uma sessão nova | `dat/DatSessionController.kt` |
| Mock | Em `debug`, um Ray-Ban Meta simulado é pareado, ligado, aberto e vestido automaticamente; em `release` o bootstrap é no-op | `src/debug/.../MockDeviceBootstrap.kt` |
| Voz — reconhecimento | Vosk pt-BR offline, microfone do celular, filtro/decimação 16 kHz → 8 kHz | `audio/ReconhecedorDeComando.kt` |
| Voz — gramática por estado | `SeletorDeEscuta` troca a gramática do Vosk conforme o `PickingState`; "próximo" é sinônimo aditivo de avanço em quase todo estado com uma única palavra; números aceitos por extenso ou dígito a dígito | `audio/SeletorDeEscuta.kt`, `VocabularioDeVoz.kt` |
| Voz — interpretação | Texto ASR → intenção → `PickingEvent`, com validação de check digit sem expor o valor esperado e resolução de próxima linha | `audio/InterpretadorDeFala.kt`, `ResolvedorDeIntencao.kt`, `PublicadorDeVoz.kt` |
| Voz — log de calibração | Todo resultado final do Vosk logado, aceito ou descartado, com o motivo do descarte (fora da gramática vs. versão de estado obsoleta) | `audio/ResultadoDePublicacao.kt` |
| Visão | Stream DAT HEVC → decodificação → ROI central → ML Kit bundled; consenso de duas leituras e publicação de um único evento | `vision/ControladorDeVisao.kt` |
| Visão — comparador | `ValidandoContraDados` compara o código lido contra o EAN da linha (ou aceita direto o sentinela do check digit por voz) e publica `ValidacaoOk`/`ValidacaoDivergente` sozinho — antes só o botão do painel fazia isso | `vision/ComparadorDeCodigo.kt` |
| Fallback de visão | Após falhas consecutivas no stream, captura foto, normaliza orientação/ROI e tenta a mesma leitura; sem teto de tentativas que escalone sozinho (Decisão 4/5 de `add-operator-feedback-improvements`) | `vision/PreparadorFoto.kt`, `LimpadorCapturas.kt`, `GatilhoDeCaptura.kt` |
| Miniatura de câmera | Flutuante, arrastável (limitada às bordas da tela), dispensável, hospedada uma vez em `MainActivity` acima de qualquer superfície — sobrevive à troca de tela/etapa; `TextureView` em vez de `SurfaceView` para suportar o arraste sem tela preta | `ui/mirror/MiniaturaDeCamera.kt`, `PreviaEspelho.kt` |
| TTS | Fala orientada por estado, em pt-BR, com readback de quantidade falado e reforçado em correção; dedupe por estado | `audio/output/` |
| Tela operacional | Cabeçalho com progresso/situação **e o nome da etapa atual** (um rótulo por `PickingState`, granular o bastante para distinguir os 4 estados do balde quantidade); dica de comando de voz por estado; saída de toque só na ocorrência; tema verde acessível (contraste AA verificado) | `ui/operation/` |
| Debug | Painel só diagnóstico; check digit esperado aparece em texto **só em build debug** | `ui/devpanel/` |

## Arquitetura e invariantes

`AppContainer` é a composição manual das dependências e vive no processo. Ele possui o
`PickingActor`; DAT, ASR, visão e painel são produtores de `PickingEvent`. Só o reducer
altera o estado. A UI é consumidora de `StateFlow` e não deve introduzir transições — a
única exceção é `OperationViewModel.registrarOcorrencia()`, o único `send` que a tela do
operador faz, e só existe porque `TratandoExcecao` não tinha outra saída quando a voz falha.

```text
DAT ────────┐
ASR ────────┼──> PickingActor ──> PickingReducer ──> StateFlow<PickingState>
visão ──────┤         │                                        │
painel dev ─┤         └──────── eventos seriais ───────────────┤
tela oper. ─┘ (só registrarOcorrencia)                          ├─ TTS (observa)
                                                                  ├─ câmera/ML Kit (observa)
                                                                  └─ Compose (observa)
```

Regras que não devem ser quebradas:

- Não criar uma segunda `DeviceSession`: visão observa `DatSessionController.sessaoAtiva`.
- Sessão em `STOPPED` é terminal; a reconexão deve criar outra sessão.
- Câmera só é aberta em `EscaneandoProduto`; pare-a em `onStop`, troca/perda de sessão e saída do estado.
- O caminho crítico não persiste quadros. A `Image` do codec é liberada após produzir o recorte; a prévia só recebe HEVC para uma `Surface` válida.
- **Consumo da `Image` de saída do `DecodificadorHevc` precisa da trava `travaDoCodec`** — sem ela, um desligamento de câmera no meio de um recorte derruba o processo (`SIGSEGV`). Ver `DecodificadorHevc.kt`.
- A câmera é a única produtora de código óptico. Voz não deve publicar código de barras.
- ASR, TTS e codec não podem bloquear a main thread ou a coroutine do ator.
- `MockDeviceKit` existe apenas em `debugImplementation`; não leve bootstrap ou UI de mock ao release.
- `Vosk Recognizer`/`Silero OrtSession` não são thread-safe: confinados à `dispatcherAudio`.

## Comportamento atual por subsistema

### Sessão DAT

Em debug, o bootstrap usa a câmera traseira do celular como feed do dispositivo simulado.
Para simular perda de sessão, use o hook de despareamento do bootstrap; `powerOff()` do
MockDeviceKit não produz uma transição confiável para `STOPPED`. A permissão de câmera do
DAT é solicitada só ao entrar no escaneamento; a `CAMERA` Android é necessária em debug
porque o mock espelha a câmera do telefone.

### Voz

A gramática do Vosk agora **muda por `PickingState`**, via `SeletorDeEscuta`. Cada
transição de estado recria a gramática na thread dedicada do ASR; resultados de uma versão
de estado anterior (comparados por um contador atômico) são descartados, assim como
resultados enquanto o TTS está falando. "Próximo" é aceito como sinônimo aditivo de avanço
em quase todo estado de uma palavra só — a lista exata está em
`VocabularioDeVoz`/`InterpretadorDeFala`, comentada estado a estado. Quantidade aceita
número por extenso ("doze") ou dígito a dígito ("um dois" → 12); check digit é sempre
dígito a dígito, dois algarismos exatos, nunca revelado em tela de release.

O modelo `vosk-model-small-pt-0.3` está versionado em `src/main/assets/modelo-vosk-pt/`;
não remova o arquivo `uuid`. Os ajustes podem ser colocados no diretório externo do app
usando `ajustes-asr.properties.exemplo`, sem recompilar o APK. HFP dos óculos ainda **não**
foi implementado: a fonte atual é `AudioMicrofoneSimulado` (microfone do celular) — ver
"Próximo trabalho recomendado".

### Visão

Defaults: `MEDIUM`, 7 FPS, ROI central de 60%, formatos `CODE_128`, `DATA_MATRIX` e
`EAN_13`, e duas confirmações consecutivas do mesmo valor. A resolução observada no
SM-G780F com MockDeviceKit foi 480×640 (não os 504×896 solicitados). A prévia e a análise
usam decodificadores HEVC separados, alimentados pelo mesmo pacote comprimido; falha da
prévia não pode interromper a leitura. **O consumo da imagem de saída precisa da trava
`travaDoCodec`** (ver Arquitetura acima) — sem ela, dizer um comando transversal
("avaria" etc.) durante o escaneamento derrubava o app.

O fallback por foto não escalona mais sozinho depois de N tentativas — isso saía de
`EscaneandoProduto` sem sinal nenhum do operador de que a caixa já estava aberta, fechando
a janela de leitura cedo demais. Hoje só sai por sucesso ou por um transversal explícito.

O EAN-13 `7896523202204` foi lido da caixa Loratamed em 5/5 execuções com consenso, em
2–3 tentativas. A validação física de DataMatrix continua pendente: a caixa disponível tem
apenas QR Code de bula, que não faz parte da lista de formatos.

A miniatura de câmera é `TextureView` (não `SurfaceView`), compõe desde
`EstadoStreamVisao.INICIANDO` (não só `ATIVO`) e vive uma única vez em `MainActivity`,
acima de qualquer tela — arrastável, com o arraste preso às bordas.

### Fala do sistema

`ControladorDeFala` projeta `PickingState` e diagnóstico de visão para mensagens curtas e
deduplica por estado. `ReadbackQuantidade` agora fala "Confirma {quantidade}?" (antes era
mudo); ao voltar por "corrigir", a quantidade esperada é repetida. `SaidaTextToSpeechAndroid`
usa `TextToSpeech` local e preserva a voz padrão pt-BR do dispositivo. `SaidaDeAudio.falando`
segue sendo o gate único entre TTS e ASR — resultado de voz chegando durante a fala é
descartado, sem barge-in.

## Próximo trabalho recomendado

Nada bloqueado por decisão de design — o que resta é quase todo bancada com voz humana e
caixa física, e um item de arquitetura (HFP) que precisa de medição no aparelho:

1. **Bancada pendente das últimas fatias** (nenhuma requer código novo, só medição):
   - `add-state-driven-voice-flow` 4.3 — ordem mockada completa, multi-linha, sem tocar em
     nada após a seleção inicial.
   - `add-scan-code-comparator` 3.3/3.4 — câmera real contra a caixa Loratamed, e o
     fallback por check digit de voz chegando em `ConfirmandoQuantidade` sozinho.
   - `add-operator-feedback-improvements` 4.2/9.2 — readback falado, log de ASR em bancada,
     miniatura/tema/dica de voz vistos ao vivo.
   - `add-operator-feedback-improvements` 10.8 — confirmar que os três defeitos corrigidos
     hoje (crash, preview preto, saída da ocorrência) não voltam com voz humana de verdade.
2. **HFP dos óculos** (doc §13.3, `AudioHfpOculos`): troca de uma linha em `AppContainer`,
   mas duas coisas precisam de medição real antes de assumir que funciona — se
   `VOICE_COMMUNICATION` de fato captura áudio numa rota HFP ao vivo (hoje devolve silêncio
   digital no microfone do celular), e se o limiar de -27 dBFS calibrado com o microfone do
   celular ainda vale com o microfone na haste.
3. **Fatias mais antigas ainda com bancada em aberto** (não fazem parte do trabalho de
   hoje, mas seguem pendentes): `add-audio-state-feedback` (9/12), `add-photo-capture-decode-fallback`
   (11/13), `add-vision-mirror-preview` (15/18), `add-vision-stream-decode-slice` (34/35,
   a tarefa 6.4 fica deliberadamente aberta — sem DataMatrix físico disponível).
4. Se o modo paisagem virar requisito real (ex.: celular montado no carrinho), tratar a
   remoção do `android:screenOrientation="portrait"` fixo do `MainActivity` como mudança à
   parte — a resiliência a paisagem descrita no `design.md` da tela operacional é teórica,
   porque a orientação está travada em retrato no manifest.
5. Nenhuma proposta OpenSpec nova está pendente de implementação no momento — todos os
   `add-*` ativos em `openspec list` já têm a parte de código feita, só falta bancada (ou,
   no caso das fatias mais antigas do item 3, seguem como estavam).

## Verificação e execução

Sempre execute dentro de `AgvPickVoice/`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew installDebug
```

A última verificação registrada passou com **196 testes, 0 falhas**, além de
`assembleDebug` e `lintDebug` limpos (17 avisos preexistentes, 0 erros). Rode novamente
após qualquer alteração; não trate essa contagem como resultado da sua mudança — conte
você mesmo em `app/build/test-results/testDebugUnitTest/*.xml` antes de confiar num número
relatado por terceiros (já aconteceu de um relato de agente vir inflado).

Para configuração de bancada sem recompilar:

```bash
adb push ajustes-asr.properties /sdcard/Android/data/com.agvtronic.pickvoice/files/
adb push ajustes-visao.properties /sdcard/Android/data/com.agvtronic.pickvoice/files/
adb shell am force-stop com.agvtronic.pickvoice
```

Use os arquivos `ajustes-*.properties.exemplo` como ponto de partida. Não registre nem
suba credenciais de `local.properties`; o token de GitHub para resolver DAT permanece local.

Para dirigir a bancada por `adb` sem tocar no aparelho (dump de `uiautomator` + `input tap`
+ `screencap`), é um padrão repetível que já provou o step indicator e os três bugfixes
desta sessão — não há scripts versionados no repo, cada sessão escreve os seus em um
diretório de trabalho temporário.

## Onde encontrar o detalhe

- Especificação/decisões de cada entrega: `openspec/changes/add-*/{proposal,design,tasks}.md`
  e `openspec/changes/archive/*/` para o que já foi arquivado.
- Contratos que a próxima implementação deve respeitar: `openspec/changes/add-*/specs/`.
- **`openspec/specs/` (specs principais consolidadas) ainda não existe** — nenhuma fatia
  chegou a sincronizar até agora, de propósito: as specs vivem nas pastas de change. Não
  assuma que existe uma fonte de verdade única fora delas.
- Estratégia, restrições da demo e decisões maiores: `DOC-TECNICA-AGV-PICK-VOICE-v1.5.md`.
- Instruções específicas de DAT e API atual: `AGENTS.md` na raiz; não inventar símbolos do SDK.
