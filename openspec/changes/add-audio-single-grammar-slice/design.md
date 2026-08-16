## Context

Ver proposal.md - Why. Ponto de partida técnico:

- Não existia nenhuma dependência de ASR neste checkout antes desta mudança. A superfície do Vosk foi verificada com `javap` sobre o `.aar` baixado, mesmo método usado na mudança anterior sobre o SDK do DAT — ver "Verificação da API do Vosk" no fim deste documento. Três coisas divergiram do que este design assumia originalmente, e as decisões abaixo já refletem o que foi verificado.
- `PickingEvent.ComandoParar` e `PickingEvent.ComandoRepetir` já existem e já são tratados por `reduzirTransversal` em `PickingReducer.kt`: ambos só produzem efeito quando `state.ehOperacional` é verdadeiro; fora disso o evento cai para `reduzirFluxoPrincipal`, que não o trata para aqueles estados, e o reducer devolve o estado inalterado por construção (comentário do próprio arquivo: "um comando de voz mal reconhecido chegando fora de hora é ruído esperado no chão de armazém, não uma condição excepcional"). Ou seja, é seguro escutar continuamente desde a inicialização do app, sem gatear por estado.
- `AppContainer` já tem o marcador `TODO(#audio-source-abstraction)` e o `appScope` (`Dispatchers.Default`) e `datScope` (`Dispatchers.Main`) existentes mostram a convenção do projeto: cada fonte de I/O ganha seu próprio escopo/dispatcher, nunca compartilha o dispatcher do ator.

## Goals / Non-Goals

**Goals:**
- Provar que um comando de voz real (via microfone do celular) produz o mesmo `PickingEvent` transversal que hoje só o painel de dev dispara — a fatia mais fina possível do pipeline de áudio do doc §5.
- Isolar toda a superfície do Vosk (não thread-safe) numa única thread dedicada, nunca tocada pela coroutine do ator nem pela UI — restrição de arquitetura do projeto, não específica desta fatia, mas primeira vez que passa a valer de verdade.
- Deixar `FonteAudio` pronta como ponto de injeção único, para que a troca por `AudioHfpOculos` no dia do evento (doc §13.3) seja restrita à implementação da fonte, sem tocar no reconhecedor.

**Non-Goals:**
- VAD Silero (ONNX Runtime) — doc §5 descreve como parte do pipeline final, mas é esforço de integração próprio (carregar modelo ONNX, janela de 256 amostras) que pertence ao Marco 2 (doc §13.1: "profundidade por componente"). Esta fatia usa o endpointer que o próprio Vosk já traz (ver Decisão 2).
- Troca de gramática por estado (doc §4.2, §5.1 perfis `DIGITOS`/`TEXTO_LIVRE`) — a gramática desta fatia é única, fixa e nunca recriada; só o Marco 2 introduz a recriação de `Recognizer` por transição de estado.
- Reranking contra a expectativa do mock (doc §5, última etapa do diagrama) — não há expectativa a rerankar quando a gramática já é um conjunto fechado de duas palavras.
- Saída por TTS (doc §5.4) — fora de escopo; a confirmação do comando reconhecido continua sendo apenas a transição de estado observada no painel de dev.
- `AudioHfpOculos` — interface satisfeita, implementação não criada aqui (doc §13.3 trata isso como a troca do dia do evento).

## Decisions

1. **Gramática fixa e estática (`["parar", "repetir"]`) construída uma única vez.** Vosk aceita um `Recognizer(model, sampleRate, grammarJson)` restrito a uma lista fechada de palavras, o que aumenta a precisão de reconhecimento e evita decodificar contra o vocabulário completo do modelo — desnecessário quando só dois comandos importam nesta fatia. Alternativa rejeitada: `Recognizer` de vocabulário aberto com parsing posterior do texto reconhecido — mais trabalho de parsing (doc §5, etapa "parse pt-BR") sem benefício nenhum para só duas palavras.

