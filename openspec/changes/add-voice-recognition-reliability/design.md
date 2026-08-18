## Context

Ver proposal.md - Why. Este design se apoia inteiramente em `adb logcat -s
ReconhecedorDeComando:* AjustesAsr:*` puxado do SM-G780F em 17/08/2026, cobrindo cinco
sessões do app entre 18:44 e 20:00 (pids 27338, 5038, 9736, 13174, 14580). O log já
carrega a classificação de descarte que `add-operator-feedback-improvements` introduziu
(`ASR[Estado]: "texto" -> aceito|descartado (fora da gramática)|descartado (versão
obsoleta)`), então dá para separar "o Vosk ouviu errado" de "o Vosk ouviu certo mas o
interpretador rejeitou" sem instrumentar nada novo.

Três achados, cada um com evidência direta:

1. **Check digit — gap de gramática.** `AguardandoCheckDigit`'s gramática hoje é
   `VocabularioDeVoz.DIGITOS + TRANSVERSAIS` — só `zero`..`nove` e `meia`. Não tem
   nenhuma palavra de dezena. Em 18:44:33–18:44:59 o log mostra seis descartes seguidos
   (`"sete" -> descartado`, `"quatro" -> descartado`, repetindo) antes de um sucesso às
   18:45:53 (`"quatro sete" -> CheckDigitFalado`). Padrão idêntico se repete às 19:56:50–
   19:56:57 (`"quatro" -> descartado` sozinho, sessão inteira sem outro check digit até o
   fim do log). Isso é consistente com o operador dizendo "quarenta e sete" por extenso —
   palavra fora da gramática fechada, forçando o decodificador para o vocabulário mais
   próximo disponível — e/ou com a elocução sendo cortada entre os dois dígitos antes do
   segundo ser dito.
2. **Check digit — timing do endpointer.** Mesmo quando o operador lê dígito a dígito
   (a forma coberta pela gramática atual), a elocução às vezes fecha depois de só um
   dígito. Contraexemplo de sucesso no mesmo log, para calibrar a expectativa: 19:09:31–
   19:09:33 e 19:56:01–19:56:02 mostram "quatro sete" reconhecido como uma elocução só,
   virando `CheckDigitFalado` — então o perfil `DIGITOS` (700 ms) funciona quando os dois
   dígitos saem próximos, mas não é robusto à variação natural de cadência da fala.
3. **Avaria — vocabulário aberto é o problema, não o interpretador.** `TratandoExcecao`
   é o único estado com `ConfiguracaoDeEscuta(palavras = emptyList(), perfil =
   TEXTO_LIVRE)`. Três ocorrências no log, todas com o mesmo padrão: uma ou mais
   transcrições erradas antes de eventualmente acertar, ou nunca acertar e o operador
   recorrer ao botão de toque. 18:46:10–18:46:12: `"prós"` descartado, depois `"próximo"`
   aceito na segunda tentativa. 18:55:32–18:55:55: `"aqui"`, `"faria"`, `"prós"`
   descartados, `"próximo"` só na quarta tentativa. 19:58:56–19:59:41: `"o próximo"`
   descartado (o artigo antes da palavra já invalida o match exato) e **nenhum outro
   evento de ASR aparece nos 45 segundos seguintes** até o estado avançar para
   `ItemConcluido` — o operador usou o botão de toque "Registrar ocorrência e seguir"
   depois de a voz falhar. Em contraste, todo estado de gramática fechada do mesmo log
   ("cheguei", "confirmar", "alocado", "concluir", "encerrar", "doze") reconheceu de
   primeira tentativa. `PickingEvent.ExcecaoRegistrada` não carrega o texto reconhecido —
   `ResolvedorDeIntencao.resolver` trata `IntencaoDeVoz.Direta` (que é o que "próximo" e
   o relato de 3+ palavras viram) como passagem direta, sem inspecionar o conteúdo — e
   nada no domínio grava ou consome esse texto, confirmado lendo
   `ResolvedorDeIntencao.kt` e `PickingEvent.kt` inteiros.
4. **"Iniciar" — sem evidência de bug.** Única ocorrência no log inteiro, 18:44:20:
   partial "iniciar" às 20.005, final "iniciar" -> `IniciarNavegacao` às 20.729 — sucesso
   de primeira tentativa, sem repetição. As quatro outras vezes que o estado
   `OrdemCarregada` aparece no log (18:55:15, 19:07:28, 19:54:49, 19:59:56), o operador
   usou "próximo" em vez de "iniciar", também sempre de primeira. Não há dado que
   distinga "iniciar" tem um problema real" de "o operador passou a usar só 'próximo'
   depois que descobriu o sinônimo".

