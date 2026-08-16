package com.agvtronic.pickvoice.audio

import kotlinx.coroutines.flow.Flow

/**
 * A abstração de fonte de áudio do doc §5.2 — o ponto de troca entre o microfone do celular
 * (desenvolvimento) e o HFP do óculos (produção).
 *
 * Ela existe por um motivo de cronograma, não de arquitetura: **o MockDeviceKit simula a
 * câmera, mas não há equivalente para o caminho de áudio HFP**. Sem esta interface, o pipeline
 * de voz — o de maior esforço do projeto — só seria exercitado em 18/09, no dia em que não
 * sobra tempo. Com ela, todo o desenvolvimento até lá roda contra
 * [AudioMicrofoneSimulado], e a troca no dia é uma linha no `AppContainer`.
 *
 * Mesma regra do `PickingRepository` (doc §11.1): quem consome recebe a interface, nunca a
 * implementação.
 *
 * ### O contrato de escala das amostras
 *
 * As amostras são **normalizadas em `-1.0..1.0`**, a convenção usual de DSP e a que deixa o
 * filtro de [AudioMicrofoneSimulado] legível. O Vosk, por outro lado, espera as amostras na
 * escala de `int16` (`±32767`) mesmo na sobrecarga que recebe `float[]` — a conversão fica no
 * [ReconhecedorDeComando], num lugar só, e não vaza para cá. Confundir as duas escalas não dá
 * erro: dá silêncio, que é bem pior de diagnosticar.
 */
interface FonteAudio {

  /**
   * Taxa de amostragem do fluxo, em Hz.
   *
   * É 8000 em ambas as implementações — o HFP do óculos captura a 8 kHz (doc §2.1), e o
   * microfone simulado reproduz exatamente essa degradação (doc §10.1) justamente para que a
   * calibração feita em agosto transfira para o hardware real com ajuste mínimo.
   */
  val sampleRate: Int

  /**
   * Abre a captura e emite janelas de áudio até o coletor ser cancelado.
   *
   * `Flow` frio de propósito: a captura começa na coleta e termina com o cancelamento dela,
   * então ninguém precisa lembrar de fechar nada — o escopo do coletor já é o dono.
   *
   * @param tamanhoJanela quantas amostras por emissão.
   * @return janelas de [tamanhoJanela] amostras normalizadas em `-1.0..1.0`.
   */
  fun fluxo(tamanhoJanela: Int): Flow<FloatArray>
}
