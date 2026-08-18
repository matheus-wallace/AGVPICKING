## Why

Em bancada real hoje (17/08/2026, 18:44–20:00, log completo do dispositivo SM-G780F em
`ReconhecedorDeComando`), três pontos do fluxo de voz mostraram falha recorrente: o check
digit ("47"/"quarenta e sete") raramente é aceito de primeira, "próximo" no registro de
avaria quase nunca é reconhecido, e a impressão de que "iniciar" também está ruim. Lendo o
log linha a linha (não a impressão do operador, os eventos reais do reconhecedor) dois
problemas de causa raiz distinta ficaram evidentes, e um terceiro não se confirmou — este
change ataca os dois primeiros com o rigor que os dados permitem, e documenta o terceiro
como investigação sem fix, em vez de aplicar uma correção às cegas.

## What Changes

- **Check digit continua só dígito a dígito (extenso tentado e revertido)**:
  `AguardandoCheckDigit` aceitava só a leitura dígito a dígito ("quatro sete"), e a
  hipótese era que aceitar também "quarenta e sete" (extenso de 0 a 99, espelhando
  `ConfirmandoQuantidade`) resolveria a recusa de primeira tentativa. O extenso chegou a
  ser implementado e foi **revertido na mesma bancada de 17/08/2026**, com voz real e
  ruído de fundo: mesmo dito grudado, "quarenta e sete" registrava como "quarenta"
  sozinho, e as ~30 palavras de dezena somadas à gramática pioraram o que já funcionava —
  "quatro" foi revisado no meio da fala para "quatrocentos", palavra que só existia ali
  por causa do extenso. O que fica é a gramática menor: dígito a dígito, dois algarismos
  exatos. Ver design.md - Decisão 1.
- **BREAKING: quantidade também perde a leitura por extenso** — extensão da decisão
  acima, não um novo defeito de bancada: `ConfirmandoQuantidade` aceitava "doze" (extenso)
  ou "um dois" (dígito a dígito) para o mesmo valor, e a leitura por extenso **não falhou**
  nesta rodada de bancada. Na mesma conversa em que o check digit foi revertido, Matheus
  pediu para remover extenso de toda leitura numérica do app, por consistência e para não
  esperar o mesmo problema aparecer aqui depois. `VocabularioDeVoz.numero()`/`QUANTIDADES`
  saem do código; `numeroDigitoADigito()` é a única leitura de quantidade que resta. Ver
  design.md - Decisão 7.
- **Calibração de bancada do perfil `DIGITOS`**: o log real mostra a elocução de dois
  dígitos sendo cortada no meio com frequência (~6 descartes por sucesso), mesmo com o
  perfil já pensado para tolerar a micropausa (700 ms). Uma tarefa de bancada mede a taxa
  de sucesso de "quatro sete" numa única elocução em valores maiores de
  `silencioFinalMs` via `ajustes-asr.properties` (sem recompilar), antes de decidir se o
  default de `PerfilEndpoint.DIGITOS` muda.
- **`TratandoExcecao` fecha a gramática**: hoje é o único estado com vocabulário aberto,
  pensado para aceitar um relato falado livre. O log mostra o decodificador aberto
  errando repetidamente ("prós", "aqui", "faria", "o próximo") onde todo o resto do app
  (gramática fechada) acerta de primeira. Como `PickingEvent.ExcecaoRegistrada` nunca
  carregou o texto do relato — nada no domínio armazena ou consome o conteúdo da fala
  livre —, o vocabulário aberto não comprava funcionalidade real, só instabilidade.
  **BREAKING** (dentro do escopo deste app, não uma API externa): remove a aceitação de
  relato falado livre de três ou mais palavras; `TratandoExcecao` passa a aceitar apenas
  "próximo" e os transversais, igual a qualquer outro estado de comando curto. A saída por
  toque ("Registrar ocorrência e seguir") continua existindo e é o caminho para relatar
  detalhes.
- **"Iniciar" investigado, sem fix aplicado**: a única ocorrência de "iniciar" no log de
  bancada de hoje funcionou de primeira tentativa; em todas as outras vezes o operador já
  estava usando "próximo" (sinônimo aditivo existente), que também funcionou de primeira
  sempre. Nenhuma mudança de código é proposta para esse item — uma tarefa de bancada
  pede confirmação dizendo "iniciar" especificamente antes de assumir que existe um
  problema aqui distinto do resto do fluxo.
- **Pós-processamento de áudio novo**: `AudioMicrofoneSimulado` hoje só liga
  `AcousticEchoCanceler` (desligado por padrão) e um `ganho` estático que o próprio código
  já documenta como incapaz de melhorar SNR. Passa a também oferecer `NoiseSuppressor` e
  `AutomaticGainControl` — efeitos nativos do Android, mesma API do AEC já usado —, como
  novos toggles bench-calibráveis em `AjustesAsr`/`ajustes-asr.properties`, sem
  recompilar.

## Capabilities

### New Capabilities

(nenhuma)

### Modified Capabilities

