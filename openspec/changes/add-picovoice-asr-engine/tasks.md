## 1. Bancada mínima de viabilidade (gate antes de qualquer código de integração)

- [ ] 1.1 Gerar `AccessKey` no Picovoice Console com a conta de trial liberada
- [ ] 1.2 Compilar no Console um contexto `.rhn` de teste, mínimo, cobrindo só os comandos
      transversais já existentes em `VocabularioDeVoz` (`parar`, `repetir`, `próximo`)
- [ ] 1.3 Rodar o exemplo oficial do SDK Android (`rhino-android` demo ou equivalente) com o
      contexto de teste e voz humana real, confirmando reconhecimento em pt-BR — não apenas
      assumir pelo rótulo genérico "Portuguese" da documentação pública
- [ ] 1.4 Decisão de go/no-go registrada em design.md: só prosseguir para as seções 2-6 se
      1.3 confirmar reconhecimento pt-BR utilizável; caso contrário, encerrar este change como
      fechado (mesmo padrão de `add-sherpa-onnx-omnilingual-decoder`) e apontar de volta para
      `add-voice-recognition-reliability` — grupo 6

> **Nota de execução (19/08/2026).** O gate do grupo 1 foi dispensado por decisão do dono do
> projeto: a confirmação de pt-BR passa a acontecer *durante* a implementação, e não antes dela.
> Os grupos 2, 4 e 5 estão implementados; o grupo 3 (autoria do contexto `.rhn` no Console) e o
> grupo 6 (bancada) continuam abertos e são os únicos bloqueios para trocar o motor ativo. O
> `MotorPicovoiceRhino` já compila, já lê a `AccessKey` e já tem o modelo de idioma pt-BR
> vendorizado — sem o `.rhn`, `carregar()` devolve `false` e a voz fica desligada, exatamente
> como o contrato do `MotorDeAsr` prevê. Ver design.md, "Verificação da API do Rhino".

## 2. Setup do projeto

- [x] 2.1 Adicionar dependência Gradle `ai.picovoice:rhino-android` ao módulo `app`
- [x] 2.2 Adicionar `picovoiceAccessKey` a `local.properties` (já gitignorado) e expor como
      campo de `BuildConfig` em `build.gradle.kts` (Decisão 3 de design.md)
- [x] 2.3 Confirmar que `AccessKey` não aparece em nenhum log, commit ou artefato versionado

## 3. Autoria do contexto de produção no Console