## Goals / Non-Goals

**Goals:**
- Corrigir os dois problemas com causa raiz confirmada em dado real: gap de gramática do
  check digit por extenso, e instabilidade do vocabulário aberto de `TratandoExcecao`.
- Deixar uma tarefa de calibração de bancada explícita para o timing do endpointer de
  `DIGITOS`, em vez de mudar o valor no código sem medir.
- Registrar "iniciar" como investigado e sem causa confirmada, para não fechar o assunto
  silenciosamente nem inventar um fix sem base.

**Non-Goals:**
- Não reintroduzir relato de exceção falado livre por nenhum outro mecanismo (ex.: dois
  reconhecedores em paralelo, um fechado e um aberto). A Decisão 2 abaixo explica por
  que a funcionalidade nunca foi real.
- Não mudar `PerfilEndpoint.DIGITOS.silencioFinalMs` no código deste change — isso é
  resultado de uma tarefa de bancada (tasks.md), não uma decisão de design antecipada.
- Não tocar em `ConfirmandoQuantidade`, que já aceita extenso e dígito a dígito
  corretamente e não aparece como problema no log.

## Decisions

### Decisão 1: Check digit aceita extenso reaproveitando `VALOR_NUMERO`, não uma tabela nova

`VocabularioDeVoz.numero(texto)` já resolve números por extenso de 0 a 999 somando
magnitudes decrescentes ("quarenta e sete" = 40 + 7). Para o check digit, o resultado
precisa virar uma **string de dois dígitos com zero à esquerda** (a comparação com
`linha.senhaEndereco`/`partida` é literal — doc §7.1, "07" ≠ "7"), e só valores de 0 a 99
fazem sentido (um check digit de 3 dígitos não existe no domínio). A nova função
`VocabularioDeVoz.checkDigitExtenso(texto)` chama `numero()`, valida o intervalo 0..99,
e formata com `"%02d".format(it)`.

**Correção descoberta na implementação:** chamar `numero()` e validar o intervalo não basta.
`numero()` soma magnitudes **decrescentes**, então "oito dois" — leitura dígito a dígito
perfeitamente comum do check digit `82`, e uma das linhas da bancada em
`PublicadorDeVozTest` — devolve 8 + 2 = 10 e cai dentro do intervalo, virando o check digit
`"10"`. O mesmo vale para "nove oito" (17 em vez de 98) e para qualquer par de unidades em
ordem decrescente; foi o teste de integração existente que pegou a regressão. `checkDigitExtenso`
por isso exige que a **primeira palavra falada já valha 10 ou mais** ("dez", "dezessete",
"vinte", "quarenta"…) antes de aceitar a leitura como extenso. Essa exigência também resolve, de
graça, a rejeição do número por extenso de um algarismo só ("sete") que o parágrafo abaixo pede:
uma unidade isolada não abre uma dezena.

`InterpretadorDeFala.interpretar` para
`AguardandoCheckDigit` tenta `checkDigitExtenso` primeiro e `digitos` (dígito a dígito)
depois — mesma ordem de prioridade que `ConfirmandoQuantidade` já usa entre `numero` e
`numeroDigitoADigito`, então o padrão do arquivo fica consistente.

Alternativa considerada: aceitar um único número por extenso de 0-9 como equivalente a
"zero mais o dígito" (ex.: "sete" sozinho vira "07"). Rejeitada — um número isolado é
ambíguo demais com fala cortada no meio de um "quarenta e sete" que perdeu a primeira
palavra; o cenário de teste "Check digit com zero à esquerda por extenso" na spec fixa
esse comportamento.

A gramática de `AguardandoCheckDigit` em `SeletorDeEscuta` precisa incluir as palavras de
dezena e o conectivo "e" para o Vosk conseguir sequer transcrever "quarenta" — sem isso a
função nova nunca recebe o texto certo para interpretar. Reaproveita
`VocabularioDeVoz.QUANTIDADES` (que já é `VALOR_NUMERO.keys + "e"`) em vez de criar uma
lista paralela.

#### Decisão 1 revertida: o extenso caiu na bancada, o check digit volta a ser só dígito a dígito

Tudo acima foi implementado, testado em JVM e levado à bancada de 17/08/2026 com **voz real
do operador e ventilador ligado ao lado** (ruído de armazém simulado). Não sobreviveu:

