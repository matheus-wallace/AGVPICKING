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

  /** Palavras mínimas para um relato de exceção ser aceito como relato, e não como ruído. */
  private const val PALAVRAS_MINIMAS_DO_RELATO = 3

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
      is PickingState.OrdemCarregada ->
          IntencaoDeVoz.IniciarNavegacao.takeIf { normalizado == VocabularioDeVoz.INICIAR }

      is PickingState.NavegandoParaEndereco ->
          evento(PickingEvent.EnderecoAlcancado).takeIf {
            normalizado == VocabularioDeVoz.CHEGUEI
          }

      // Dois dígitos exatos. Um só é fala cortada; três é ruído somado à fala — nos dois casos
      // conferir seria adivinhar (doc §7.1).
      is PickingState.AguardandoCheckDigit ->
          VocabularioDeVoz.digitos(normalizado)
              ?.takeIf { it.length == DIGITOS_DO_CHECK_DIGIT }
              ?.let(IntencaoDeVoz::CheckDigitFalado)

      is PickingState.ConfirmandoQuantidade ->
          VocabularioDeVoz.numero(normalizado)
              ?.takeIf { it in QUANTIDADE_ACEITA }
              ?.let { evento(PickingEvent.QuantidadeInformada(it)) }

      is PickingState.ReadbackQuantidade ->
          when (normalizado) {
            VocabularioDeVoz.CONFIRMAR -> evento(PickingEvent.ReadbackConfirmado)
            VocabularioDeVoz.CORRIGIR -> evento(PickingEvent.ReadbackCorrecaoSolicitada)
            else -> null
          }

      is PickingState.AlocandoCarrinho ->
          evento(PickingEvent.ItemAlocado).takeIf { normalizado == VocabularioDeVoz.ALOCADO }

      is PickingState.ItemConcluido ->
          IntencaoDeVoz.AvancarParaProximoItem.takeIf { normalizado == VocabularioDeVoz.PROXIMO }

      // Vocabulário aberto, mas não passa qualquer coisa: um relato precisa ter forma de
      // relato (design.md - Decisão 2). Uma palavra solta aqui é ruído do galpão.
      is PickingState.TratandoExcecao ->
          evento(PickingEvent.ExcecaoRegistrada).takeIf {
            VocabularioDeVoz.palavras(normalizado).size >= PALAVRAS_MINIMAS_DO_RELATO
          }

      is PickingState.ConferenciaFinal ->
          evento(PickingEvent.ConferenciaConcluida).takeIf {
            normalizado == VocabularioDeVoz.CONCLUIR
          }

      is PickingState.OrdemConcluida ->
          evento(PickingEvent.OrdemEncerrada).takeIf { normalizado == VocabularioDeVoz.ENCERRAR }

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