- `state-driven-voice-flow`: dois requisitos mudam — "Check digit falado é validado
  localmente" tem o texto ajustado para deixar explícito que a leitura é só dígito a
  dígito (a leitura por extenso foi tentada e revertida em bancada, e a mesma regra se
  estende a `ConfirmandoQuantidade`), e "A ocorrência tem saída por voz e por toque" perde
  o relato livre (a saída por voz em `TratandoExcecao` passa a ser só "próximo", igual às
  demais).

## Impact

- `audio/VocabularioDeVoz.kt`: sem mudança líquida no check digit — a função de leitura
  por extenso de 0–99 (`checkDigitExtenso`, com a guarda `comecaEmDezena`) foi escrita e
  removida após a bancada; `digitos` segue sendo a única leitura do check digit.
- `audio/SeletorDeEscuta.kt`: gramática de `AguardandoCheckDigit` permanece
  `DIGITOS + TRANSVERSAIS` (as palavras de dezena entraram e saíram, com o motivo
  registrado em comentário); gramática de `TratandoExcecao` deixa de ser aberta
  (`ConfiguracaoDeEscuta` com `palavras = comando(PROXIMO)`, perfil `COMANDO_CURTO`).
- `audio/InterpretadorDeFala.kt`: `AguardandoCheckDigit` segue só com a leitura dígito a
  dígito de dois algarismos (o encadeamento extenso-primeiro foi revertido);
  `TratandoExcecao` deixa de aceitar frase de três ou mais palavras, só "próximo" exato.
  `PALAVRAS_MINIMAS_DO_RELATO` sai se ficar sem uso.
- `audio/PerfilEndpoint.kt`: `TEXTO_LIVRE` fica sem nenhum estado que o use e passa a ser
  documentado assim (não é removido — a fatia de relato via LLM do doc §5.4 volta a
  precisar dele). Possível mudança de `silencioFinalMs` de `DIGITOS` condicionada ao
  resultado da tarefa de calibração de bancada.
- `ui/operation/DicaDeComandoDeVoz.kt`, `ui/operation/ProjetorDeOperacao.kt` e
  `audio/output/ProjetorDeFalaPicking.kt`: os três textos que mandavam o operador
  "descrever a ocorrência" passam a pedir "próximo". Sem isso a tela e a fala instruiriam
  um caminho que a gramática fechada não aceita mais — é consequência direta da mudança,
  não escopo novo. A ação de toque continua sendo a via do detalhe.
- `audio/AjustesAsr.kt`: dois novos campos bench-calibráveis (`supressaoDeRuido`,
  `controleAutomaticoDeGanho`), lidos do mesmo `ajustes-asr.properties`.
- `audio/AudioMicrofoneSimulado.kt`: liga `NoiseSuppressor`/`AutomaticGainControl` quando
  disponíveis no aparelho e pedidos pelos ajustes, mesmo padrão de
  `ligarCancelamentoDeEco` (indisponibilidade não é erro, sem efeito colateral no fluxo).
- `audio/VocabularioDeVoz.kt`: `numero()`, a tabela `VALOR_NUMERO` e o `val QUANTIDADES`
  saem inteiros (Decisão 7) — nada mais os chamava fora de `ConfirmandoQuantidade` e dos
  próprios testes. Novo `DIGITOS_EM_QUANTIDADE` (espelha `VALOR_DIGITO_EM_QUANTIDADE`, sem
  "meia") alimenta a gramática no lugar de `QUANTIDADES`.
- `audio/SeletorDeEscuta.kt`: gramática de `ConfirmandoQuantidade` passa de `QUANTIDADES` (0
  a 999 por extenso) para `DIGITOS_EM_QUANTIDADE` (10 palavras).
- `audio/InterpretadorDeFala.kt`: `ConfirmandoQuantidade` perde o encadeamento
  `numero() ?: numeroDigitoADigito()`, fica só com `numeroDigitoADigito()`.
- `ui/operation/OperationUiState.kt`, `OperationViewModel.kt`, `OperationScreen.kt`,
  `ProjetorDeOperacao.kt`: fora do escopo original desta proposta, mas motivado pela mesma
  sessão de bancada — `AguardandoOrdem` nunca teve uma via de confirmação na tela principal
  (só existia no painel de dev), e é um estado deliberadamente surdo por voz (design.md do
  fluxo original — Decisão 4), então o operador ficava preso ali sem nenhuma forma de
  seguir. Novo campo `podeConfirmarOrdem` + botão "Confirmar ordem" espelhando o padrão já
  existente de `podeRegistrarOcorrencia`. Corrigido também `aguardandoVoz = true` nesse
  mesmo estado, que anunciava escuta que nunca existiu (`SeletorDeEscuta` sempre devolveu
  `null` ali).
- `openspec/changes/add-operator-feedback-improvements/specs/state-driven-voice-flow/spec.md`
  e `openspec/changes/add-state-driven-voice-flow/specs/state-driven-voice-flow/spec.md`:
  a Decisão 12 / requisito de relato livre que introduziu o vocabulário aberto de
  `TratandoExcecao` é revertida por este change, não apenas o código.
- Nenhum novo teste de instrumentação — cobertura por teste de JVM (as três funções
  puras) mais confirmação de bancada com voz humana real, igual às fatias anteriores
  deste projeto.
