package com.agvtronic.pickvoice.data.mock

import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.model.Coleta
import com.agvtronic.pickvoice.data.model.Conferencia
import com.agvtronic.pickvoice.data.model.Endereco
import com.agvtronic.pickvoice.data.model.Excecao
import com.agvtronic.pickvoice.data.model.Linha
import com.agvtronic.pickvoice.data.model.Operador
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.data.model.ResumoOrdem
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * O WMS substituído por um mapa em memória — doc §11.
 *
 * **Zero chamada de rede em qualquer ponto desta classe**, e é por isso que ela existe:
 * mockar o WMS é correto porque a integração com WMS não é o que está em dúvida (doc §1.2).
 * O que nunca é mockado é o outro lado — captura de áudio, ASR, captura de imagem e
 * decodificação são sempre reais, senão o experimento não prova nada.
 *
 * Os dados são fictícios mas **estruturalmente reais** (doc §11.4), e "estruturalmente real"
 * aqui significa literalmente as convenções do WMS de produção da AGV, não um formato
 * plausível inventado: `produto` numérico de 6 dígitos, `partida` de 8, `pedido` de 6,
 * `praca` alfanumérica de 11, `ua` de 8, EAN-13 e DUN-14 com dígito verificador válido, e
 * endereço decomposto em `cd/setor/andar/predio/rua` cujo código de barras é
 * `cd + setor + andar + predio(4) + rua`. A cascata de decodificação e o parser GS1
 * desenvolvidos contra este mock se comportam igual contra dados reais depois — que é o
 * único motivo de o realismo importar aqui.
 *
 * **Nenhum valor abaixo veio de uma base real.** Todos foram inventados dentro desses
 * formatos; nenhum pedido, praça, produto, lote, UA ou endereço aqui existe em produção.
 *
 * Sem persistência entre execuções: a ordem reinicia a cada sessão (doc §1.3).
 */
class MockPickingRepository : PickingRepository {

  private val mutex = Mutex()
  private val coletasPorOrdem = mutableMapOf<String, MutableMap<Int, Coleta>>()
  private val excecoesPorOrdem = mutableMapOf<String, MutableList<Excecao>>()

  override suspend fun operadorAtual(): Operador = semIo { OPERADOR }

  override suspend fun ordensDisponiveis(): List<ResumoOrdem> = semIo {
    ORDENS.map { it.resumo }
  }

  override suspend fun ordem(id: String): Ordem = semIo {
    ORDENS.firstOrNull { it.id == id }
        ?: throw NoSuchElementException("Ordem desconhecida: $id")
  }

  override suspend fun registrarColeta(ordemId: String, linha: Int, coleta: Coleta) {
    val alvo = ordem(ordemId)
    require(linha in alvo.linhas.indices) {
      "Linha $linha fora da ordem $ordemId (${alvo.linhas.size} linhas)"
    }
    semIo { mutex.withLock { coletasPorOrdem.getOrPut(ordemId) { mutableMapOf() }[linha] = coleta } }
  }

  override suspend fun registrarExcecao(ordemId: String, excecao: Excecao) {
    ordem(ordemId) // valida que a ordem existe antes de aceitar a ocorrência
    semIo { mutex.withLock { excecoesPorOrdem.getOrPut(ordemId) { mutableListOf() } += excecao } }
  }

  override suspend fun fecharConferencia(ordemId: String): Conferencia {
    val alvo = ordem(ordemId)
    return semIo {
      mutex.withLock {
        val coletas = coletasPorOrdem[ordemId].orEmpty()
        Conferencia(
            ordemId = ordemId,
            totalLinhas = alvo.linhas.size,
            linhasColetadas = coletas.size,
            linhasDivergentes =
                coletas
                    .filter { (indice, coleta) -> coleta.quantidade != alvo.linhas[indice].quantidade }
                    .keys
                    .sorted(),
            excecoes = excecoesPorOrdem[ordemId].orEmpty().toList(),
            fechadaEm = Instant.now(),
        )
      }
    }
  }

  /**
   * O contexto que o doc §4.3 reserva para o repositório de dados.
   *
   * O nome é o lembrete do que não pode aparecer aqui dentro: nada de rede, nada de disco.
   * O `Dispatchers.Default` está aqui pela tabela de contextos do doc, não porque haja I/O
   * a tirar da thread do chamador — não há.
   */
  private suspend fun <T> semIo(bloco: suspend () -> T): T =
      withContext(Dispatchers.Default) { bloco() }

