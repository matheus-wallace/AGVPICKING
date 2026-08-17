# Handoff — AGV Pick Voice

**Atualizado em 16/08/2026 (sessão Claude, à noite)** · ponto de partida para a próxima
sessão de desenvolvimento.

Este arquivo resume o estado efetivamente entregue do protótipo em `AgvPickVoice/`.
Leia também `DOC-TECNICA-AGV-PICK-VOICE-v1.5.md` para as decisões de produto e os
documentos em `openspec/changes/` para requisitos, decisões e tarefas de cada fatia.

## Estado atual

O app abre por padrão na **tela operacional unificada** (`OperationScreen`), não mais no
painel de debug — o painel continua acessível por um link e não é mais o controlador da
operação. O app sobe em debug sem óculos físicos, usando o `MockDeviceKit`, e chega a
`AguardandoOrdem`. A visão e o TTS estão integrados; o ASR está integrado, mas só
reconhece os comandos transversais `parar` e `repetir` — o avanço normal do fluxo ainda
depende dos botões do painel de debug, porque a gramática de voz ainda não é dependente
do estado.

O último commit aplicado é `5e31796` (`Document state-driven TTS delivery`); a mudança
`add-unified-picking-operation-screen` foi implementada por cima dele e está pronta para
commit (ver seção "Próximo trabalho recomendado"). Há uma proposta OpenSpec **ainda não
implementada** que deve ser preservada:

- `openspec/changes/add-state-driven-voice-flow/`

Ela é o próximo passo recomendado: torna a gramática de voz dependente do `PickingState`,
o que é o único jeito de tornar a operação realmente hands-free (a tela operacional já
existe, mas hoje é alimentada pelos botões do painel, não pela voz). A task 4.4 de
`add-unified-picking-operation-screen` está deliberadamente bloqueada até essa mudança
existir — ver `openspec/changes/add-unified-picking-operation-screen/tasks.md`.

## Entregas implementadas

| Área | Entrega | Pontos de entrada |
|---|---|---|
| Domínio | Máquina de estados, reducer puro e ator serial (`Channel` + `StateFlow`); dados WMS fictícios, mas com formato realista | `domain/statemachine/`, `data/` |
| DAT | Registro e sessão reais, mapeados para `PickingEvent`; reconexão cria uma sessão nova | `dat/DatSessionController.kt` |
| Mock | Em `debug`, um Ray-Ban Meta simulado é pareado, ligado, aberto e vestido automaticamente; em `release` o bootstrap é no-op | `src/debug/.../MockDeviceBootstrap.kt` |
| ASR | Vosk pt-BR offline, microfone do celular, filtro/decimação 16 kHz → 8 kHz, logs de nível e arquivo de calibração | `audio/` |
| Visão | Stream DAT HEVC → decodificação → ROI central → ML Kit bundled; consenso de leituras e publicação de um único evento | `vision/ControladorDeVisao.kt` |
| Fallback de visão | Após falhas consecutivas no stream, captura foto, normaliza orientação/ROI e tenta a mesma leitura; arquivos temporários são limpos | `vision/PreparadorFoto.kt`, `LimpadorCapturas.kt` |
| Prévia | Espelho efêmero do stream em `SurfaceView`, ROI alinhada ao vídeo renderizado e telemetria; não grava imagem/vídeo | `ui/mirror/`, `vision/RenderizadorHevc.kt` |
| TTS | Fala orientada por estado, em pt-BR, com seleção da voz padrão como primeira opção e fallback por qualidade/latência | `audio/output/` |
| Debug | Painel conserva os controles para dirigir o ator durante bancada e mostra diagnóstico de áudio/visão | `ui/devpanel/` |
| Tela operacional | `OperationScreen` única, projeção pura de `OperationUiState` (ator + ordem + diagnóstico de voz/visão); cartão central troca entre endereço/produto/quantidade/mensagem; prévia+ROI extraídos para componente reutilizável; troca para o painel de debug não manda `PickingEvent` nem reinicia sessão/áudio | `ui/operation/`, `ui/mirror/PreviaEspelho.kt`, `MainActivity.kt` |

## Arquitetura e invariantes

`AppContainer` é a composição manual das dependências e vive no processo. Ele possui o
`PickingActor`; DAT, ASR, visão e painel são produtores de `PickingEvent`. Só o reducer
altera o estado. A UI é consumidora de `StateFlow` e não deve introduzir transições.

```text
DAT ────────┐
ASR ────────┼──> PickingActor ──> PickingReducer ──> StateFlow<PickingState>
visão ──────┤         │                                        │
painel dev ─┘         └──────── eventos seriais ───────────────┤
                                                                  ├─ TTS (observa)
                                                                  ├─ câmera/ML Kit (observa)
                                                                  └─ Compose (observa)
```

Regras que não devem ser quebradas:

- Não criar uma segunda `DeviceSession`: visão observa `DatSessionController.sessaoAtiva`.
- Sessão em `STOPPED` é terminal; a reconexão deve criar outra sessão.
- Câmera só é aberta em `EscaneandoProduto`; pare-a em `onStop`, troca/perda de sessão e saída do estado.
- O caminho crítico não persiste quadros. A `Image` do codec é liberada após produzir o recorte; a prévia só recebe HEVC para uma `Surface` válida.
- A câmera é a única produtora de código óptico. Voz não deve publicar código de barras.
- ASR, TTS e codec não podem bloquear a main thread ou a coroutine do ator.
- `MockDeviceKit` existe apenas em `debugImplementation`; não leve bootstrap ou UI de mock ao release.

