package com.agvtronic.pickvoice.data.model

import java.time.LocalDate

/**
 * Uma linha de separação da ordem — doc §11.2.
 *
 * Este é o "valor esperado" contra o qual a cascata de visão verifica, e não extrai
 * (doc §6.6): o sistema já sabe qual código de barras e qual lote deveriam estar na caixa,
 * o que torna viável o passo de OCR com fuzzy match que uma extração cega não sustentaria.
 *
 * Os nomes de campo seguem o WMS de produção da AGV, não uma nomenclatura inventada: a
 * linha de separação real chega no app de RF como
 * `praca, pedido, data, tipo, produto, prod_des, ean, dun14, partida, andar, predio, rua,
 * cd, dir_stage, ua` + `SUM(qtd - qtd_sep) AS qtd`, com `codbarra` e `senha_endereco`
 * resolvidos por endereço. Quando a `HttpPickingRepository` existir, o mapeamento vai ser
 * campo a campo em vez de tradução.
 */
data class Linha(
    /**
     * Código do produto no cadastro (`wmsprodu.cod`) — numérico curto, ~6 dígitos.
     *
     * Substitui o `sku` do rascunho: "SKU" não é palavra que o WMS use, e o app de RF
     * aceita o próprio `produto` como leitura válida do código de barras.
     */
    val produto: String,
    /** Descrição do cadastro (`wmsprodu.des`). */
    val descricao: String,
    val endereco: Endereco,
    /**
     * Senha da posição (`wmscam2.senha_endereco`) — o check digit de dois dígitos do
     * doc §7.1.
     *
     * **Não é derivável do endereço** de propósito: se fosse função de cd/setor/andar/
     * prédio/rua, o operador aprenderia a fórmula e confirmaria sem chegar lá. No WMS ela
     * vem cadastrada por endereço e é comparada literalmente com o que o operador informa.
     */
    val senhaEndereco: String,
    /**
     * EAN-13 do produto (`wmsprodu.ean`) — a GS1 AI (01) do doc §6.5 no nível de unidade.
     */
    val ean: String,
    /**
     * DUN-14 do produto (`wmsprodu.dun14`) — o código de barras de caixa/pallet.
     *
     * Modelado junto com o [ean] e não deixado como "problema futuro" porque o WMS trata os
     * dois como leituras igualmente válidas da mesma linha: a validação de produto do RF
     * aceita `produto`, `ua`, `ean` **ou** `dun14`. Uma cascata de decodificação que só
     * conhecesse o EAN rejeitaria caixa fechada — que é exatamente o caso de uso de
     * separação por voz com as mãos livres.
     */
    val dun14: String,
    /**
     * Lote (`wmsesto2.partida`) — numérico, ~8 dígitos. É a GS1 AI (10) do doc §6.5.
     *
     * O nome `partida` é o do WMS; `lote` é como a operação fala. O campo carrega os dois
     * papéis: é o valor comparado com o lote lido da embalagem e a origem dos dois últimos
     * dígitos do check digit de produto (doc §7.2).
     */
    val partida: String,
    /** GS1 AI (21). Não vem na linha de separação do WMS; é lido da etiqueta. */
    val serie: String,
    /** GS1 AI (17). Não vem na linha de separação do WMS; é lido da etiqueta. */
    val validade: LocalDate,
    /** `SUM(qtd - qtd_sep)` — o que ainda falta separar nesta linha. */
    val quantidade: Int,
    /** Unidade de armazenagem (`wmsesto2.ua`) — numérico, ~8 dígitos. */
    val ua: String,
    /** `wmsesto2.recnum` — identifica a linha física, o que distingue rateio de duplicata. */
    val recnum: String,
    /** Saldo do endereço — alimenta o `maximoPlausivel` do reranking de quantidade. */
    val saldoEndereco: Int,
    /**
     * Endereço de stage de destino (`wmsesto2.dir_stage`) — o "compartimento" do doc §11.2.
     */
    val dirStage: String,
)
