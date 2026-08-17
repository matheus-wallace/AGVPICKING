## Context

Ver `proposal.md` - Why. `ProjetorDeOperacao.projetar()` já faz um `when` exaustivo sobre `PickingState` para montar `OperationUiState`, e já tem um `when` irmão menor (`situacao()`) que resume o estado de sessão em texto ("Sessão ativa", "Sessão pausada"...). `EtapaOperacao` é um enum de 4 valores usado só para escolher qual `Conteudo*` renderizar — nunca virou texto visível.

## Goals / Non-Goals

**Goals:**
- Um texto por `PickingState` (não por `EtapaOperacao`) identificando o que o operador está fazendo agora, granular o suficiente para distinguir os quatro estados que hoje compartilham o cartão `QUANTIDADE`.
- Exibir esse texto de forma proeminente no cabeçalho da tela, junto de `progresso`/`situacao`.

**Non-Goals:**
- Não é um stepper visual com barra de progresso ou ícones — só texto, como `progresso`/`situacao` já são hoje. Um stepper gráfico é um upgrade de UI possível depois, não desta fatia.
- Não muda `EtapaOperacao` nem o roteamento de qual `Conteudo*` a tela renderiza.
- Não muda `ProjetorDeFalaPicking`/TTS — a fala tem suas próprias mensagens de estado, sem relação com este rótulo visual.

## Decisions

1. **Novo campo `String`, preenchido dentro do `when` exaustivo já existente em `projetar()` — não um `when` auxiliar novo, nem um enum espelhando `PickingState`.** `projetar()` já faz `when (estado)` sem `else` sobre o `PickingState` sealed, e o compilador já força cada branch a existir (mesma garantia que o resto do projeto usa para correção — ver `SeletorDeEscuta`/`PickingReducer`). Adicionar `nomeEtapa` como mais um argumento de cada `base.copy(...)`/`base.mensagem(...)` já existente aproveita essa exaustividade de graça: esquecer um estado novo quebra a build, não silenciosamente perde um rótulo. Alternativas rejeitadas: (a) um `when` auxiliar separado tipo `situacao()` — duplicaria os ~20 branches sem ganhar nada, e `situacao()` usa `else` justamente porque agrupa por status de sessão, não por estado individual; (b) um enum novo espelhando `PickingState` 1:1 — a tela nunca faz `when` exaustivo sobre esse rótulo (é só texto exibido), então o enum só adicionaria uma camada de tradução sem benefício de tipo.

2. **Renderizado no cabeçalho, não dentro do cartão.** Cada `Conteudo*` já tem seu próprio subtítulo de seção ("Endereço"/"Produto"/"Quantidade") — repetir a etapa lá dentro seria redundante com esses três títulos, que continuam existindo. O cabeçalho já mistura "onde estou na ordem" (`progresso`, item X de N) com "status da sessão" (`situacao`); "o que estou fazendo agora" é a peça que faltava ali.

3. **Um texto por combinação estado+`tipo`, não só por classe de estado.** `AguardandoCheckDigit` tem dois sub-casos (`TipoCheckDigit.POSICAO`/`PRODUTO`) com significado operacional bem diferente — confirmar que chegou no endereço certo vs. confirmar o produto pelo lote quando a câmera falhou. Um rótulo só ("Confirmando check digit") esconderia justamente a distinção que motivou este ajuste.

## Risks / Trade-offs

- **[Risco]** Os estados de mensagem (`Ocioso`, `Registrando`, `PreparandoSessao`, `AguardandoOrdem`, `OrdemCarregada`, `TratandoExcecao`, `ConferenciaFinal`, `OrdemConcluida`, `SessaoPausada`, `Erro`) passam hoje pelo helper comum `OperationUiState.mensagem(...)` (`ProjetorDeOperacao.kt`), que já fixa `etapa = EtapaOperacao.MENSAGEM` para todos eles. Adicionar `nomeEtapa` como parâmetro desse helper, em vez de cada chamada, é conveniente mas reintroduziria um ponto único capaz de esquecer a distinção por estado — a assinatura do helper precisa exigir o parâmetro (sem default), não inferir um texto genérico. **Mitigação:** tarefa 1.2 pede explicitamente um `nomeEtapa` obrigatório (sem valor padrão) no helper `mensagem()`, para que o compilador continue forçando uma escolha em cada um dos dez `call sites`.
