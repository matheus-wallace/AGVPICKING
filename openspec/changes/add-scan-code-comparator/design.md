## Context

Ver `proposal.md` - Why. Hoje `ValidandoContraDados` é alcançado por dois caminhos distintos do reducer, ambos automáticos:

1. **Câmera** (`EscaneandoProduto`/`DecodificandoProduto`/`VerificacaoAssistida` + `DecodificacaoConcluida`): `codigoLido` é o texto decodificado pelo ML Kit — hoje sempre um EAN-13, único formato validado ponta-a-ponta em bancada (`add-vision-stream-decode-slice`, tarefa 6.3).
2. **Fallback de check digit do produto** (`AguardandoCheckDigit(tipo=PRODUTO)` + `CheckDigitCorreto`): `codigoLido` é o sentinela `CODIGO_CHECK_DIGIT_PRODUTO = "CHECK_DIGIT_VOZ"` (`PickingReducer.kt:105`), não um código lido de verdade — o produto já foi confirmado pelo check digit falado (doc §7.2).

Os dois precisam publicar `ValidacaoOk`/`ValidacaoDivergente` para o ator sair de `ValidandoContraDados`, e hoje só o botão "Validação OK" do painel de dev faz isso. O projeto já tem dois precedentes de produtor que observa `PickingActor.state` e publica de volta: `ResolvedorDeIntencao` (voz, com guarda contra estado obsoleto) e `ControladorDeVisao` (câmera, liga/desliga por estado).

## Goals / Non-Goals

**Goals:**
- Publicar `ValidacaoOk`/`ValidacaoDivergente` automaticamente para os dois caminhos que levam a `ValidandoContraDados`, sem exigir toque no painel.
- Comparação determinística e literal — nunca confirmação cega (mesmo princípio da Decisão 5 de `add-state-driven-voice-flow`).
- Guardar contra corrida: se o ator sair de `ValidandoContraDados` antes da consulta ao repositório terminar, o resultado obsoleto não deve ser publicado.

**Non-Goals:**
- Parsing GS1 (doc §6.5) ou comparação de DataMatrix/CODE_128 — a caixa de bancada ainda não tem DataMatrix real (achado registrado em `add-vision-stream-decode-slice`), e nenhum produtor hoje decodifica esses formatos ponta-a-ponta. Um código nesses formatos simplesmente diverge do EAN esperado e vai para `TratandoExcecao` — comportamento seguro, não um bug desta fatia.
- Verificação assistida por VLM (doc §6.4) — passo de rede, fora de escopo.
- Qualquer mudança no reducer, no contrato de `PickingEvent` ou na cascata de captura/decodificação — todos já existem e ficam intocados.
- Remover os botões do painel de dev — continuam como atalho de diagnóstico (mesmo padrão já estabelecido para os botões de check digit e decodificação).

## Decisions

1. **Novo produtor dedicado (`vision/ComparadorDeCodigo.kt`), não lógica dentro de `ControladorDeVisao`.** Mistura responsabilidade de sensor (ligar câmera, decodificar) com responsabilidade de domínio (consultar repositório, decidir OK/divergente) seria o mesmo erro que `ResolvedorDeIntencao` evitou para voz — o precedente do projeto já separa "quem produz o dado bruto" de "quem decide o evento de domínio". Alternativa rejeitada: adicionar a comparação ao final do callback de `ControladorDeVisao` — mais simples à primeira vista, mas acopla o componente de câmera ao `PickingRepository` e duplicaria a lógica quando o fallback de check digit de produto precisar do mesmo comparador.

2. **Observa `PickingActor.state` via `collectLatest`, roda em `Dispatchers.Default`.** Sem hardware envolvido (ao contrário de `ReconhecedorDeComando`/Vosk), não há necessidade de thread dedicada — mesma classe de trabalho que `ResolvedorDeIntencao` já faz em corrotina suspensa comum. `collectLatest` cancela automaticamente uma consulta em andamento se o estado mudar antes dela terminar, que é a mesma garantia que a guarda explícita do item 3 reforça no nível do evento publicado.

3. **Guarda dupla contra estado obsoleto: verificação por igualdade de estado, não por versão numérica.** Antes de publicar o evento, o comparador confere se `PickingActor.state.value` ainda é o mesmo `ValidandoContraDados` (mesma instância de dado, comparável por `equals` de data class) que disparou a consulta. Alternativa rejeitada: contador de versão (`AtomicLong`) como em `PublicadorDeVoz` — ali a versão existe porque há uma thread de áudio dedicada fora do controle do dispatcher da corrotina; aqui a única fonte de mudança concorrente é o próprio ator processando eventos sequencialmente, então comparar o estado antes/depois da suspensão é suficiente e mais simples.

4. **Sentinela do check digit de produto nunca é comparado como EAN.** `CODIGO_CHECK_DIGIT_PRODUTO` é verificado primeiro, com curto-circuito para `ValidacaoOk` direto — sem essa checagem, o fallback do doc §7.2 (câmera indisponível, check digit falado confirma o produto) sempre divergiria contra o EAN esperado, porque `"CHECK_DIGIT_VOZ"` nunca é um EAN válido. Esse é o motivo pelo qual a comparação não pode ser um simples `codigoLido == linha.ean` sem essa exceção — foi a armadilha que motivou registrar esta decisão explicitamente em vez de deixar implícita no código.

5. **Comparação sempre literal contra `linha.ean`, independente do formato que o ML Kit reportou.** `LeitorDeCodigo`/`DecodificacaoConcluida` não carregam a informação de qual formato casou (EAN-13, CODE_128 ou DATA_MATRIX) — só o texto. Comparar literalmente contra o EAN esperado é seguro por padrão: um código de outro formato que por acaso tiver o mesmo texto do EAN é aceito (correto), qualquer outra coisa diverge e vai para exceção, nunca é aceito às cegas.

## Risks / Trade-offs

- **[Risco]** Um código de formato diferente do EAN-13 (ex.: DataMatrix de uma caixa hospitalar) sempre diverge, mesmo sendo genuinamente o produto certo, porque o comparador não faz parsing GS1. → **Mitigação:** aceito por não-goal; a bancada atual não tem DataMatrix real para validar esse caminho, e o texto do EAN já é o único formato provado em produção nesta fase.
- **[Risco]** Esquecer o curto-circuito do sentinela do check digit de produto (Decisão 4) quebraria silenciosamente um fallback que hoje já funciona por voz. → **Mitigação:** requisito e cenário dedicados no spec (`Fallback de check digit de produto não é comparado como EAN`), mais teste de unidade explícito cobrindo esse caminho.
- **[Risco]** Item em andamento não encontrado no repositório (não deveria acontecer, já que o item vem do próprio ator) → **Mitigação:** mesmo padrão defensivo de `ResolvedorDeIntencao`: se a linha não for encontrada, nenhum evento é publicado, e o log estruturado registra a tentativa — não trava o app, mas também não finge sucesso.
