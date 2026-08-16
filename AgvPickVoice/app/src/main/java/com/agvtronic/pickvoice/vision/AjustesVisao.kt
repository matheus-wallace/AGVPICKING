package com.agvtronic.pickvoice.vision

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/** Resolução do stream de câmera do doc §2.2, sem depender do enum do SDK. */
enum class QualidadeStream(val largura: Int, val altura: Int) {
  ALTA(720, 1280),
  MEDIA(504, 896),
  BAIXA(360, 640),
}

/**
 * Os formatos de código que a operação usa (doc §6.2, §15, e o campo `ean` do mock).
 *
 * Enum próprio em vez das constantes `Barcode.FORMAT_*` do ML Kit pelo mesmo motivo de
 * [QualidadeStream]: [AjustesVisao] é lido e testado sem Android nem SDK no caminho. A tradução
 * para o ML Kit acontece num lugar só, em `LeitorDeCodigo`.
 */
enum class FormatoCodigo {
  CODE_128,
  DATA_MATRIX,
  EAN_13,
}

/**
 * Os parâmetros de calibração do pipeline de visão, lidos de um arquivo opcional no aparelho —
 * mesmo mecanismo (e mesmas razões) de [com.agvtronic.pickvoice.audio.AjustesAsr].
 *
 * ### Por que não são constantes
 *
 * O doc §6.1 é explícito: "a distância vira parâmetro de configuração, não constante no código",
 * e a varredura de 15/20/25/30/40 cm do §10.2 é a primeira coisa a rodar na manhã de 18/09. Uma
 * varredura em que cada valor custa um `installDebug` de um APK de 127 MB não é varredura, é
 * desperdício de bancada. Um ciclo aqui é:
 *
 * ```
 * adb push ajustes-visao.properties /sdcard/Android/data/com.agvtronic.pickvoice/files/
 * adb shell am force-stop com.agvtronic.pickvoice   # os ajustes são lidos na inicialização
 * ```
 *
 * Sem o arquivo, valem os defaults, que são exatamente os valores de produção do doc (§6.3 para
 * o recorte, §8 para resolução e taxa de quadros).
 *
 * @property fatorRecorte fração central do quadro retida antes de qualquer decodificação (doc
 *   §6.3). 60% e não menos porque o descarte é precoce: com o quadro completo já liberado, não
 *   existe segunda tentativa com outro recorte sobre o mesmo frame.
 * @property qualidade resolução do stream. `MEDIA` (504×896) é o que o doc §8 fixa — resolução
 *   menor rende **mais** qualidade por frame, porque a compressão se adapta à banda do Bluetooth
 *   (doc §2.2).
 * @property fps taxa de quadros do stream. 7 pelo mesmo motivo do item acima, mais bateria.
 * @property rotacaoGraus rotação informada ao leitor de código, em 0/90/180/270. Existe porque a
 *   orientação do frame que chega do óculos não é conhecida hoje: no MockDeviceKit ela depende de
 *   como o feed foi configurado, e descobrir isso por recompilação seria queimar bancada.
 * @property formatos formatos aceitos pelo leitor. Restringir é recomendação do próprio ML Kit
 *   para velocidade, e são estes três que a operação usa (doc §6.2, §15).
 * @property confirmacoesDeLeitura quantos frames consecutivos precisam devolver o **mesmo**
 *   código antes de ele valer como leitura. Nasceu de falso positivo medido em bancada — ver
 *   [ConsensoDeLeitura]. `1` desliga a proteção e restaura o comportamento de publicar na
 *   primeira leitura; existe como ajuste porque é o parâmetro que troca latência por segurança,
 *   e a calibração do doc §10 precisa poder varrer os dois lados.
 * @property logTentativas registra cada tentativa de decodificação com os campos do doc §4.5.
 *   Ligado por padrão: é o insumo da calibração do §10, e a fatia de áudio já mostrou o custo de
 *   descobrir tarde que não havia nada no logcat para explicar uma falha.
 */