2. **Endpointing embutido do Vosk, não um endpointer próprio** (decisão revisada após verificar a API). A intenção original era escrever um `EndpointerPorEnergia` em Kotlin puro — janela deslizante de RMS fechando a elocução após 280 ms abaixo de um limiar — porque se assumia que o Vosk só decodificava, sem detectar fim de fala. O `javap` mostrou o contrário: `Recognizer.setEndpointerDelays(t_start_max, t_end, t_max)` configura o endpointer em segundos, e `acceptWaveForm()` **retornando `true` já é o sinal de fim de elocução** (é assim que o próprio `SpeechService` do Vosk funciona). Com isso o perfil `COMANDO_CURTO` do doc §5.1 vira `t_end = 0.28f`, e os perfis `DIGITOS` (0.70f) e `TEXTO_LIVRE` (0.90f) do Marco 2 passam a ser outro valor de configuração em vez de lógica nova. Além de menos código, é mais robusto: o endpointer do Vosk decide sobre o modelo acústico (conhece fonemas de silêncio), enquanto um limiar de RMS confunde silêncio com fala baixa em galpão ruidoso — exatamente o ambiente do projeto. Nada disso muda o contrato observável da spec, que não amarra mecanismo de endpointing.

3. **Escuta contínua desde a inicialização do app, sem gate por estado da sessão.** Já que o reducer trata `ComandoParar`/`ComandoRepetir` fora de estado operacional como no-op (ver Context), o reconhecedor liga assim que o modelo carrega e a permissão `RECORD_AUDIO` é concedida — não espera `SessaoPreparada`. Alternativa rejeitada: gatear a escuta pelo estado do `PickingActor` — adicionaria acoplamento (o componente de áudio precisaria observar o estado do ator só para decidir se escuta) sem nenhum ganho de comportamento, já que o próprio reducer já absorve o caso.

4. **Modelo carregado no `init` do `AppContainer`, em thread própria, em paralelo à sessão DAT.** Segue o doc §5.3 ("carregar na inicialização do app, não ao criar a sessão"). Como o carregamento do `Model` é I/O de disco que pode levar alguns segundos, ele roda na mesma thread dedicada de áudio que depois hospeda o `Recognizer`, nunca bloqueando a `MainActivity` nem a corrotina de registro DAT (`datScope`, `Dispatchers.Main`) — os dois processos de inicialização (sessão DAT e modelo de voz) são concorrentes e independentes.

5. **Modelo `vosk-model-small-pt-0.3` (51 MB descompactado) embutido em `assets/` e versionado no git.** Alternativa rejeitada: baixar o modelo em tempo de execução — o doc não permite dependência de rede para esta camada e adicionaria uma falha de inicialização nova (sem internet no chão de armazém). Também rejeitadas, por decisão explícita do Matheus: manter o modelo fora do git com script de setup, ou baixá-lo por task do Gradle no primeiro build. Ambas deixam o repositório leve, mas trocam 51 MB de histórico por um passo que pode falhar na manhã de 18/09 (Wi-Fi do evento, cache do Gradle limpo, dev que esqueceu de rodar o script). Num projeto onde o doc §13.3 exige que a única mudança daquela manhã seja o device selector e a `FonteAudio`, um `git clone` que já compila vale os 51 MB. O único outro modelo pt publicado tem 1,69 GB e está fora de questão.

6. **Calibração por arquivo de propriedades no aparelho, não por constantes recompiladas.** A primeira rodada de verificação com voz humana (tarefa 5.2) mostrou reconhecimento intermitente, e as hipóteses candidatas — endpoint curto demais, AEC atenuando o sinal, degradação de canal agressiva demais para o modelo pequeno, nível baixo — só se distinguem por tentativa. Com o APK de debug em 127 MB, cada `installDebug` custa perto de um minuto, e testar uma dúzia de combinações por recompilação consumiria a maior parte do tempo de bancada que resta. `AjustesAsr` lê um `ajustes-asr.properties` opcional do `getExternalFilesDir` (o mesmo diretório para onde o `StorageService` já copia o modelo, portanto gravável por `adb push` sem root), e um ciclo vira `push` + `force-stop`. Os defaults do `data class` são exatamente os valores de produção, então o arquivo ausente — inclusive na manhã de 18/09 — não muda comportamento nenhum. Alternativa rejeitada: expor os controles no painel de dev — mais código de UI para uma necessidade de bancada, e o painel não deveria crescer, já que o doc §12 prevê substituí-lo pela tela espelho.