- [x] 3.1 Modelar como slot numérico enumerado (0-99) o check digit dígito-a-dígito fundido,
      cobrindo as expressões que hoje `VocabularioDeVoz.digitos` aceita
      <br>_(`numero_digitos` em `picovoice-pt.yaml`, slot `digito` — ver comentário "Por que check
      digit e quantidade viraram um só par de intenções" no YAML.)_
- [x] 3.2 Adicionar ao mesmo slot as expressões alternativas de check digit extenso
      (`VocabularioDeVoz.checkDigitExtenso`), registrando no Console cada variação já
      coberta pelo Vosk
      <br>_(`numero_extenso` em `picovoice-pt.yaml`, decomposto por magnitude — unidade/dezena/
      centena/milhar.)_
- [x] 3.3 Modelar os demais comandos transversais e por estado (quantidade, confirmações,
      "próximo") como intents/expressões cobrindo o vocabulário fechado atual
      <br>_(As 15 intenções de palavra única em `picovoice-pt.yaml`, nomes casando com
      `SintetizadorDeIntencaoRhino.INTENCOES`.)_
- [x] 3.4 Compilar o contexto `.rhn` final e vendorizar em `assets/`, com um
      `PROVENIENCIA.md` equivalente ao já usado para o modelo sherpa-onnx
      <br>_(Feito em 19/08/2026: `picovoice-pt.rhn` vendorizado em
      `assets/contexto-picovoice/`, `PROVENIENCIA.md` atualizado com data/tamanho/SHA-256.
      `assembleDebug` confirma o asset empacotado no APK (10.388 B, mesmo hash do download).
      **Ainda não confirmado**: se o Console compilou o YAML sem corte/erro silencioso — isso só
      se vê no log de carga (`SintetizadorDeIntencaoRhino.INTENCOES`) rodando o motor de
      verdade, o que é o próprio objetivo do grupo 6.)_
- [ ] 3.5 **Pendência, não bloqueia este change**: `VocabularioDeVoz.VALOR_NUMERO`/
      `VALOR_DIGITO_EM_QUANTIDADE` (Kotlin) não aceitam `"uma"`/`"duas"`, que o contexto Rhino já
      reconhece por causa do vocabulário compartilhado com o `app-wms` (design.md - "Uso futuro
      compartilhado com o app-wms"). Decidir se o AGV passa a aceitar essas formas também, e
      ajustar as duas tabelas + testes se sim.
- [ ] 3.6 **Pendência, não bloqueia este change**: `numero_extenso` ganhou cobertura de
      1.000-999.999 (design.md - "Casa dos milhares"), mas `VocabularioDeVoz.numero()` (Kotlin)
      não tem noção de multiplicação — reconhecer "mil" como âncora ×1000 é reescrita de
      algoritmo, não tabela nova. Decidir se/quando fazer essa mudança, e se
      `InterpretadorDeFala.QUANTIDADE_ACEITA` (hoje `1..999`) deve mudar junto ou se a casa de
      milhar é só pro `app-wms`.
- [ ] 3.7 **Investigar antes de aceitar como limite acústico**: bancada reportou `"um"` não
      entendido, `"doze"` virando `"dois"`, `"21"` virando `"20"`. Confirmar método de teste
      (voz real vs. texto digitado no Console) antes de decidir se é achado de modelo acústico
      (sem correção via YAML) ou bug de gramática real (design.md - "Achados de bancada
      reportados").

## 4. Implementação de `MotorPicovoiceRhino`

- [x] 4.1 Implementar `carregar(): Boolean` carregando o `Rhino` com o contexto e o
      `AccessKey`, uma vez, na construção
- [x] 4.2 Implementar `abrirSessao`/`SessaoDeAsr.aceitar`, sintetizando texto a partir do
      `intent`/`slots` reconhecidos (Decisão 1 de design.md) — nunca expondo intenção
      estruturada fora da fronteira do motor
- [x] 4.3 Implementar `SessaoDeAsr.reiniciar()` para o mesmo gatilho que já existe hoje
      (TTS falando, add-state-driven-voice-flow - Decisão 6)
- [x] 4.4 Log estruturado do resultado bruto do Rhino (`isUnderstood`, `intent`, `slots`,
      texto sintetizado) no mesmo canal/convenção de `ReconhecedorDeComando`, para leitura de
      bancada consistente com as rodadas anteriores

## 5. Testes e wiring

- [x] 5.1 Testes unitários da síntese de texto a partir de intent/slot (sem tocar hardware)
- [x] 5.2 `MotorPicovoiceRhino` disponível em `AppContainer.kt`, mas **não** trocado como
      motor ativo por padrão — mesmo padrão de convivência de `MotorSherpaOnnx`
- [x] 5.3 `./gradlew testDebugUnitTest assembleDebug lintDebug` limpos

## 6. Bancada comparativa (deliberadamente aberta, exige Matheus com o aparelho físico)

- [ ] 6.1 Trocar `motorDeAsr` para `MotorPicovoiceRhino` no build instalado (sem commitar a
      troca de default)
      <br>_(Preparado em 19/08/2026: a troca foi validada no working tree e `assembleDebug`
      rodou limpo com ela; a linha foi revertida para `MotorVosk` antes do commit, como esta
      tarefa exige. Falta instalar no aparelho — nenhum dispositivo estava conectado
      (`adb devices` vazio) nas sessões de 19-20/08/2026.)_
- [ ] 6.2 Rodar a mesma bateria de 9 comandos usada como baseline do Vosk
      (add-audio-single-grammar-slice)
- [ ] 6.3 Testar check digit extenso e dígito a dígito, mesmo protocolo de
      add-voice-recognition-reliability - grupo 6
- [ ] 6.4 Testar "próximo" em avaria (`TratandoExcecao`), mesmo cenário que motivou o
      fechamento de gramática nesse estado
- [ ] 6.5 Medir tempo de troca de contexto/instância se a Decisão 2 (contexto único) precisar
      ser revisitada por confusão entre comandos de estados diferentes
- [ ] 6.6 Registrar os números em design.md ("## Bancada", mesma convenção das rodadas
      anteriores) e decidir: manter Vosk, promover Rhino a padrão, ou fechar este change como
      mais uma tentativa descartada — sem marcar esta seção como concluída até essa decisão