- **A leitura por extenso não registra inteira.** Dizendo "quarenta e sete" grudado, sem
  pausa nenhuma, o reconhecedor devolvia repetidamente **"quarenta" sozinho** — check digit
  errado, não recusa. Aumentar o silêncio final do endpointer (a hipótese da Decisão 3) não
  mudou o resultado: o problema não é o corte de elocução, é a hipótese que o decodificador
  escolhe.
- **A gramática maior degradou o que já funcionava.** Somar `QUANTIDADES` à gramática de
  `AguardandoCheckDigit` acrescenta ~30 palavras de dezena/centena a um estado que antes
  tinha 10. Com elas dentro, uma tentativa **dígito a dígito** de "quatro" foi revisada no
  meio da elocução para **"quatrocentos"** — descartada corretamente por estar fora do
  intervalo, mas ela nunca deveria ter sido hipótese: "quatrocentos" só existia no
  vocabulário por causa do extenso. Vocabulário maior = mais vizinhos confundíveis, e o
  preço foi pago pela leitura que já era confiável.

Conclusão do operador em bancada, textual: *"estou falando quarenta e sete grudado, por
extenso não está funcionando, vamos manter tudo por dígito mesmo, dessa forma fica mais
confiável"*.

Revertido, então: `checkDigitExtenso`/`comecaEmDezena` e as constantes `CHECK_DIGIT_POR_EXTENSO`
e `MENOR_DEZENA` saem de `VocabularioDeVoz`; `SeletorDeEscuta` volta a `DIGITOS +
TRANSVERSAIS` em `AguardandoCheckDigit`; `InterpretadorDeFala` volta a só `digitos(...)` com
dois algarismos exatos. O registro acima fica de propósito — a hipótese era razoável e o
custo dela é conhecido; quem quiser tentar de novo precisa de evidência de bancada melhor do
que a que a derrubou. O aprendizado transferível: **numa gramática fechada, aceitar mais
formas de dizer a mesma coisa não é grátis — cada palavra a mais é um candidato a mais para
o decodificador errar sob ruído.**

#### Decisão 1 restaurada em 18/08/2026: só dígito a dígito também falha; extenso volta sem repetir o segundo motivo da queda

Na sessão de bancada seguinte, o padrão inverso apareceu: só dígito a dígito, o
reconhecedor passou a entender repetidamente **um único algarismo por elocução** — o log de
`ReconhecedorDeComando` mostra `AguardandoCheckDigit`/`ConfirmandoQuantidade` girando em
ciclos de confirmar/corrigir sem o operador conseguir fechar a leitura em uma tentativa.
Matheus, em bancada, textual: *"eu pedi para colocar somente dígitos para números, mas não
ficou bom, a todo momento é entendido somente um deles, vamos manter por extenso e por
dígito também"*.

`checkDigitExtenso`/`MENOR_DEZENA` voltam, mas não como estavam antes da reversão: a
constante que preenche a gramática deixa de ser `VocabularioDeVoz.QUANTIDADES` (0-999
inteiro) e passa a ser `VocabularioDeVoz.CHECK_DIGIT_POR_EXTENSO`, uma tabela própria
restrita a 0..99 — sem "cem", "cento", "duzentos" ... "novecentos". Isso ataca
especificamente o segundo motivo da queda original (a gramática maior revisando "quatro"
para "quatrocentos" no meio da fala): um check digit de dois algarismos nunca precisou de
palavra de centena, e elas só estavam na gramática porque a lista era compartilhada com
quantidade. O primeiro motivo da queda (elocução grudada registrando incompleta) não ganhou
correção nova aqui — extenso continua sujeito a ele —, mas agora é uma segunda tentativa
disponível ao lado do dígito a dígito, não a única via: se um corta, o outro cobre.
`InterpretadorDeFala` volta a tentar `checkDigitExtenso` primeiro e `digitos` depois, como o
design original já previa.

### Decisão 7: extenso sai de `ConfirmandoQuantidade` também — extensão da Decisão 1 revertida, não um novo defeito de bancada

Na mesma conversa de bancada em que a Decisão 1 foi revertida, Matheus pediu para ir além:
*"não Claude, vamos remover extenso de tudo, para números vamos falar somente por
dígitos"*. Diferente do check digit, a leitura por extenso de `ConfirmandoQuantidade`
("doze" -> 12) **não falhou em bancada nesta rodada** — é uma decisão proativa de
consistência, não uma correção de sintoma observado. Vale registrar essa distinção para
não inflar a evidência: o que a bancada provou foi que gramática maior piora a
confiabilidade sob ruído (Decisão 1 revertida); o que Matheus decidiu foi aplicar essa
lição ao app inteiro antes que o mesmo problema aparecesse em quantidade, não depois.