7. **Observabilidade do ASR como parte do componente, não como instrumentação descartável.** As duas falhas mais caras desta fatia (`VOICE_COMMUNICATION` devolvendo silêncio digital, escala `±1.0` em vez de `±32767`) tinham o mesmo sintoma observável — "falo e nada acontece" — e nenhum rastro no log. Pior: o `publicar` original devolvia cedo em texto vazio *antes* de logar, então onze minutos de escuta produziam zero linhas, e não havia como distinguir "microfone mudo" de "endpointer nunca fechou" de "reconheceu `[unk]`". Agora o componente loga três coisas: nível do sinal em dBFS uma vez por segundo (`MedidorDeNivel`), a hipótese parcial a cada mudança, e — em `W` — a elocução que fecha sem texto final *quando havia um parcial*, que é a assinatura exata de endpoint cedo demais. O caso comum de elocução vazia por silêncio continua sem log, senão o logcat receberia uma linha a cada `silencioAntesDaFala`. Isso é registro permanente, não andaime: o doc §10 exige um plano de calibração, e calibrar exige ver o que o ASR ouviu, não só o que ele publicou.

8. **AEC deixa de ser ligado por padrão.** A versão original ligava o `AcousticEchoCanceler` sempre que o aparelho oferecia, antecipando o TTS do doc §5.4. Mas o AEC cancela o que está sendo tocado a partir de uma referência de playback, e enquanto não existe TTS não há nada para cancelar — sobra um estágio a mais no caminho do sinal, projetado para sessões `VOICE_COMMUNICATION`, rodando sobre uma sessão `VOICE_RECOGNITION`. Passou a ser opt-in por `AjustesAsr.cancelamentoDeEco`, e volta a ser padrão quando a saída por voz existir.

9. **Falha ao obter `RECORD_AUDIO` é não fatal: o reconhecedor simplesmente não inicia.** Mesma postura de degradação graciosa já usada para a ausência de `BLUETOOTH_CONNECT` na mudança da sessão DAT, mas aqui sem publicar nenhum evento de falha — não existe hoje um `PickingEvent` de "áudio indisponível" no domínio, e criar um sairia do escopo desta fatia (nenhum evento novo, conforme proposal.md - What Changes). O app continua operável só por toque no painel de dev.

## Risks / Trade-offs

- **[Resolvido]** A superfície do Vosk não estava verificada quando este design foi escrito. Foi verificada com `javap` antes de qualquer código de integração — ver a seção final. Três suposições caíram; as Decisões 1, 2 e 5 já refletem o que existe de verdade.
- **[Risco]** O endpointer do Vosk é uma caixa-preta comparado a um limiar de RMS próprio: se ele fechar elocução cedo demais em galpão ruidoso, o ajuste disponível é `t_end`, não o algoritmo. → **Mitigação**: é o mesmo endpointer que o doc §5.1 já pressupõe ao definir os perfis em milissegundos, e o valor é configurável em uma linha; se a calibração do Marco 3 mostrar que não basta, o Silero VAD do doc §5 entra na frente dele sem mudar nada do que esta fatia publica.
- **[Risco]** Tamanho do APK e do repositório crescem 51 MB com o modelo embutido nos assets. → **Mitigação**: escolha deliberada do menor modelo pt publicado e decisão explícita de versionar (Decisão 5); sem impacto funcional, só de tempo de build/instalação e de `clone`.
- **[Risco]** Duas inicializações concorrentes (sessão DAT em `datScope`, modelo Vosk em thread de áudio própria) competem por I/O/CPU no início do app. → **Mitigação**: ambas já eram assíncronas e não bloqueantes antes desta mudança; monitorar tempo de partida na verificação em bancada (doc §13.3 não permite fricção na manhã do evento) e mover o carregamento do modelo para depois do primeiro frame se necessário.

## Verificação da API do Vosk

Feita com `javap` sobre o `classes.jar` do `.aar`, antes de escrever qualquer integração. O que divergiu do assumido:

| Assumido no plano | Verificado |
|---|---|
| Artefato `org.vosk:vosk-android` | **`com.alphacephei:vosk-android`** no Maven Central. O pacote *Java* é que é `org.vosk` — daí a confusão. Puxa `net.java.dev.jna:jna` como dependência transitiva. |
| Versão mais recente `0.3.47` | **`0.3.75`**. O índice de busca do Maven Central reporta `0.3.47` como `latestVersion`, mas o `maven-metadata.xml` lista até `0.3.75` — o índice está velho. Confiar no metadata, não na busca. |
| Vosk só decodifica; endpointing é por nossa conta | `Recognizer` tem `setEndpointerMode(int)` (`DEFAULT`/`SHORT`/`LONG`/`VERY_LONG`) e `setEndpointerDelays(float, float, float)`, e `acceptWaveForm()` devolve `true` no fim da elocução. Ver Decisão 2. |

Assinaturas que a implementação usa, confirmadas:

- `Model(String path)` — caminho de **sistema de arquivos**, não de asset. Lança `IOException`.
- `Recognizer(Model, float sampleRate, String grammar)` — o construtor com gramática existe; a gramática é um array JSON de palavras.
- `Recognizer.acceptWaveForm(float[], int)` existe, então a `FonteAudio` do doc §5.2 entrega `FloatArray` sem conversão intermediária. **Armadilha**: o Vosk espera as amostras na escala de `int16` (±32767), não normalizadas em ±1.0 — ver o KDoc de `FonteAudio`, que fixa o contrato normalizado, e a conversão no `ReconhecedorDeComando`.
- `StorageService.sync(Context, String assetDir, String targetDir): String` copia o modelo de `assets/` para `getExternalFilesDir()` e devolve o caminho para o `Model`. Ele exige um arquivo **`uuid`** dentro do diretório do modelo nos assets: é o que ele compara para decidir se precisa recopiar. Sem esse arquivo, `sync` lança `IOException` — e ele não vem no zip oficial do modelo, tem que ser criado.

## Verificação em bancada

Feita no Galaxy S20 FE (SM-G780F) de desenvolvimento. O reconhecimento não funcionou de primeira, e separar as causas exigiu alimentar o Vosk com um WAV limpo gerado por TTS, sem passar pelo microfone. A matriz que isolou cada variável:

| Fonte | Taxa | Escala entregue ao Vosk | Resultado |
|---|---|---|---|
| Microfone, `VOICE_COMMUNICATION` | 8 kHz | int16 | Pico de 0,0001 — silêncio digital |
| Microfone, `VOICE_RECOGNITION` | 8 e 16 kHz | int16 | Sinal real (0,04), sem reconhecer |
| Arquivo limpo | 16 kHz | normalizada `±1.0` | Sem reconhecer |
| Arquivo limpo | 16 kHz | `±32767` | **Reconheceu** |
| Arquivo limpo | 8 kHz degradado | `±32767` | **Reconheceu** |

Três conclusões:

1. **`MediaRecorder.AudioSource.VOICE_COMMUNICATION` devolve silêncio neste aparelho.** `AudioRecord` inicializa, `read` devolve amostras, nenhum erro aparece no log — e o sinal é zero. O doc §5 pede essa fonte, mas ela parece depender de uma rota de voz ativa que só existe em chamada. `AudioMicrofoneSimulado` usa `VOICE_RECOGNITION`; ver o KDoc da classe. O `AudioHfpOculos` provavelmente vai precisar de `VOICE_COMMUNICATION` de verdade, porque lá existe rota HFP — quem implementar aquela fatia deve testar essa hipótese antes de assumir.
2. **A escala `±32767` é obrigatória**, confirmando o que o `javap` sugeria: entregar `float[]` normalizado em `±1.0` não dá erro nenhum, só faz o decodificador não reconhecer absolutamente nada. É a armadilha mais cara desta fatia, porque não deixa rastro no log.
3. **A degradação para 8 kHz não atrapalha o reconhecimento**, apesar de o modelo declarar `--sample-frequency=16000` e `--high-freq=7600` no `mfcc.conf`. O Kaldi reamostra internamente (`--allow-upsample=true`), e o mesmo áudio limpo foi reconhecido nas duas taxas. Isso valida a premissa central do doc §5.2: dá para desenvolver contra o canal degradado sem perder o pipeline.

**Limitação do que foi verificado automaticamente:** tocar TTS pelo alto-falante do Mac na frente do aparelho produz só cerca de -30 dBFS no microfone, fraco demais para o ASR decidir. A validação de ponta a ponta com voz humana direta no aparelho (tarefas 5.2 e 5.3) não é automatizável dessa forma e ficou para o operador.

### Segunda rodada: reconhecimento intermitente com voz humana

O que a primeira tentativa de 5.2/5.3 com voz humana produziu, e o que se aprendeu antes de mudar qualquer parâmetro:

1. **A palavra testada não estava na gramática.** O operador testou dizendo "confirma", que é do perfil `CONFIRMACAO` do doc §5.1 e pertence ao Marco 2 — esta fatia decodifica exclusivamente `["parar", "repetir", "[unk]"]`. As poucas vezes em que "algo aconteceu" foram o decodificador colapsando "confirma" em "parar". Não é um defeito do pipeline; é a explicação inteira do "às vezes funciona, às vezes preciso repetir três vezes". Registrado aqui porque o mesmo engano vai reaparecer a cada fatia que mexer na gramática: **o conjunto de palavras aceitas agora aparece na linha `Escutando` do logcat**, justamente para poder ser conferido antes de culpar o reconhecimento.
2. **O teste pelo alto-falante do Mac era inválido por um segundo motivo.** Além dos -30 dBFS já registrados acima, o Mac estava com fone conectado, então o áudio nunca chegou ao microfone do aparelho. Esse caminho de verificação foi abandonado: 5.2/5.3 só valem com voz humana direta no aparelho.
3. **Não havia como investigar sem instrumentar.** Ver Decisão 7. Onze minutos de escuta contínua produziram zero linhas de log.

