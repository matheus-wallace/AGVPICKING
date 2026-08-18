## 1. Check digit por extenso — implementado e REVERTIDO na bancada de 17/08/2026

> **Revertido.** As tarefas abaixo ficam marcadas porque foram de fato executadas, mas o
> código que elas produziram não existe mais: a bancada com voz real e ruído de ventilador
> mostrou "quarenta e sete" registrando como "quarenta" sozinho mesmo dito grudado, e as
> ~30 palavras de dezena somadas à gramática degradaram a leitura dígito a dígito que já
> funcionava ("quatro" revisado no meio da fala para "quatrocentos"). `checkDigitExtenso`,
> `comecaEmDezena`, `CHECK_DIGIT_POR_EXTENSO`, `MENOR_DEZENA`, a ampliação da gramática em
> `SeletorDeEscuta` e o encadeamento extenso-primeiro em `InterpretadorDeFala` foram
> removidos; os testes de JVM correspondentes viraram travas contra reintroduzir o extenso.
> O check digit é, e continua sendo, só dígito a dígito. Ver design.md - "Decisão 1
> revertida".

- [x] 1.1 Adicionar `VocabularioDeVoz.checkDigitExtenso(texto): String?` — chama `numero()`,
      valida o intervalo 0..99, formata como string de 2 dígitos com zero à esquerda
      (`"%02d".format(it)`), `null` fora do intervalo ou se `numero()` já devolveu `null`.
- [x] 1.2 Testes de JVM para `checkDigitExtenso`: "quarenta e sete" -> "47", "sete" sozinho
      -> `null` (ambíguo, ver design.md - Decisão 1), "cento e vinte" -> `null` (fora de
      0..99), texto vazio -> `null`.
- [x] 1.3 Ampliar a gramática de `AguardandoCheckDigit` em `SeletorDeEscuta` para incluir
      `VocabularioDeVoz.QUANTIDADES` (dezenas + "e") além de `DIGITOS`, mantendo
      `TRANSVERSAIS`.
- [x] 1.4 Em `InterpretadorDeFala.interpretar` para `AguardandoCheckDigit`, tentar
      `checkDigitExtenso` primeiro e `digitos` (dígito a dígito, filtrado por
      `length == DIGITOS_DO_CHECK_DIGIT`) depois — mesma ordem de prioridade usada em
      `ConfirmandoQuantidade` entre `numero` e `numeroDigitoADigito`.
- [x] 1.5 Testes de JVM para o `InterpretadorDeFala` atualizado: "quarenta e sete" e
      "quatro sete" no mesmo estado produzem o mesmo `CheckDigitFalado("47")`; "sete"
      sozinho continua sem produzir intenção.

## 2. Fechar a gramática de `TratandoExcecao`

- [x] 2.1 Em `SeletorDeEscuta.para`, trocar `TratandoExcecao` de
      `ConfiguracaoDeEscuta(palavras = emptyList(), perfil = TEXTO_LIVRE)` para
      `comando(VocabularioDeVoz.PROXIMO)` (gramática fechada, perfil `COMANDO_CURTO`),
      igual ao padrão dos demais estados de avanço de uma palavra.
- [x] 2.2 Em `InterpretadorDeFala.interpretar` para `TratandoExcecao`, remover a condição
      `VocabularioDeVoz.palavras(normalizado).size >= PALAVRAS_MINIMAS_DO_RELATO`,
      mantendo só o match exato de `PROXIMO`.
- [x] 2.3 Remover `PALAVRAS_MINIMAS_DO_RELATO` se, depois de 2.2, não sobrar nenhum uso no
      arquivo.
