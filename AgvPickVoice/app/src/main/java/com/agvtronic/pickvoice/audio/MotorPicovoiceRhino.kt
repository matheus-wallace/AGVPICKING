package com.agvtronic.pickvoice.audio

import ai.picovoice.rhino.Rhino
import android.content.Context
import android.util.Log
import com.agvtronic.pickvoice.BuildConfig

/**
 * O [MotorDeAsr] do Picovoice Rhino — **fala-para-intenção**, não fala-para-texto.
 *
 * ### O que muda em relação aos outros dois motores
 *
 * Vosk e sherpa-onnx decodificam texto e entregam o que ouviram; o [InterpretadorDeFala] decide
 * depois se aquilo é comando. O Rhino já decide: um contexto `.rhn`, compilado no Picovoice
 * Console, define as expressões aceitas, e o runtime devolve `isUnderstood` + `intent` + `slots`.
 * Fala fora do contexto sai como `isUnderstood = false`, sem hipótese nenhuma.
 *
 * Essa saída **não** atravessa a fronteira do motor. O [SintetizadorDeIntencaoRhino] a converte em
 * texto e o resto do pipeline segue sem saber que o motor pensava em intenções
 * (add-picovoice-asr-engine - Decisão 1). Ver o KDoc daquele objeto para o porquê.
 *
 * ### Dois contextos pré-carregados
 *
 * `setContextPath` é parâmetro de construção, então o motor constrói uma instância para comandos
 * e outra para quantidades durante [carregar]. [ConfiguracaoDeEscuta.contextoRhino] escolhe qual
 * delas recebe áudio; nunca se processa o mesmo quadro nas duas. A sessão e ambos os engines são
 * manipulados somente pela thread `audio-asr` do [ReconhecedorDeComando].
 *
 * [abrirSessao] ignora [ConfiguracaoDeEscuta.palavras], porque o vocabulário já está compilado nos
 * contextos; usa apenas [ConfiguracaoDeEscuta.contextoRhino] para escolher o engine.
 *
 * ### Três coisas que a verificação da API impôs a este código
 *
 * A API foi conferida no `.aar` e no `-sources.jar` de `ai.picovoice:rhino-android:4.0.2` baixados
 * do Maven Central, não na documentação do produto — mesma disciplina do sherpa-onnx.
 *
 * 1. **Reamostragem obrigatória.** `Rhino.getSampleRate()` devolve 16 kHz e `process` exige
 *    exatamente [Rhino.getFrameLength] amostras `short` por chamada; a [FonteAudio] entrega
 *    8 kHz em janelas de 512 amostras `float`. Cada sessão tem seu [ReamostradorLinear] e um
 *    acumulador que fatia o fluxo em quadros do tamanho certo — tamanho errado não é ignorado,
 *    é `RhinoInvalidArgumentException`.
 * 2. **Escala de int16, como o Vosk e ao contrário do sherpa-onnx.** `process(short[])` quer PCM
 *    16 bits; a [FonteAudio] entrega `-1.0..1.0`. Sem a conversão não haveria erro, haveria
 *    silêncio.
 * 3. **`reset()` depois de cada inferência.** O `pv_rhino_reset` do demo oficial em C é o que
 *    permite a escuta contínua: sem ele o motor fica preso na inferência já concluída e nunca
 *    fecha a elocução seguinte. O `RhinoManager` do próprio SDK não chama `reset` porque ele
 *    **para de gravar** depois do primeiro resultado — o que não serve a um fluxo de picking.
 *
 * ### Falha de carga degrada, não derruba
 *
 * Ao contrário do sherpa-onnx, aqui não é preciso conferir a existência dos assets antes: o
 * `Rhino.Builder` abre o asset pelo `AssetManager` e um arquivo ausente vira `RhinoIOException`,
 * que é `Exception` comum e o `runCatching` pega. Isso vale inclusive para o caso de hoje — o
 * contexto `.rhn` **ainda não existe** —, e o efeito é o contrato do [MotorDeAsr.carregar]: `false`,
 * voz desligada, app inteiro pelo painel de dev.
 *
 * ### Confinamento de thread
 *
 * Cada instância do `Rhino` segura ponteiro nativo e não é thread-safe, como
 * `Model`/`Recognizer` do Vosk e as sessões do ONNX Runtime. Tudo aqui roda na thread única de
 * áudio do [ReconhecedorDeComando]; nenhuma sincronização é necessária e nenhuma é feita.
 *
 * @param appContext contexto de aplicação — o `Rhino` vive além de qualquer `Activity`, e o
 *   `Builder` precisa dele para extrair modelo e contexto de `assets/` para o armazenamento
 *   interno.
 * @param ajustes calibração de bancada; ver [AjustesAsr]. **Nada aqui foi medido** — nem a
 *   sensibilidade, que fica no default do próprio SDK.
 */