Mecânica: `VocabularioDeVoz.numero()` (leitura por extenso de 0 a 999, tabela
`VALOR_NUMERO`) e o `val QUANTIDADES` público (que reaproveitava essa tabela para a
gramática) saem inteiros — nada mais os chamava fora de `ConfirmandoQuantidade` e dos
próprios testes. `numeroDigitoADigito()` continua a única leitura de quantidade, e a
gramática de `ConfirmandoQuantidade` ganha um equivalente de `DIGITOS` sem "meia"
(`VocabularioDeVoz.DIGITOS_EM_QUANTIDADE`, espelhando `VALOR_DIGITO_EM_QUANTIDADE`) em vez
de expor a tabela privada diretamente — mesmo padrão do `DIGITOS` já usado por
`AguardandoCheckDigit`.

#### Decisão 7 revertida em 18/08/2026: extenso volta para `ConfirmandoQuantidade`

Mesma bancada, mesma conversa que restaurou a Decisão 1 acima: o pedido de Matheus —
"vamos manter por extenso e por dígito também" — não distinguiu os dois estados, e o corte
que atrapalhava o check digit dígito a dígito atrapalhava a quantidade do mesmo jeito.
`VocabularioDeVoz.numero()` e o `val QUANTIDADES` voltam; `InterpretadorDeFala.interpretar`
para `ConfirmandoQuantidade` volta a tentar `numero()` antes de `numeroDigitoADigito()`; e
`SeletorDeEscuta` volta a `QUANTIDADES + TRANSVERSAIS`.

Nota sobre o histórico, para quem ler o diff: a Decisão 7, embora registrada acima como
decisão tomada, nunca chegou a ser commitada — ficou como mudança de working tree entre a
bancada de 17/08 e a de 18/08. "Reverter" aqui foi, na prática, `git checkout` do arquivo
de vocabulário, não uma segunda edição sobre código já mesclado.

### Decisão 2: Fechar a gramática de `TratandoExcecao`, não torná-la mais tolerante

Cogitado e descartado: normalizar o texto reconhecido para aceitar variações como "o
próximo" (removendo artigos/preenchimento antes do match) mantendo o vocabulário aberto.
Rejeitado porque não ataca a causa raiz — "prós", "aqui" e "faria" não são "próximo" com
ruído decorável, são transcrições genuinamente diferentes que o decodificador de
vocabulário aberto produziu a partir do mesmo áudio. Um decodificador de gramática
fechada com "próximo" como palavra válida elimina a ambiguidade na origem, e o log já
prova que gramática fechada é a condição em que este app reconhece uma palavra de avanço
de primeira tentativa. Como o texto do relato livre nunca foi consumido por nada no
domínio (achado 3 acima), fechar a gramática não perde funcionalidade real — só a
aparência de uma funcionalidade que nunca funcionou de ponta a ponta. O botão de toque
"Registrar ocorrência e seguir" (`add-operator-feedback-improvements`, item 3b) continua
como a via de registrar detalhes, e passa a ser a única via para *conteúdo*, com a voz
resolvendo apenas o avanço.

Decisão confirmada com Matheus antes deste design ser escrito (não é uma escolha
unilateral): a alternativa de manter vocabulário aberto foi apresentada e recusada.

### Decisão 3: Timing do endpointer de `DIGITOS` fica para bancada, não para este design

O log mostra os dois lados: 700 ms funciona quando os dígitos saem próximos, falha
quando não. Subir o valor sem medir arrisca trocar um problema (corte no meio) por outro
(elocução caindo em cima da fala seguinte, ou o operador esperando à toa — o mesmo motivo
que mantém `COMANDO_CURTO` em 280 ms). O mecanismo de calibração sem recompilar já existe
(`AjustesAsr.carregar`, `ajustes-asr.properties`) exatamente para este tipo de decisão.
tasks.md inclui a tarefa de bancada; só se o resultado for conclusivo o default de
`PerfilEndpoint.DIGITOS` muda, e isso vira uma tarefa separada de código dentro do mesmo
change.

### Decisão 4: "Iniciar" não recebe fix especulativo

Alternativa considerada: aplicar a mesma tolerância de match (ou qualquer ajuste de
gramática) preventivamente, já que "iniciar" e "próximo" convivem na mesma gramática
fechada de `OrdemCarregada`. Rejeitada — o único dado disponível mostra "iniciar"
funcionando de primeira, e mudar código sem sintoma reproduzido no log viola a própria
prática que este change segue para os outros dois itens (diagnóstico antes de fix,
convenção já usada no achado do "camera window" de `add-operator-feedback-improvements`).
tasks.md inclui uma tarefa de bancada dedicada: pedir para o operador dizer "iniciar"
especificamente, várias vezes, e ler o log resultante antes de decidir se existe algo
para corrigir aqui.