- [x] 2.4 Testes de JVM: "próximo" em `TratandoExcecao` produz `ExcecaoRegistrada`; uma
      frase de 3+ palavras (o antigo caminho de relato) deixa de produzir evento; o
      transversal "avaria" dito de novo nesse estado continua sem produzir evento (não é
      um transversal válido dentro do próprio `TratandoExcecao`, mesmo comportamento de
      hoje).
      **Correção:** a premissa sobre "avaria" estava errada. `TratandoExcecao.ehOperacional`
      é `true`, então `InterpretadorDeFala.transversal` sempre produziu — e continua
      produzindo — `ExcecaoSolicitada(AVARIA)` ali; o reducer só reentra no mesmo estado. O
      teste registra o comportamento real (inalterado por este change) em vez da premissa.
- [x] 2.5 Conferir `PerfilEndpoint.TEXTO_LIVRE` — se, depois desta mudança, nenhum estado
      mais o usa, decidir com o restante do arquivo se o valor fica documentado como não
      utilizado ou é removido (não remover a `enum` inteira sem confirmar, pois pode ser
      reaproveitada por uma fatia futura de relato via LLM, doc §5.4).
      **Decidido:** fica, documentado como sem estado que o use. A tabela do enum espelha o
      doc §5.1; apagar a linha faria o código deixar de espelhar o documento. Mesma nota em
      `ConfiguracaoDeEscuta.aberta`, que também ficou sem nenhum estado que a acione.

## 3. Pós-processamento de áudio (`NoiseSuppressor` / `AutomaticGainControl`)

- [x] 3.1 Adicionar `supressaoDeRuido: Boolean` e `controleAutomaticoDeGanho: Boolean` a
      `AjustesAsr`, com leitura de `ajustes-asr.properties` seguindo o mesmo padrão dos
      campos booleanos existentes (`propriedades.booleano(...)`), default `false` para os
      dois — mesma cautela já aplicada ao AEC (design.md - Decisão 5).
- [x] 3.2 Em `AudioMicrofoneSimulado`, ligar `NoiseSuppressor.create(audioSessionId)` e
      `AutomaticGainControl.create(audioSessionId)` quando os respectivos ajustes pedirem
      e `.isAvailable()` confirmar, mesmo molde de `ligarCancelamentoDeEco` (log quando
      indisponível, sem lançar exceção, `.release()` no `finally` junto do `aec`).
- [x] 3.3 Atualizar o log de abertura de captura (`"Captura aberta: ..."`) para incluir os
      dois novos estados, mesmo padrão do `aec=${aec != null}` já logado.

## 4. Quantidade só dígito a dígito — extensão da Decisão 1 revertida, não novo defeito de bancada

> A leitura por extenso de `ConfirmandoQuantidade` ("doze") não falhou nesta rodada de
> bancada — Matheus pediu a remoção por consistência, na mesma conversa em que o check
> digit foi revertido: "vamos remover extenso de tudo, para números vamos falar somente
> por dígitos". Ver design.md - Decisão 7.

- [x] 4.1 Remover `VocabularioDeVoz.numero()`, a tabela `VALOR_NUMERO` e o `val
      QUANTIDADES` — nada mais os chamava fora de `ConfirmandoQuantidade` e dos próprios
      testes. Remover `CONECTIVO`/"e" também, se nada mais depender dele.
- [x] 4.2 Adicionar `VocabularioDeVoz.DIGITOS_EM_QUANTIDADE: List<String>`, espelhando
      `VALOR_DIGITO_EM_QUANTIDADE` (dígitos sem "meia") como `DIGITOS` já espelha
      `VALOR_DIGITO`.
- [x] 4.3 Em `SeletorDeEscuta.para` para `ConfirmandoQuantidade`, trocar a gramática de
      `QUANTIDADES + TRANSVERSAIS` para `DIGITOS_EM_QUANTIDADE + TRANSVERSAIS`.
- [x] 4.4 Em `InterpretadorDeFala.interpretar` para `ConfirmandoQuantidade`, remover o
      encadeamento `VocabularioDeVoz.numero(normalizado) ?:`, ficando só com
      `VocabularioDeVoz.numeroDigitoADigito(normalizado)`.
