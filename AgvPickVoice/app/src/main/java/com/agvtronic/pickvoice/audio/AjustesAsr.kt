package com.agvtronic.pickvoice.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/**
 * Os parâmetros de calibração do pipeline de voz, lidos de um arquivo opcional em vez de
 * ficarem fixos no código.
 *
 * ### Por que não são constantes
 *
 * O APK de debug tem 127 MB por causa do modelo Vosk, e cada `installDebug` custa quase um
 * minuto. Calibrar limiar de endpoint, ganho e degradação de canal exige dezenas de tentativas
 * com voz humana no aparelho (doc §10) — recompilar a cada valor consumiria a maior parte do
 * tempo de bancada que resta até 18/09. Com este arquivo, um ciclo de calibração é:
 *
 * ```
 * adb push ajustes-asr.properties /sdcard/Android/data/com.agvtronic.pickvoice/files/
 * adb shell am force-stop com.agvtronic.pickvoice   # os ajustes são lidos na inicialização
 * ```
 *
 * O arquivo mora no mesmo `getExternalFilesDir` para onde o `StorageService` copia o modelo,
 * então é gravável por `adb` sem root e some junto com o app na desinstalação.
 *
 * ### Por que os defaults são os valores de produção
 *
 * Nenhum ajuste é obrigatório: sem o arquivo, o app roda exatamente com os valores que a
 * fatia definiu (doc §5.1, §5.2). O arquivo é um instrumento de bancada, não um requisito de
 * configuração — se ele sumir no dia do evento, nada muda de comportamento.
 *
 * @property degradarCanal quando `false`, entrega o microfone a 16 kHz cru ao reconhecedor, sem
 *   band-pass nem decimação. É o interruptor que separa "o modelo pequeno não dá conta" de "a
 *   degradação de canal do doc §10.1 é que está matando o reconhecimento" — sem ele as duas
 *   hipóteses são indistinguíveis no logcat.
 * @property cancelamentoDeEco liga o [android.media.audiofx.AcousticEchoCanceler] sobre a
 *   captura. **Desligado por padrão**: o AEC é projetado para `VOICE_COMMUNICATION` com uma
 *   referência de playback, e sobre uma sessão `VOICE_RECOGNITION` sem nada tocando ele não tem
 *   o que cancelar — em vários aparelhos o efeito só atenua o sinal. Volta a fazer sentido
 *   quando o TTS do doc §5.4 existir e tocar no mesmo aparelho.
 * @property ganho multiplica as amostras antes de entregá-las ao decodificador, com clipping em
 *   `±1.0`. **Não melhora a relação sinal/ruído** — amplifica o ruído junto — e o Kaldi já
 *   normaliza a média cepstral, então `1.0` deve continuar sendo o valor certo. Existe para
 *   testar a hipótese, não porque se espera que ela se confirme.
 * @property silencioFinalMs o `t_end` do endpointer do Vosk: silêncio após a fala que fecha a
 *   elocução. O default é o [PerfilEndpoint.COMANDO_CURTO] do doc §5.1; se o logcat mostrar
 *   parciais truncadas ("pa" em vez de "parar"), é este número que sobe.
 * @property silencioAntesDaFalaMs o `t_start_max`: sem nenhuma palavra decodificada, o Vosk
 *   fecha uma elocução vazia depois deste tempo e recomeça.
 * @property duracaoMaximaMs o `t_max`: teto duro de uma elocução.
 * @property logParciais loga o resultado parcial do decodificador a cada mudança. É o que torna
 *   visível *o que* o ASR está ouvindo enquanto ouve, em vez de só o que sobrou no fim.
 * @property logNivel loga RMS e pico do sinal capturado uma vez por segundo. Responde à
 *   pergunta que veio antes de todas as outras: o microfone está entregando alguma coisa?
 */
data class AjustesAsr(
    val degradarCanal: Boolean = true,
    val cancelamentoDeEco: Boolean = false,
    val ganho: Float = 1f,
    val silencioFinalMs: Int = PerfilEndpoint.COMANDO_CURTO.silencioFinalMs,
    val silencioAntesDaFalaMs: Int = 5_000,
    val duracaoMaximaMs: Int = 10_000,
    val logParciais: Boolean = true,
    val logNivel: Boolean = true,
) {

  companion object {
    private const val TAG = "AjustesAsr"

    /** Nome do arquivo dentro de `getExternalFilesDir(null)`. */
    const val NOME_ARQUIVO = "ajustes-asr.properties"

    /**
     * Lê os ajustes do arquivo, caindo para os defaults em qualquer problema.
     *
     * Chamado na construção do `AppContainer`, ou seja, na main thread durante o
     * `Application.onCreate`. É um arquivo de poucas linhas — a leitura custa menos que a
     * própria checagem de existência do diretório, e precisa ser síncrona porque a
     * [AudioMicrofoneSimulado] já nasce com a taxa de amostragem decidida por ela.
     *
     * Uma chave desconhecida ou um valor inválido não derruba nada: o campo correspondente
     * mantém o default e o arquivo inteiro continua valendo para os outros.
     */
    fun carregar(appContext: Context): AjustesAsr {
      val arquivo = File(appContext.getExternalFilesDir(null), NOME_ARQUIVO)
      if (!arquivo.exists()) {
        Log.i(TAG, "Sem $NOME_ARQUIVO em ${arquivo.parent}; usando os ajustes padrão")
        return AjustesAsr()
      }

      val propriedades =
          runCatching { Properties().apply { arquivo.inputStream().use { load(it) } } }
              .onFailure { Log.e(TAG, "Falha ao ler $NOME_ARQUIVO; usando os padrões", it) }
              .getOrNull() ?: return AjustesAsr()

      val padrao = AjustesAsr()
      val ajustes =
          AjustesAsr(
              degradarCanal = propriedades.booleano("degradarCanal", padrao.degradarCanal),
              cancelamentoDeEco =
                  propriedades.booleano("cancelamentoDeEco", padrao.cancelamentoDeEco),
              ganho = propriedades.decimal("ganho", padrao.ganho),
              silencioFinalMs = propriedades.inteiro("silencioFinalMs", padrao.silencioFinalMs),
              silencioAntesDaFalaMs =
                  propriedades.inteiro("silencioAntesDaFalaMs", padrao.silencioAntesDaFalaMs),
              duracaoMaximaMs = propriedades.inteiro("duracaoMaximaMs", padrao.duracaoMaximaMs),
              logParciais = propriedades.booleano("logParciais", padrao.logParciais),
              logNivel = propriedades.booleano("logNivel", padrao.logNivel),
          )

      Log.i(TAG, "Ajustes carregados de $NOME_ARQUIVO: $ajustes")
      return ajustes
    }

    private fun Properties.booleano(chave: String, padrao: Boolean): Boolean =
        when (getProperty(chave)?.trim()?.lowercase()) {
          null -> padrao
          "true" -> true
          "false" -> false
          else -> padrao.also { Log.w(TAG, "Valor inválido para $chave; mantendo $padrao") }
        }

    private fun Properties.inteiro(chave: String, padrao: Int): Int =
        getProperty(chave)?.trim()?.toIntOrNull()
            ?: padrao.also {
              if (getProperty(chave) != null) {
                Log.w(TAG, "Valor inválido para $chave; mantendo $padrao")
              }
            }

    private fun Properties.decimal(chave: String, padrao: Float): Float =
        getProperty(chave)?.trim()?.toFloatOrNull()
            ?: padrao.also {
              if (getProperty(chave) != null) {
                Log.w(TAG, "Valor inválido para $chave; mantendo $padrao")
              }
            }
  }
}
