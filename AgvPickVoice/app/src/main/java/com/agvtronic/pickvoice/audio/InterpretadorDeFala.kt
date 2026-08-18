package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState

/**
 * Texto reconhecido + estado atual -> intenção, ou nada.
 *
 * O outro lado do [SeletorDeEscuta]: um decide o que pode ser dito, o outro o que aquilo
 * significa **naquele estado**. A mesma palavra muda de sentido conforme o momento do fluxo, e
 * é isso que a Decisão 1 do design.md chama de "a fala é interpretada pelo estado atual".
 *
 * Função pura: sem Android, sem Vosk, sem repositório e sem corrotina. Nada aqui envia evento —
 * quem envia é o [PublicadorDeVoz], depois de o [ResolvedorDeIntencao] conferir o que precisa
 * ser conferido.
 *
 * Texto fora do contrato do estado não produz intenção nenhuma. É o caso comum no galpão, não
 * uma condição de erro: conversa ao lado, uma palavra pela metade, o `[unk]` do Vosk.
 */
object InterpretadorDeFala {

  /**
   * Teto de quantidade aceita por voz.
   *
   * O piso é 1: "zero unidades" não é uma coleta, é uma ruptura, e tem comando próprio.
   */
  private val QUANTIDADE_ACEITA = 1..999

  fun interpretar(estado: PickingState, texto: String): IntencaoDeVoz? {
    val normalizado = texto.trim().lowercase()
    if (normalizado.isEmpty() || normalizado == VocabularioDeVoz.DESCONHECIDA) return null

    // Transversais primeiro: valem em todo estado operacional (doc §3.3) e nenhuma palavra
    // delas colide com um comando de fluxo.
    transversal(estado, normalizado)?.let {
      return it
    }

    return when (estado) {
      // "próximo" é sinônimo aditivo de "iniciar" aqui — mesmo evento, mesma consequência
      // (design.md - Decisão 6 de add-operator-feedback-improvements).
      is PickingState.OrdemCarregada ->
          IntencaoDeVoz.IniciarNavegacao.takeIf {
            normalizado == VocabularioDeVoz.INICIAR || normalizado == VocabularioDeVoz.PROXIMO
          }

      // "próximo" é sinônimo aditivo de "cheguei" aqui (mesma decisão acima).
      is PickingState.NavegandoParaEndereco ->
          evento(PickingEvent.EnderecoAlcancado).takeIf {
            normalizado == VocabularioDeVoz.CHEGUEI || normalizado == VocabularioDeVoz.PROXIMO
          }

      // Por extenso primeiro ("quarenta e sete"), dígito a dígito depois ("quatro sete"): o
      // extenso caiu na bancada de 17/08/2026 (add-voice-recognition-reliability - Decisão 1) e
      // voltou na de 18/08/2026, porque só dígito a dígito também falhava — "a todo momento é
      // entendido somente um deles". [VocabularioDeVoz.checkDigitExtenso] evita o motivo
      // original da queda (a gramática ganhando palavras de centena) restringindo-se a 0..99.
      // Ver [SeletorDeEscuta].
      //
      // O fallback exige dois algarismos exatos: um só é fala cortada, três é ruído somado à
      // fala — nos dois casos conferir seria adivinhar (doc §7.1).
      is PickingState.AguardandoCheckDigit ->
          (VocabularioDeVoz.checkDigitExtenso(normalizado)
                  ?: VocabularioDeVoz.digitos(normalizado)
                      ?.takeIf { it.length == DIGITOS_DO_CHECK_DIGIT })
              ?.let(IntencaoDeVoz::CheckDigitFalado)

      // Por extenso primeiro ("doze"), dígito a dígito depois ("um dois"): o operador usa as duas
      // leituras, e só a segunda aceita magnitudes não decrescentes — invertê-las faria "um dois"
      // ser rejeitado por `numero` antes de chegar à leitura que o entende. Essa ordem caiu por
      // um dia (Decisão 7, por consistência com o check digit acima) e voltou na bancada de
      // 18/08/2026 pelo mesmo motivo do check digit: só dígito a dígito também falhava aqui.
      is PickingState.ConfirmandoQuantidade ->
          (VocabularioDeVoz.numero(normalizado)
                  ?: VocabularioDeVoz.numeroDigitoADigito(normalizado))
              ?.takeIf { it in QUANTIDADE_ACEITA }
              ?.let { evento(PickingEvent.QuantidadeInformada(it)) }

      is PickingState.ReadbackQuantidade ->
          when (normalizado) {
            // "próximo" é sinônimo aditivo de "confirmar" aqui — mesmo evento, mesma
            // consequência (design.md - Decisão 8).
            VocabularioDeVoz.CONFIRMAR,
            VocabularioDeVoz.PROXIMO -> evento(PickingEvent.ReadbackConfirmado)
            VocabularioDeVoz.CORRIGIR -> evento(PickingEvent.ReadbackCorrecaoSolicitada)
            else -> null
          }

      is PickingState.AlocandoCarrinho ->
          evento(PickingEvent.ItemAlocado).takeIf {
            normalizado == VocabularioDeVoz.ALOCADO || normalizado == VocabularioDeVoz.PROXIMO
          }

      is PickingState.ItemConcluido ->
          IntencaoDeVoz.AvancarParaProximoItem.takeIf { normalizado == VocabularioDeVoz.PROXIMO }

      // Só "próximo", como qualquer outro avanço de uma palavra do fluxo
      // (add-voice-recognition-reliability - Decisão 2). O relato falado livre de três ou mais
      // palavras deixou de ser aceito: `ExcecaoRegistrada` nunca carregou o texto reconhecido e
      // nada no domínio o gravava ou consumia, então o vocabulário aberto que ele exigia só
      // comprava instabilidade de transcrição. Detalhe da ocorrência entra pela ação de toque
      // da tela do operador.
      is PickingState.TratandoExcecao ->
          evento(PickingEvent.ExcecaoRegistrada).takeIf {
            normalizado == VocabularioDeVoz.PROXIMO
          }

      // "próximo" é sinônimo aditivo de "concluir" aqui (design.md - Decisão 6 de
      // add-operator-feedback-improvements).
      is PickingState.ConferenciaFinal ->
          evento(PickingEvent.ConferenciaConcluida).takeIf {
            normalizado == VocabularioDeVoz.CONCLUIR || normalizado == VocabularioDeVoz.PROXIMO
          }

      // "próximo" é sinônimo aditivo de "encerrar" aqui (mesma decisão acima).
      is PickingState.OrdemConcluida ->
          evento(PickingEvent.OrdemEncerrada).takeIf {
            normalizado == VocabularioDeVoz.ENCERRAR || normalizado == VocabularioDeVoz.PROXIMO
          }

      is PickingState.SessaoPausada ->
          evento(PickingEvent.SessaoRetomada).takeIf { normalizado == VocabularioDeVoz.RETOMAR }

      // Escaneamento, decodificação, verificação e validação: o código vem da câmera, nunca da
      // voz (spec: "Código só vem do produtor óptico"). Sessão e erro não têm fala de avanço.
      PickingState.Ocioso,
      PickingState.Registrando,
      PickingState.PreparandoSessao,
      PickingState.AguardandoOrdem,
      is PickingState.EscaneandoProduto,
      is PickingState.DecodificandoProduto,
      is PickingState.VerificacaoAssistida,
      is PickingState.ValidandoContraDados,
      is PickingState.Erro -> null
    }
  }