class MotorPicovoiceRhino(
    private val appContext: Context,
    private val ajustes: AjustesAsr = AjustesAsr(),
) : MotorDeAsr {

  override val nome: String = "picovoice-rhino"

  /** Confinados na thread de áudio. Dois ponteiros nativos, vivos pela vida do processo. */
  private var rhinoPrincipal: Rhino? = null
  private var rhinoQuantidade: Rhino? = null

  /** Último contexto que recebeu áudio; usado apenas para logar transições reais. */
  private var contextoAtivo = TipoContextoRhino.PRINCIPAL

  /**
   * Constrói as duas instâncias do `Rhino` com o modelo pt-BR, uma vez.
   *
   * A `AccessKey` vem do `BuildConfig`, gerada a partir de `local.properties` no
   * `build.gradle.kts` (add-picovoice-asr-engine - Decisão 3). Ela **nunca** é logada: só a
   * presença dela aparece, porque um motor que não carrega por falta de chave e um que não carrega
   * por falta de contexto são dois problemas diferentes de bancada.
   */
  override fun carregar(): Boolean {
    if (rhinoPrincipal != null && rhinoQuantidade != null) return true

    if (BuildConfig.PICOVOICE_ACCESS_KEY.isEmpty()) {
      Log.e(TAG, "picovoiceAccessKey ausente em local.properties; voz desligada")
      return false
    }

    val inicio = System.currentTimeMillis()
    var principal: Rhino? = null
    var quantidade: Rhino? = null

    val carregou =
        runCatching {
              val novoPrincipal =
                  construirRhino(CONTEXTO_PRINCIPAL, duracaoDeEndpointSegundos())
              principal = novoPrincipal
              val novaQuantidade = construirRhino(CONTEXTO_QUANTIDADE, ENDPOINT_QUANTIDADE_S)
              quantidade = novaQuantidade
              rhinoPrincipal = novoPrincipal
              rhinoQuantidade = novaQuantidade

              Log.i(
                  TAG,
                  "Rhino ${novoPrincipal.version} carregado em " +
                      "${System.currentTimeMillis() - inicio}ms " +
                      "(${novoPrincipal.sampleRate}Hz, quadro=${novoPrincipal.frameLength}, " +
                      "contextos=[PRINCIPAL endpoint=${duracaoDeEndpointSegundos()}s, " +
                      "QUANTIDADE endpoint=${ENDPOINT_QUANTIDADE_S}s], " +
                      "intenções=${SintetizadorDeIntencaoRhino.INTENCOES.keys})",
              )
            }
            .onFailure { erro ->
              principal?.delete()
              quantidade?.delete()
              rhinoPrincipal = null
              rhinoQuantidade = null
              Log.e(TAG, "Falha ao carregar os contextos Rhino; voz desligada", erro)
            }
            .isSuccess

    return carregou
  }

  private fun construirRhino(caminhoDoContexto: String, endpointSegundos: Float): Rhino =
      Rhino.Builder()
          .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
          // Sem esta linha o SDK usaria o modelo inglês empacotado no .aar.
          .setModelPath(MODELO)
          .setContextPath(caminhoDoContexto)
          .setSensitivity(SENSIBILIDADE)
          .setEndpointDurationSec(endpointSegundos)
          .setRequireEndpoint(true)
          .build(appContext)

  /**
   * Abre a escuta usando o engine pré-carregado pedido pelo estado. O chamador executa este
   * método entre janelas, na thread de áudio, depois de fechar a sessão anterior.
   */
  override fun abrirSessao(configuracao: ConfiguracaoDeEscuta, sampleRate: Int): SessaoDeAsr? {
    val contexto = configuracao.contextoRhino
    val carregado =
        when (contexto) {
          TipoContextoRhino.PRINCIPAL -> rhinoPrincipal
          TipoContextoRhino.QUANTIDADE -> rhinoQuantidade
        } ?: return null

    // O novo contexto começa sem qualquer inferência parcial anterior. Também limpa a sessão
    // principal ao mudar apenas de estado dentro do mesmo contexto.
    val reiniciado =
        runCatching { carregado.reset() }
            .onFailure { Log.e(TAG, "Falha ao iniciar RhinoContext=$contexto", it) }
            .isSuccess
    if (!reiniciado) return null

    if (contexto != contextoAtivo) {
      Log.i(TAG, "RhinoContext $contextoAtivo -> $contexto")
      contextoAtivo = contexto
    }

    return SessaoRhino(
        rhino = carregado,
        contexto = contexto,
        reamostrador = ReamostradorLinear(sampleRate, carregado.sampleRate),
    )
  }

  /**
   * O silêncio que fecha a elocução, em segundos e **dentro do que o SDK aceita**.
   *
   * Aqui o perfil por estado se perde, e é consequência direta da Decisão 2: `endpointDurationSec`
   * é parâmetro de construção do `Rhino`, então honrar [PerfilEndpoint] por estado exigiria
   * recriar a instância a cada transição — exatamente o custo que a decisão recusou. O motor usa
   * o perfil **mais longo em uso** ([PerfilEndpoint.DIGITOS]), porque errar para o lado longo faz
   * o operador esperar e errar para o lado curto come dígito (doc §5.1).
   *
   * A faixa que o `Builder` aceita é `[0.5, 5.0]` segundos — fora dela ele lança
   * `RhinoInvalidArgumentException`. Os 280 ms do [PerfilEndpoint.COMANDO_CURTO] **não cabem**, e
   * é por isso que o `coerceIn` existe: sem ele, um `silencioFinalMs` calibrado para o Vosk
   * derrubaria a carga deste motor em vez de aproximá-la.
   */
  private fun duracaoDeEndpointSegundos(): Float {
    val padrao = PerfilEndpoint.COMANDO_CURTO.silencioFinalMs
    val ms = if (ajustes.silencioFinalMs == padrao) PerfilEndpoint.DIGITOS.silencioFinalMs
    else ajustes.silencioFinalMs
    return (ms / MS_POR_SEGUNDO).coerceIn(ENDPOINT_MINIMO_S, ENDPOINT_MAXIMO_S)
  }

  /**
   * Uma escuta viva: reamostra, fatia em quadros e entrega ao `Rhino` até ele fechar a elocução.
   *
   * O `Rhino` é **compartilhado** e pertence ao motor — [close] limpa o estado dele mas não o
   * libera, porque o próximo estado vai reusá-lo. Mesmo arranjo do `Model` do Vosk e do `Vad` do
   * sherpa-onnx.
   */
  private class SessaoRhino(
      private val rhino: Rhino,
      private val contexto: TipoContextoRhino,
      private val reamostrador: ReamostradorLinear,
  ) : SessaoDeAsr {

    private val tamanhoDoQuadro = rhino.frameLength

    /**
     * Amostras já reamostradas e convertidas, ainda não entregues ao `Rhino`.
     *
     * Existe porque os dois lados são picados em tamanhos que não se dividem: a [FonteAudio]
     * entrega 512 amostras a 8 kHz (1023 ou 1024 depois de reamostrar, porque a fase não fecha em
     * múltiplo inteiro — ver [ReamostradorLinear]) e o `Rhino` exige `frameLength` exato por
     * chamada. Sem o acumulador, a sobra de cada janela seria descartada e o áudio chegaria ao
     * motor com buracos periódicos.
     */
    private var acumulado = ShortArray(0)

    override fun aceitar(janela: FloatArray): ResultadoDeAsr {
      acumulado += paraInt16(reamostrador.processar(janela))

      var consumidas = 0
      var fechada: ResultadoDeAsr? = null

      while (acumulado.size - consumidas >= tamanhoDoQuadro) {
        val quadro = acumulado.copyOfRange(consumidas, consumidas + tamanhoDoQuadro)
        consumidas += tamanhoDoQuadro

        val concluiu =
            runCatching { rhino.process(quadro) }
                .onFailure { Log.e(TAG, "Falha ao processar quadro; descartado", it) }
                .getOrDefault(false)

        // Uma elocução por janela. O que sobrou fica no acumulador para a janela seguinte, 64 ms
        // depois — é o silêncio de endpoint, na prática, e o versionamento do PublicadorDeVoz
        // continua descartando o que ficar obsoleto.
        if (concluiu) {
          fechada = ResultadoDeAsr.Fechada(colherInferencia())
          break
        }
      }

      acumulado =
          if (consumidas == acumulado.size) VAZIO
          else acumulado.copyOfRange(consumidas, acumulado.size)

      // O Rhino não expõe hipótese em andamento: até `process` devolver `true` não há nada
      // decodificado, mesma situação do pipeline VAD-então-ASR do sherpa-onnx. O parcial fica
      // sempre vazio, e o [AjustesAsr.logParciais] não tem efeito neste motor.
      return fechada ?: ResultadoDeAsr.NADA
    }

    /**
     * Lê a inferência concluída, sintetiza o texto e **reinicia o motor**.
     *
     * O `reset` não é opcional: sem ele o `Rhino` fica preso na inferência já lida e nunca fecha
     * a próxima elocução. O `RhinoManager` do SDK não precisa disso porque para de gravar depois
     * do primeiro resultado; o demo oficial em C, que escuta em loop como este app, chama
     * `pv_rhino_reset` logo depois de ler a intenção.
     */
    private fun colherInferencia(): String {
      val texto =
          runCatching {
                val inferencia = rhino.inference
                val quantidade =
                    if (contexto == TipoContextoRhino.QUANTIDADE && inferencia.isUnderstood) {
                      InterpretadorDeQuantidadeRhino.interpretar(inferencia.slots)
                    } else null
                val sintetizado =
                    when (contexto) {
                      TipoContextoRhino.PRINCIPAL ->
                          SintetizadorDeIntencaoRhino.sintetizar(
                              entendido = inferencia.isUnderstood,
                              intencao = inferencia.intent,
                              slots = inferencia.slots,
                          )
                      TipoContextoRhino.QUANTIDADE -> quantidade?.toString().orEmpty()
                    }

                val quantidadeLog =
                    if (contexto == TipoContextoRhino.QUANTIDADE) {
                      " quantidadeInterpretada=${quantidade ?: "null"}"
                    } else ""
                Log.i(
                    TAG,
                    "RhinoContext=$contexto understood=${inferencia.isUnderstood} " +
                        "intent=${inferencia.intent.orEmpty()} slots=${inferencia.slots}" +
                        quantidadeLog,
                )
                sintetizado
              }
              .onFailure {
                Log.e(TAG, "Falha ao ler RhinoContext=$contexto; elocução descartada", it)
              }
              .getOrDefault("")

      runCatching { rhino.reset() }
          .onFailure { Log.e(TAG, "Falha ao reiniciar RhinoContext=$contexto", it) }
      return texto
    }

    override fun reiniciar() {
      runCatching { rhino.reset() }
          .onFailure { Log.e(TAG, "Falha ao reiniciar o Rhino", it) }
      reamostrador.reiniciar()
      acumulado = VAZIO
    }

    override fun close() {
      // Não libera o `Rhino`: ele é do motor e o próximo estado o reaproveita. Só o estado de
      // elocução é que não pode atravessar a troca de configuração de escuta.
      reiniciar()
    }

    /**
     * `-1.0..1.0` (contrato da [FonteAudio]) -> PCM 16 bits, que é o que `process` quer.
     *
     * O `coerceIn` importa: `-1.0` multiplicado pela escala já passa do fundo da faixa de `Short`
     * por uma unidade, e `toInt().toShort()` faria a conversão dar a volta — o pico negativo
     * viraria pico positivo, que é distorção audível bem no instante mais alto da fala.
     */
    private fun paraInt16(amostras: FloatArray): ShortArray =
        ShortArray(amostras.size) {
          (amostras[it] * ESCALA_INT16).coerceIn(MENOR_INT16, MAIOR_INT16).toInt().toShort()
        }
  }

  private companion object {
    const val TAG = "MotorPicovoiceRhino"

    /**
     * O modelo de idioma pt-BR, vendorizado (ver PROVENIENCIA.md do diretório). Caminho relativo a
     * `assets/`: o `Rhino.Builder` copia o asset para o armazenamento interno se ele ainda não
     * estiver lá.
     */
    const val MODELO = "modelo-picovoice-rhino/rhino_params_pt.pv"

    /**
     * O contexto principal compilado no Picovoice Console — comandos, check digit e a gramática
     * numérica original. Ele permanece inalterado para evitar regressões nos comandos.
     */
    const val CONTEXTO_PRINCIPAL = "contexto-picovoice/picovoice-pt.rhn"

    /** Contexto dedicado aos slots `a1`, `b1` e `c1` de quantidades entre 1 e 9999. */
    const val CONTEXTO_QUANTIDADE =
        "contexto-picovoice/AGVTRONIC_pt_android_v4_0_0_Quantidade.rhn"

    /**
     * Sensibilidade da inferência, `[0, 1]`. Mais alta erra menos por omissão e mais por
     * inferência indevida. **0,5 é o default do próprio SDK, não uma medição deste projeto** —
     * calibrá-la é tarefa de bancada, junto com o resto (grupo 6 do change).
     */
    const val SENSIBILIDADE = 0.5f

    /** A faixa que o `Rhino.Builder` aceita em `setEndpointDurationSec`; fora dela, ele lança. */
    const val ENDPOINT_MINIMO_S = 0.5f

    const val ENDPOINT_MAXIMO_S = 5.0f

    /** Baseline solicitada para o contexto dedicado de quantidade. */
    const val ENDPOINT_QUANTIDADE_S = 1.0f

    /** Os [AjustesAsr] falam em ms; o Rhino, em segundos. */
    const val MS_POR_SEGUNDO = 1_000f

    /** -1.0..1.0 (contrato da FonteAudio) -> PCM 16 bits (o que o Rhino processa). */
    const val ESCALA_INT16 = 32_767f

    const val MENOR_INT16 = -32_768f

    const val MAIOR_INT16 = 32_767f

    /** Compartilhado porque é imutável — evita alocar um array vazio a cada janela consumida. */
    val VAZIO = ShortArray(0)
  }
}
