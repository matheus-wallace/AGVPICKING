package com.agvtronic.pickvoice.vision

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O passo 1 da cascata do doc §6.3: ML Kit **bundled** sobre o recorte do frame do stream.
 *
 * Bundled e não a distribuição via Play Services porque o §6.3 é explícito — a variante
 * unbundled baixa o modelo no primeiro uso, e falharia em silêncio num celular recém-recebido,
 * sem rede confiável, na manhã do evento.
 *
 * Roda numa thread própria: o recorte chega da thread do decodificador e o resultado precisa
 * sair de lá sem prender nem o codec nem o dispatcher do ator (doc §4.1).
 *
 * @param ajustes de onde saem os formatos aceitos e a rotação informada ao ML Kit.
 */
class LeitorDeCodigo(private val ajustes: AjustesVisao) : Closeable {

  private val executor =
      Executors.newSingleThreadExecutor { tarefa -> Thread(tarefa, "LeitorDeCodigo") }

  private val leitor: BarcodeScanner = BarcodeScanning.getClient(opcoes(ajustes.formatos))

  @Volatile private var encerrado = false

  /**
   * Enfileira uma leitura na thread do leitor e chama [aoConcluir] **nela**, com o conteúdo lido
   * ou `null`.
   *
   * O recorte já vem sem o quadro completo (ver [recortarParaNv21]): daqui para a frente o único
   * pixel que existe no processo é o da ROI.
   */
  fun ler(recorte: RecorteNv21, aoConcluir: (String?) -> Unit) {
    if (encerrado) return
    executor.execute {
      if (encerrado) return@execute
      val inicio = System.nanoTime()
      val codigo = decodificar(recorte)
      val duracaoMs = (System.nanoTime() - inicio) / 1_000_000

      if (ajustes.logTentativas) {
        // Campos da linha "Tentativa de decodificação" do doc §4.5. A distância estimada ainda
        // não existe — ela depende do gatilho de captura (§6.2), que é do Marco 2.
        val mensagem =
            "tentativa passo=1-stream metodo=mlkit-bundled " +
                "roi=${recorte.largura}x${recorte.altura} rotacao=${ajustes.rotacaoGraus} " +
                "tempoMs=$duracaoMs resultado=${codigo ?: "nada"}"
        if (codigo != null) Log.i(TAG, mensagem) else Log.d(TAG, mensagem)
      }

      aoConcluir(codigo)
    }
  }

  private fun decodificar(recorte: RecorteNv21): String? {
    val entrada =
        InputImage.fromByteBuffer(
            ByteBuffer.wrap(recorte.bytes),
            recorte.largura,
            recorte.altura,
            ajustes.rotacaoGraus,
            InputImage.IMAGE_FORMAT_NV21,
        )
    // `process` é assíncrono por API, mas já estamos numa thread própria e serializada — esperar
    // aqui mantém "um frame por vez" sem precisar de mais nenhuma sincronização. O timeout existe
    // porque um travamento do ML Kit não pode congelar a leitura para sempre.
    val codigos =
        runCatching { Tasks.await(leitor.process(entrada), TIMEOUT_S, TimeUnit.SECONDS) }
            .onFailure { Log.w(TAG, "Falha na leitura: ${it.message}") }
            .getOrNull()
            .orEmpty()

    // `rawValue` e não `displayValue`: o que a cascata precisa é o conteúdo cru do código,
    // inclusive os separadores GS1 (doc §6.5) que o Marco 2 vai interpretar. `displayValue` é
    // texto para humano e pode vir sanitizado.
    return codigos.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
  }

  /** Solta a thread e o leitor do ML Kit. Idempotente. */
  override fun close() {
    if (encerrado) return
    encerrado = true
    executor.execute { runCatching { leitor.close() } }
    executor.shutdown()
  }

  private companion object {
    const val TAG = "LeitorDeCodigo"

    const val TIMEOUT_S = 2L

    fun opcoes(formatos: List<FormatoCodigo>): BarcodeScannerOptions {
      // Restringir formatos é recomendação do próprio ML Kit para velocidade: cada formato a
      // mais é um detector a mais rodando sobre o mesmo frame.
      val codigos = formatos.map(::paraMlKit)
      return BarcodeScannerOptions.Builder()
          .setBarcodeFormats(codigos.first(), *codigos.drop(1).toIntArray())
          .build()
    }

    fun paraMlKit(formato: FormatoCodigo): Int =
        when (formato) {
          FormatoCodigo.CODE_128 -> Barcode.FORMAT_CODE_128
          FormatoCodigo.DATA_MATRIX -> Barcode.FORMAT_DATA_MATRIX
          FormatoCodigo.EAN_13 -> Barcode.FORMAT_EAN_13
        }
  }
}