  /**
   * Os comandos do doc §3.3, válidos em qualquer estado operacional.
   *
   * O filtro por [PickingState.ehOperacional] repete a condição do reducer de propósito: um
   * evento que o reducer descartaria não deve nem sair da camada de áudio, senão o log de
   * calibração passa a mostrar comandos "aceitos" que não mudam nada.
   */
  private fun transversal(estado: PickingState, texto: String): IntencaoDeVoz? {
    if (!estado.ehOperacional) return null
    return when (texto) {
      VocabularioDeVoz.PARAR -> evento(PickingEvent.ComandoParar)
      VocabularioDeVoz.EMERGENCIA -> evento(PickingEvent.ComandoEmergencia)
      VocabularioDeVoz.REPETIR -> evento(PickingEvent.ComandoRepetir)
      VocabularioDeVoz.AVARIA -> excecao(MotivoExcecao.AVARIA)
      VocabularioDeVoz.RUPTURA -> excecao(MotivoExcecao.RUPTURA)
      VocabularioDeVoz.DIVERGENCIA -> excecao(MotivoExcecao.DIVERGENCIA)
      else -> null
    }
  }

  private fun excecao(motivo: MotivoExcecao) = evento(PickingEvent.ExcecaoSolicitada(motivo))

  private fun evento(evento: PickingEvent): IntencaoDeVoz = IntencaoDeVoz.Direta(evento)

  /** Os dois dígitos do doc §7.1 e §7.2 — a senha do endereço e o fim do lote. */
  const val DIGITOS_DO_CHECK_DIGIT = 2
}