## Comportamento atual por subsistema

### Sessão DAT

Em debug, o bootstrap usa a câmera traseira do celular como feed do dispositivo simulado.
Para simular perda de sessão, use o hook de despareamento do bootstrap; `powerOff()` do
MockDeviceKit não produz uma transição confiável para `STOPPED`. A permissão de câmera do
DAT é solicitada só ao entrar no escaneamento; a `CAMERA` Android é necessária em debug
porque o mock espelha a câmera do telefone.

### Voz

`ReconhecedorDeComando` usa hoje a gramática fixa:

```json
["parar", "repetir", "[unk]"]
```

`[unk]` é obrigatório para evitar que fala/ruído vire um comando conhecido. O modelo
`vosk-model-small-pt-0.3` está versionado em `src/main/assets/modelo-vosk-pt/`; não remova
o arquivo `uuid`. Os ajustes podem ser colocados no diretório externo do app usando
`ajustes-asr.properties.exemplo`, sem recompilar o APK. HFP dos óculos ainda não foi
implementado: a fonte atual é `AudioMicrofoneSimulado` (microfone do celular).

### Visão

Defaults: `MEDIUM`, 7 FPS, ROI central de 60%, formatos `CODE_128`, `DATA_MATRIX` e
`EAN_13`, e duas confirmações consecutivas do mesmo valor. A resolução observada no
SM-G780F com MockDeviceKit foi 480×640 (não os 504×896 solicitados). A prévia e a análise
usam decodificadores HEVC separados, alimentados pelo mesmo pacote comprimido; falha da
prévia não pode interromper a leitura.

O EAN-13 `7896523202204` foi lido da caixa Loratamed em 5/5 execuções com consenso, em
2–3 tentativas. A validação física de DataMatrix continua pendente: a caixa disponível tem
apenas QR Code de bula, que não faz parte da lista de formatos.

### Fala do sistema

`ControladorDeFala` projeta `PickingState` e diagnóstico de visão para mensagens curtas e
deduplica estados. `SaidaTextToSpeechAndroid` usa `TextToSpeech` local e preserva a voz
padrão pt-BR do dispositivo quando ela atende aos requisitos; não voltar a selecionar uma
voz fixa por índice. Durante o próximo slice de ASR, suspenda resultados enquanto o TTS
fala e descarte resultados associados a uma versão anterior do estado.

## Próximo trabalho recomendado

1. **Commitar `add-unified-picking-operation-screen`** (tasks 1–3 e 4.1–4.3 concluídas e
   verificadas em bancada; só falta o commit, feito nesta mesma sessão — ver git log).
2. Implementar `add-state-driven-voice-flow` conforme suas tasks 1–4. Começar por contratos
   puros e testes: configuração de escuta por `PickingState`, interpretador pt-BR e adaptador
   de validação de check digit/próxima linha. Só então refatorar o Vosk para trocar gramática
   na sua própria thread. Esse é o passo que desbloqueia a task 4.4 da tela operacional.
3. Integrar o gate entre `SaidaDeAudio` e ASR: resultados enquanto TTS fala, ou de uma versão
   anterior do estado, são descartados. Não implementar barge-in sem medição física.
4. Validar uma ordem mockada de múltiplas linhas sem toque após a seleção inicial, dentro da
   `OperationScreen` (não mais no painel). A leitura de produto continua exclusivamente pela
   câmera.
5. Fechar a bancada pendente: etiqueta DataMatrix física, qualidade/impacto da prévia e ciclo
   completo com microfone. Registre resultados na `design.md` da mudança correspondente.
6. Se o modo paisagem virar requisito real (ex.: celular montado no carrinho), tratar a
   remoção do `android:screenOrientation="portrait"` fixo do `MainActivity` como mudança à
   parte — hoje a resiliência a paisagem descrita no `design.md` da tela operacional é teórica,
   porque a orientação está travada em retrato no manifest.

## Verificação e execução

Sempre execute dentro de `AgvPickVoice/`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew installDebug
```

A última verificação registrada para a prévia passou com **92 testes, 0 falhas**, além de
`assembleDebug` e `lintDebug`. Rode novamente após qualquer alteração; não trate essa
contagem como resultado da sua mudança.

Para configuração de bancada sem recompilar:

```bash
adb push ajustes-asr.properties /sdcard/Android/data/com.agvtronic.pickvoice/files/
adb push ajustes-visao.properties /sdcard/Android/data/com.agvtronic.pickvoice/files/
adb shell am force-stop com.agvtronic.pickvoice
```

Use os arquivos `ajustes-*.properties.exemplo` como ponto de partida. Não registre nem
suba credenciais de `local.properties`; o token de GitHub para resolver DAT permanece local.

## Onde encontrar o detalhe

- Especificação/decisões de cada entrega: `openspec/changes/add-*/{proposal,design,tasks}.md`.
- Contratos que a próxima implementação deve respeitar: `openspec/changes/add-*/specs/`.
- Estratégia, restrições da demo e decisões maiores: `DOC-TECNICA-AGV-PICK-VOICE-v1.5.md`.
- Instruções específicas de DAT e API atual: `AGENTS.md` na raiz; não inventar símbolos do SDK.