  private companion object {

    val OPERADOR =
        Operador(
            id = "OP-2291",
            nome = "Jonas Ribeiro da Silva",
            matricula = "2291",
        )

    /**
     * Duas ordens de separação de distribuidora farmacêutica, no formato do WMS.
     *
     * Detalhes que valem a pena notar porque exercitam comportamento real do fluxo:
     *
     * - A ordem `408176` tem **duas linhas no mesmo endereço** (`7204B0118D`, produtos
     *   514702 e 514703). O WMS emite uma parada por linha, não por endereço — o mesmo
     *   código de barras é pedido duas vezes seguidas, e isso é comportamento esperado, não
     *   defeito. Se o protótipo agrupar endereços iguais, é decisão de produto e precisa ser
     *   consciente.
     * - A ordem `408193` tem o **mesmo produto+partida em dois endereços** (produto 402118,
     *   partida 62204718: 14 + 106 = 120 unidades). É rateio de alocação, e a soma bater com
     *   o total pedido é o teste que distingue rateio de duplicata.
     * - `recnum` distinto por linha: duas paradas com o mesmo recnum seriam defeito real.
     *
     * Dígito verificador de EAN-13 e DUN-14 válidos; a senha do endereço é cadastrada por
     * posição e **não derivável do endereço** (doc §7.1).
     */
    val ORDENS =
        listOf(
            Ordem(
                praca = "274K5010000",
                pedido = "408176",
                cliente = "Drogaria Vila Ema — Loja 12",
                linhas =
                    listOf(
                        Linha(
                            produto = "514702",
                            descricao = "DIPIRONA SODICA 500MG COM 10 COMPRIMIDOS",
                            endereco =
                                Endereco(cd = "72", setor = "04", andar = "B", predio = "118", rua = "D"),
                            senhaEndereco = "47",
                            ean = "7896006310242",
                            dun14 = "17896006310249",
                            partida = "60318425",
                            serie = "1002478351",
                            validade = LocalDate.of(2027, 4, 30),
                            quantidade = 12,
                            ua = "39604512",
                            recnum = "12480537",
                            saldoEndereco = 240,
                            dirStage = "ST01",
                        ),
                        Linha(
                            produto = "514703",
                            descricao = "DIPIRONA SODICA 1G COM 10 COMPRIMIDOS",
                            endereco =
                                Endereco(cd = "72", setor = "04", andar = "B", predio = "118", rua = "D"),
                            senhaEndereco = "47",
                            ean = "7891456120779",
                            dun14 = "27891456120773",
                            partida = "60318431",
                            serie = "1002478419",
                            validade = LocalDate.of(2026, 11, 30),
                            quantidade = 4,
                            ua = "39604512",
                            recnum = "12480538",
                            saldoEndereco = 96,
                            dirStage = "ST01",
                        ),
                        Linha(
                            produto = "527940",
                            descricao = "LOSARTANA POTASSICA 50MG COM 30 COMPRIMIDOS",
                            endereco =
                                Endereco(cd = "72", setor = "04", andar = "C", predio = "233", rua = "G"),
                            senhaEndereco = "82",
                            ean = "7890243719035",
                            dun14 = "17890243719032",
                            partida = "59714203",
                            serie = "1002481760",
                            validade = LocalDate.of(2027, 8, 31),
                            quantidade = 30,
                            ua = "39718044",
                            recnum = "12480601",
                            saldoEndereco = 180,
                            dirStage = "ST02",
                        ),
                    ),
            ),
            Ordem(
                praca = "309B7040000",
                pedido = "408193",
                cliente = "Drogaria Nova Esperança — Osasco",
                linhas =
                    listOf(
                        Linha(
                            produto = "402118",
                            descricao = "OMEPRAZOL 20MG COM 28 CAPSULAS",
                            endereco =
                                Endereco(cd = "72", setor = "07", andar = "A", predio = "56", rua = "K"),
                            senhaEndereco = "35",
                            ean = "7890025481167",
                            dun14 = "27890025481161",
                            partida = "62204718",
                            serie = "1002490114",
                            validade = LocalDate.of(2027, 1, 31),
                            quantidade = 14,
                            ua = "40113276",
                            recnum = "12491204",
                            saldoEndereco = 144,
                            dirStage = "ST03",
                        ),
                        Linha(
                            produto = "402118",
                            descricao = "OMEPRAZOL 20MG COM 28 CAPSULAS",
                            endereco =
                                Endereco(cd = "72", setor = "07", andar = "A", predio = "72", rua = "K"),
                            senhaEndereco = "19",
                            ean = "7890025481167",
                            dun14 = "27890025481161",
                            partida = "62204718",
                            serie = "1002490127",
                            validade = LocalDate.of(2027, 1, 31),
                            quantidade = 106,
                            ua = "40113310",
                            recnum = "12491205",
                            saldoEndereco = 260,
                            dirStage = "ST03",
                        ),
                        Linha(
                            produto = "486205",
                            descricao = "SORO FISIOLOGICO 0,9% 500ML BOLSA",
                            endereco =
                                Endereco(cd = "72", setor = "07", andar = "D", predio = "9", rua = "B"),
                            senhaEndereco = "68",
                            ean = "7891815304222",
                            dun14 = "17891815304229",
                            partida = "61840537",
                            serie = "1002490233",
                            validade = LocalDate.of(2026, 9, 30),
                            quantidade = 20,
                            ua = "40120884",
                            recnum = "12491260",
                            saldoEndereco = 80,
                            dirStage = "ST03",
                        ),
                    ),
            ),
        )
  }
}