### Decisão 5: `NoiseSuppressor`/`AutomaticGainControl` entram como toggles, não como padrão automático incondicional

`AudioMicrofoneSimulado` já tem o padrão certo para isso — `ligarCancelamentoDeEco`,
condicionado a `AjustesAsr.cancelamentoDeEco` e a uma checagem de disponibilidade no
aparelho, sem lançar exceção quando o efeito não existe. `NoiseSuppressor` e
`AutomaticGainControl` seguem o mesmo molde: `AjustesAsr` ganha
`supressaoDeRuido: Boolean` e `controleAutomaticoDeGanho: Boolean`, lidos de
`ajustes-asr.properties` como os demais campos, e `AudioMicrofoneSimulado` liga cada um
só se o aparelho oferecer (`NoiseSuppressor.isAvailable()` /
`AutomaticGainControl.isAvailable()`), liberando (`.release()`) junto com o
`AcousticEchoCanceler` no `finally` do fluxo.

Por que toggle em vez de ligar direto por padrão: o app já foi pego de surpresa uma vez
por um efeito de áudio nativo que piora o sinal em vez de melhorar (é literalmente por
isso que o AEC deixou de ser padrão — ver KDoc de `AudioMicrofoneSimulado`). AGC em
particular tem histórico de variar muito entre fabricantes; sem medir em bancada com voz
real, ligar por padrão repetiria o mesmo erro. tasks.md inclui a tarefa de bancada que
decide se o default de produção liga ou não — a decisão de design aqui é só a mecânica de
como o toggle é oferecido, não se ele fica ligado ao final.

Alternativa considerada: normalização de pico dinâmica feita em software, no próprio
`ReconhecedorDeComando`, em vez de um efeito nativo do Android. Rejeitada por agora —
`AutomaticGainControl` já resolve o mesmo problema com hardware/driver dedicado quando
disponível, e escrever AGC em software é o tipo de trabalho que só vale a pena se a
versão nativa se mostrar insuficiente na bancada.

### Decisão 6: Isolar o efeito da degradação de canal simulada é um passo de bancada, não uma mudança de default

`AjustesAsr.degradarCanal` já existe e já é ligável sem recompilar. Enquanto Matheus
banca sem os óculos físicos, o band-pass 300–3400 Hz + decimação para 8 kHz que simula o
canal HFP do óculos está no caminho de toda fala, mesmo sem nenhum dispositivo Bluetooth
para justificá-lo. Não faz parte deste change desligar isso por padrão — a degradação é
intencional para o pipeline se comportar como vai se comportar no dia do evento — mas
tasks.md inclui uma tarefa de bancada rápida (sem código) para medir quanto da
instabilidade reportada é o canal simulado versus os problemas de gramática/timing já
corrigidos aqui, testando com `degradarCanal=false` isoladamente.

## Risks / Trade-offs

- [Fechar `TratandoExcecao` remove a única via de relato de texto livre por voz, mesmo
  que nunca tenha sido consumida] → Se um uso futuro do texto do relato for desejado
  (ex.: enviar para o WMS real, fora do escopo deste hackathon), será um change novo que
  reabre a decisão, não uma regressão silenciosa — a spec documenta o comportamento
  anterior e por que ele mudou.
- [Extenso e dígito a dígito na mesma gramática aumentam o tamanho do vocabulário de
  `AguardandoCheckDigit` de 12 para ~30 palavras] → Gramáticas de `ConfirmandoQuantidade`
  já têm esse tamanho hoje sem problema reportado; o risco é considerado baixo mas fica
  como algo a observar na tarefa de bancada.
- [`NoiseSuppressor`/`AutomaticGainControl` pioram o sinal em vez de melhorar, repetindo
  o que já aconteceu com o AEC] → Por isso entram como toggle desligável por
  `ajustes-asr.properties`, não como padrão automático; a tarefa de bancada mede antes de
  qualquer default de produção mudar (Decisão 5).
- [A tarefa de calibração de `DIGITOS` pode não ter um valor único que resolva os dois
  modos de falha] → Se a bancada mostrar que nenhum valor de `silencioFinalMs` resolve
  sozinho, a extensão por extenso (Decisão 1) já reduz a dependência da leitura dígito a
  dígito, dando ao operador uma via alternativa mais robusta ao corte de elocução.