data class AjustesVisao(
    val fatorRecorte: Float = 0.60f,
    val qualidade: QualidadeStream = QualidadeStream.MEDIA,
    val fps: Int = 7,
    val rotacaoGraus: Int = 0,
    val formatos: List<FormatoCodigo> = FORMATOS_PADRAO,
    val confirmacoesDeLeitura: Int = 2,
    val logTentativas: Boolean = true,
) {

  companion object {
    private const val TAG = "AjustesVisao"

    /** Nome do arquivo dentro de `getExternalFilesDir(null)`. */
    const val NOME_ARQUIVO = "ajustes-visao.properties"

    val FORMATOS_PADRAO: List<FormatoCodigo> =
        listOf(FormatoCodigo.CODE_128, FormatoCodigo.DATA_MATRIX, FormatoCodigo.EAN_13)

    private val ROTACOES_VALIDAS = setOf(0, 90, 180, 270)

    /**
     * Lê os ajustes do arquivo no diretório externo do app, caindo para os defaults em qualquer
     * problema.
     *
     * Chamado na construção do `AppContainer`, na main thread — é um arquivo de poucas linhas, e
     * precisa ser síncrono porque a resolução e a taxa de quadros já entram na
     * `StreamConfiguration` da primeira câmera que subir.
     */
    fun carregar(appContext: Context): AjustesVisao =
        carregarDe(File(appContext.getExternalFilesDir(null), NOME_ARQUIVO)) { mensagem ->
          Log.w(TAG, mensagem)
        }.also { Log.i(TAG, "Ajustes de visão em uso: $it") }

    /**
     * A leitura de verdade, sem nada de Android no caminho para poder ser exercitada por teste
     * de unidade comum (o `Log` entra por [avisar], que o teste substitui).
     *
     * Uma chave desconhecida ou um valor inválido não derruba nada: o campo correspondente
     * mantém o default e o arquivo inteiro continua valendo para os outros.
     */
    fun carregarDe(arquivo: File, avisar: (String) -> Unit = {}): AjustesVisao {
      if (!arquivo.exists()) return AjustesVisao()

      val propriedades =
          runCatching { Properties().apply { arquivo.inputStream().use { load(it) } } }
              .onFailure { avisar("Falha ao ler ${arquivo.name}; usando os padrões: ${it.message}") }
              .getOrNull() ?: return AjustesVisao()

      val padrao = AjustesVisao()
      return AjustesVisao(
          fatorRecorte =
              propriedades
                  .decimal("fatorRecorte", padrao.fatorRecorte, avisar)
                  .let { valor ->
                    // Fora de (0, 1] não é recorte: 0 não retém nada e acima de 1 pediria pixels
                    // que o frame não tem.
                    if (valor > 0f && valor <= 1f) valor
                    else padrao.fatorRecorte.also { avisar("fatorRecorte fora de (0,1]; usando $it") }
                  },
          qualidade = propriedades.enumeracao("qualidade", padrao.qualidade, avisar),
          fps = propriedades.inteiro("fps", padrao.fps, avisar),
          rotacaoGraus =
              propriedades.inteiro("rotacaoGraus", padrao.rotacaoGraus, avisar).let { valor ->
                if (valor in ROTACOES_VALIDAS) valor
                else padrao.rotacaoGraus.also { avisar("rotacaoGraus deve ser 0/90/180/270; usando $it") }
              },
          formatos = propriedades.formatos("formatos", padrao.formatos, avisar),
          confirmacoesDeLeitura =
              propriedades
                  .inteiro("confirmacoesDeLeitura", padrao.confirmacoesDeLeitura, avisar)
                  .let { valor ->
                    if (valor >= 1) valor
                    else
                        padrao.confirmacoesDeLeitura.also {
                          avisar("confirmacoesDeLeitura precisa ser >= 1; usando $it")
                        }
                  },
          logTentativas = propriedades.booleano("logTentativas", padrao.logTentativas, avisar),
      )
    }

    private fun Properties.booleano(
        chave: String,
        padrao: Boolean,
        avisar: (String) -> Unit,
    ): Boolean =
        when (getProperty(chave)?.trim()?.lowercase()) {
          null -> padrao
          "true" -> true
          "false" -> false
          else -> padrao.also { avisar("Valor inválido para $chave; mantendo $padrao") }
        }

    private fun Properties.inteiro(chave: String, padrao: Int, avisar: (String) -> Unit): Int {
      val bruto = getProperty(chave) ?: return padrao
      return bruto.trim().toIntOrNull()
          ?: padrao.also { avisar("Valor inválido para $chave; mantendo $padrao") }
    }

    private fun Properties.decimal(chave: String, padrao: Float, avisar: (String) -> Unit): Float {
      val bruto = getProperty(chave) ?: return padrao
      return bruto.trim().toFloatOrNull()
          ?: padrao.also { avisar("Valor inválido para $chave; mantendo $padrao") }
    }

    private fun Properties.enumeracao(
        chave: String,
        padrao: QualidadeStream,
        avisar: (String) -> Unit,
    ): QualidadeStream {
      val bruto = getProperty(chave)?.trim()?.uppercase() ?: return padrao
      return QualidadeStream.entries.firstOrNull { it.name == bruto }
          ?: padrao.also { avisar("Valor inválido para $chave; mantendo $padrao") }
    }

    private fun Properties.formatos(
        chave: String,
        padrao: List<FormatoCodigo>,
        avisar: (String) -> Unit,
    ): List<FormatoCodigo> {
      val bruto = getProperty(chave) ?: return padrao
      val lidos =
          bruto
              .split(',')
              .map { it.trim().uppercase() }
              .filter { it.isNotEmpty() }
              .mapNotNull { nome ->
                val formato = FormatoCodigo.entries.firstOrNull { it.name == nome }
                if (formato == null) avisar("Formato desconhecido em $chave: $nome")
                formato
              }
      // Lista vazia desligaria a leitura por inteiro sem que ninguém pedisse isso — quem quer
      // desligar a visão não concede a permissão de câmera.
      return lidos.ifEmpty { padrao.also { avisar("Nenhum formato válido em $chave; mantendo $padrao") } }
    }
  }
}