- [x] 4.5 Atualizar/remover os testes de JVM que exercitavam `numero()`/leitura de
      quantidade por extenso em `VocabularioDeVozTest.kt`, `InterpretadorDeFalaTest.kt` e
      `SeletorDeEscutaTest.kt`, preservando a cobertura de `numeroDigitoADigito` e dos
      limites de quantidade (1..999, ruptura para zero).
- [x] 4.6 Atualizar o fixture de bancada `PublicadorDeVozTest.kt` (`LinhaDeBancada`), que
      usava `"doze"`/`"trinta"` como `quantidadeFalada` — trocar para a leitura dígito a
      dígito equivalente (`"um dois"`, `"três zero"`) para o teste de ponta a ponta
      continuar passando. **Achado durante a implementação**: esse arquivo não estava na
      lista original de testes a revisar (o agente responsável por 4.1–4.5 esgotou o
      limite de sessão antes de chegar nele) — a suíte ficou com 1 falha real até este
      ajuste ser aplicado à mão.

## 5. Specs e artefatos anteriores

- [x] 5.1 Revisar `openspec/changes/add-operator-feedback-improvements/specs/state-driven-voice-flow/spec.md`
      e `openspec/changes/add-state-driven-voice-flow/specs/state-driven-voice-flow/spec.md`
      — confirmar que a delta deste change (MODIFIED de "Check digit falado é validado
      localmente" e "A ocorrência tem saída por voz e por toque") reflete corretamente o
      texto final depois de 1.x e 2.x implementados; ajustar a delta se o código divergir
      do que foi especificado.
      **Resultado:** as duas MODIFIED carregam o texto completo das versões anteriores mais
      a mudança, então nada nos dois arquivos antigos precisou de ajuste.
      **Atualizado após a reversão do extenso:** a MODIFIED do check digit passou a
      descrever o comportamento final e verdadeiro — leitura só dígito a dígito, com a
      gramática mínima do estado como parte do requisito. Os cenários de extenso saíram e
      no lugar ficaram "Leitura dígito a dígito preserva o valor e o zero à esquerda" e
      "Palavra de dezena não é check digit"; o histórico da tentativa está em design.md -
      "Decisão 1 revertida", não na prosa da spec. **Atualizado de novo após o grupo 4**: a
      mesma MODIFIED ganhou uma frase estendendo a regra a `ConfirmandoQuantidade`.
- [x] 5.2 `openspec validate --strict` no change completo.

## 6. Verificação

- [x] 6.1 `./gradlew testDebugUnitTest` — contar os testes nos XMLs
      (`app/build/test-results/testDebugUnitTest/*.xml`) em vez de confiar só no
      resumo do Gradle, seguindo a prática já estabelecida neste projeto.
      **205 `<testcase>` em 26 XMLs, nenhum `<failure>` nem `<error>`.**
      **Após a reversão do extenso do check digit: 206 `<testcase>` em 27 XMLs, 0 falhas.**
      **Após a reversão do extenso da quantidade (grupo 4) + o ajuste do fixture de 4.6:
      206 `<testcase>` em 27 XMLs, 0 falhas e 0 erros** — número líquido igual ao anterior
      (testes de extenso saíram, travas equivalentes entraram), mas passou por 1 falha real
      no meio do caminho até o fixture de `PublicadorDeVozTest.kt` ser corrigido.
- [x] 6.2 `./gradlew assembleDebug lintDebug` limpos.
      **16 warnings de lint, todos pré-existentes** (`libs.versions.toml`,
      `AndroidManifest.xml`, `MockDeviceBootstrap.kt`, `gradle-wrapper.properties`,
      `ic_launcher.jpg`) — nenhum arquivo tocado por este change.
- [x] 6.3 Instalar no SM-G780F (`./gradlew installDebug`). **Instalado** (reinstalado
      várias vezes ao longo da bancada, a última após o grupo 4) — o aparelho estava
      conectado (`RQ8NB02BZCD`, Android 13). O `ajustes-asr.properties.exemplo` já traz as duas
      chaves novas para o `adb push` da tarefa 7.6.

## 7. Bancada (Matheus, com voz humana real)

- [ ] 7.1 Check digit: repetir o mesmo valor de 2 dígitos várias vezes dígito a dígito
      ("quatro sete"), lendo `ASR[AguardandoCheckDigit]` no logcat. Registrar a taxa de
      sucesso de primeira tentativa. (A comparação com a leitura por extenso saiu do
      escopo: o extenso foi revertido — ver a nota do grupo 1 e design.md.)
- [ ] 7.2 Avaria: chegar em `TratandoExcecao` e dizer "próximo" várias vezes, lendo
      `ASR[TratandoExcecao]` no logcat. Confirmar que passa a reconhecer de primeira
      tentativa, igual aos demais estados de gramática fechada.
- [ ] 7.3 Calibração de `PerfilEndpoint.DIGITOS` (design.md - Decisão 3): com
      `ajustes-asr.properties`, testar `silencioFinalMs` em pelo menos dois valores acima
      de 700 (por exemplo 900 e 1100) para o check digit dígito a dígito, medindo a
      proporção de elocuções que saem com os dois dígitos juntos versus cortadas ao
      meio. Se um valor for conclusivamente melhor, abrir uma tarefa de código separada
      (não incluída aqui) para mudar o default em `PerfilEndpoint.DIGITOS`.
- [ ] 7.4 "Iniciar" (design.md - Decisão 4): em `OrdemCarregada`, dizer "iniciar"
      especificamente (não "próximo") várias vezes seguidas, lendo
      `ASR[OrdemCarregada]` no logcat. Registrar se reproduz alguma falha; se sim,
      colar o trecho relevante do log nesta tarefa antes de decidir se vira um change
      novo. Se não reproduzir, marcar como investigado sem causa encontrada e encerrar.
- [ ] 7.5 Isolar o efeito da degradação de canal simulada (design.md - Decisão 6): com
      `ajustes-asr.properties`, testar `degradarCanal=false` e repetir check digit e
      avaria, comparando a taxa de sucesso contra o default (`degradarCanal=true`).
      Registrar se a degradação simulada é uma parcela relevante da instabilidade
      observada sem os óculos físicos.
- [ ] 7.6 `NoiseSuppressor`/`AutomaticGainControl` (design.md - Decisão 5): com
      `ajustes-asr.properties`, testar `supressaoDeRuido=true` e
      `controleAutomaticoDeGanho=true`, separadamente e juntos, medindo taxa de sucesso
      de check digit e avaria contra o baseline (ambos `false`). Só muda o default de
      produção em `AjustesAsr` se algum resultado for conclusivamente melhor.

## 8. Botão "Confirmar ordem" na tela principal — fora do escopo original, mesma sessão de bancada

> `AguardandoOrdem` nunca teve uma via de confirmação fora do painel de dev, e é
> deliberadamente surdo por voz (design.md do fluxo original — Decisão 4). O operador
> ficava preso na tela principal depois de encerrar uma ordem, sem forma de carregar a
> próxima. Motivado pela mesma bancada, não pelos três achados originais desta proposta.

- [x] 8.1 Novo campo `OperationUiState.podeConfirmarOrdem`, `true` só em `AguardandoOrdem`.
- [x] 8.2 `ProjetorDeOperacao`: `AguardandoOrdem` ganha `.copy(podeConfirmarOrdem = true)`
      e perde o `aguardandoVoz = true` que anunciava escuta inexistente.
- [x] 8.3 `OperationViewModel.confirmarOrdem()`, publicando `PickingEvent.OrdemConfirmada`
      a partir da ordem já carregada em `ordemFlow`.
- [x] 8.4 `OperationScreen`: botão "Confirmar ordem" condicional, mesmo padrão do botão de
      `podeRegistrarOcorrencia`.
- [x] 8.5 Testes de JVM (`ProjetorDeOperacaoTest`, novo `OperationViewModelTest`) e
      verificação (`testDebugUnitTest`/`assembleDebug`/`lintDebug`/`installDebug`) —
      cobertos nas contagens consolidadas da tarefa 6.1.