Linha de base do sinal medida depois de instrumentar, no mesmo Galaxy S20 FE, com `VOICE_RECOGNITION`, sem AEC, após a degradação para 8 kHz — sala silenciosa, ninguém falando: `rms=-62,7 dBFS pico=-34,0 dBFS`. Ou seja, **o microfone entrega sinal**; o problema nunca foi captura. É contra esta linha de base que qualquer medida de fala deve ser comparada daqui em diante.

### Terceira rodada: os quatro suspeitos caíram

Com a instrumentação no lugar, a bateria de voz humana (tarefa 6.7) foi conclusiva: **9 comandos falados, 9 reconhecidos na primeira tentativa, nenhum falso positivo.**

| Pico da elocução | Parcial | Texto final | Evento |
|---|---|---|---|
| -9,4 dBFS | `parar` | `parar` | `ComandoParar` |
| -23,4 | `repetir` | `repetir` | `ComandoRepetir` |
| -17,2 | `parar` | `parar` | `ComandoParar` |
| -20,0 | `parar` | `parar` | `ComandoParar` |
| -22,8 | `repetir` | `repetir` | `ComandoRepetir` |
| -24,4 | `repetir` | `repetir` | `ComandoRepetir` |
| -23,6 | `repetir` | `repetir` | `ComandoRepetir` |
| -26,8 | `repetir` | `repetir` | `ComandoRepetir` |
| -23,9 | `repetir` | `repetir` | `ComandoRepetir` |

Latência da parcial ao texto final: 460–770 ms, coerente com os 280 ms de `t_end` mais a decodificação.

**Nenhum dos quatro parâmetros suspeitos precisou mudar, e cada um caiu por evidência própria:**

- **`t_end` de 280 ms** — nenhuma parcial truncada e nenhum aviso de "elocução fechada sem texto" em nove elocuções. O perfil `COMANDO_CURTO` do doc §5.1 está calibrado para o que ele promete.
- **AEC** — desligado nesta rodada (Decisão 8), com o melhor sinal já medido no projeto. Sem motivo para religar antes do TTS.
- **`ganho`** — desnecessário: -9 dBFS de pico é sinal forte, e ganho não melhoraria relação sinal/ruído de todo modo.
- **Degradação para 8 kHz** — inocente, e agora com a evidência mais forte que existia até aqui: o modelo pequeno reconheceu 9/9 *através* do canal telefônico, com voz humana real. Confirma a premissa central do doc §5.2 muito melhor do que o áudio limpo de TTS da rodada anterior.

**O que explica a intermitência original, então, são duas coisas e nenhuma delas é o pipeline:** a palavra testada não existia na gramática (ver item 1 acima) e a distância ao aparelho.

**O limiar de energia é o achado aproveitável desta rodada.** Tudo acima de **-27 dBFS de pico** decodificou; nada abaixo de **-30 dBFS** produziu sequer uma hipótese parcial, incluindo doze minutos de ambiente e conversa a picos de até -29 dBFS. O piso da sala fica em torno de -52 dBFS de pico. Isso dá uma margem operacional confortável em bancada — o celular precisa estar a um palmo de quem fala — e deve deixar de importar no óculos, onde o microfone HFP fica na haste, a centímetros da boca. **Quem implementar o `AudioHfpOculos` deve medir esse limiar de novo**: é a comparação mais direta entre os dois caminhos de áudio, e o `MedidorDeNivel` já está no caminho para produzi-la sem trabalho novo.

**Ressalva sobre a rejeição de "confirma":** as duas elocuções de "confirma" saíram 15 a 25 dB mais fracas que os comandos bem-sucedidos. Elas não dispararam nada, que é o comportamento exigido pela spec, mas o dado não separa "rejeitada por estar fora da gramática" de "fraca demais para ser decodificada". A rejeição por gramática segue apoiada no `[unk]` (ver o KDoc de `GRAMATICA`) e nos doze minutos de ambiente sem nenhum evento, não neste par de elocuções.
