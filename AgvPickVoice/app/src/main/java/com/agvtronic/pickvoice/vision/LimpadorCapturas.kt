package com.agvtronic.pickvoice.vision

import java.io.File

/**
 * Remove somente resíduos da pasta privada reservada ao fallback de foto.
 *
 * O pipeline atual trabalha em memória e não cria esses arquivos. A varredura existe para que
 * uma futura mudança de decoder que precise de arquivo temporário não transforme um crash em
 * retenção permanente de imagem.
 */
fun limparTemporariosDeCaptura(diretorio: File): Int {
  if (!diretorio.exists() || !diretorio.isDirectory) return 0
  var removidos = 0
  diretorio.listFiles().orEmpty().forEach { arquivo ->
    if (arquivo.isFile && arquivo.delete()) removidos++
  }
  return removidos
}
