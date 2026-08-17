## 1. Fala no readback

- [ ] 1.1 `ProjetorDeFalaPicking.kt`: `ReadbackQuantidade` passa a retornar
  "Confirma {quantidadeInformada}?" em vez de `null`.
- [ ] 1.2 Ao voltar para `ConfirmandoQuantidade` via `ReadbackCorrecaoSolicitada`, repetir por
  voz a quantidade esperada da linha (mesma mensagem já usada na primeira entrada nesse estado).
- [ ] 1.3 Confirmar que a nova fala respeita o gate `SaidaDeAudio.falando` já existente
  (design.md - Risco 1), sem nenhum mecanismo novo de sincronização TTS/ASR.
- [ ] 1.4 Teste de unidade em `ControladorDeFalaTest.kt`/`ProjetorDeFalaPickingTest` cobrindo os
  dois cenários do spec.

## 2. Log de reconhecimento do ASR

- [ ] 2.1 `ReconhecedorDeComando.kt`: logar todo resultado final do Vosk, aceito ou não, no
  mesmo canal estruturado já usado para gramática/nível ([[reference-voz-bancada]]).
- [ ] 2.2 Diferenciar no log os dois motivos de descarte: fora da gramática do estado atual vs.
  resultado de versão de estado obsoleta.
- [ ] 2.3 Teste de unidade cobrindo que um resultado fora da gramática e um resultado obsoleto
  são ambos logados e nenhum publica `PickingEvent`.

## 3. Diagnóstico da janela de câmera (bloqueia a implementação)

- [ ] 3.1 Ler `ControladorDeVisao.kt` por completo: o que hoje encerra a produção de eventos em
  `EscaneandoProduto` — teto de tempo fixo, leitura prematura satisfazendo `ConsensoDeLeitura`,
  ou outra causa. Responder a Open Question do design.md antes de seguir.
- [ ] 3.2 Com a causa confirmada, escrever a tarefa de correção específica (estender/remover
  teto, ou gating por sinal explícito do operador antes de aceitar leitura) — **tarefa a
  detalhar depois de 3.1**, não implementar às cegas.
- [ ] 3.3 Teste (unidade e/ou bancada, conforme a causa) cobrindo que o operador tem tempo real
  de abrir a caixa e contar antes de a janela de leitura se fechar.

## 4. Verificação

- [ ] 4.1 Executar `./gradlew testDebugUnitTest assembleDebug lintDebug` a partir de
  `AgvPickVoice/` depois dos grupos 1 e 2 (grupo 3 depende do diagnóstico de 3.1).
- [ ] 4.2 Em bancada: readback fala a quantidade e reforça em caso de correção; log mostra
  resultados de ASR aceitos e descartados; janela de câmera cobre o tempo real de abrir a caixa
  e contar. **Pendente — exige bancada com voz humana e caixa física**, mesmo padrão das fatias
  anteriores.
