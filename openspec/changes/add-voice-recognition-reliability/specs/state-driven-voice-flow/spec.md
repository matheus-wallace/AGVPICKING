## MODIFIED Requirements

### Requirement: Check digit falado é validado localmente

O sistema DEVE (MUST) comparar o check digit reconhecido com o dado operacional esperado
antes de enviar o evento ao ator. O valor esperado NÃO DEVE (MUST NOT) aparecer na saída
de áudio nem em logs de diagnóstico, em nenhum build. Em build de release, o valor
esperado também NÃO DEVE (MUST NOT) aparecer no painel. Em build de debug, o painel de
desenvolvimento PODE (MAY) exibir o valor esperado como apoio à depuração de bancada —
essa exceção é escopada exclusivamente ao build de debug. O sistema DEVE (MUST) aceitar o
check digit falado **apenas dígito a dígito** ("quatro", "sete"), com dois algarismos
exatos, e a gramática de `AguardandoCheckDigit` DEVE (MUST) conter só os dígitos e os
transversais. Dados de bancada reais com voz humana e ruído de fundo mostram que ampliar
essa gramática com as palavras de dezena degrada o reconhecimento da própria leitura
dígito a dígito, então o vocabulário mínimo é parte do requisito, não detalhe de
implementação — a mesma regra vale para `ConfirmandoQuantidade` (Decisão 7 do design.md),
que também passa a aceitar somente dígito a dígito.

#### Scenario: Check digit divergente

- **WHEN** o estado é `AguardandoCheckDigit` e os dígitos falados não correspondem ao
  dado esperado
- **THEN** o sistema publica `CheckDigitIncorreto`
- **AND** o ator retorna à navegação sem revelar os dígitos corretos.

#### Scenario: Painel de debug mostra o valor esperado, release não

- **WHEN** o app roda em build de debug e o estado é `AguardandoCheckDigit`
- **THEN** o painel de dev pode exibir o check digit esperado da linha
- **AND** em build de release esse mesmo valor nunca aparece no painel, na saída de áudio
  ou em log.

#### Scenario: Leitura dígito a dígito preserva o valor e o zero à esquerda

- **WHEN** o estado é `AguardandoCheckDigit` e o operador fala dois algarismos, como "oito
  dois" para o check digit "82" ou "zero sete" para o check digit "07"
- **THEN** o sistema interpreta cada palavra como um algarismo na ordem falada, sem somar
  magnitudes
- **AND** o zero à esquerda é preservado, porque a comparação com o dado esperado é
  literal ("07" ≠ "7").

#### Scenario: Palavra de dezena não é check digit

- **WHEN** o estado é `AguardandoCheckDigit` e o operador fala uma palavra que não é
  algarismo, como "quarenta e sete" ou "dezessete"
- **THEN** nenhum evento é publicado e a fala é descartada, o mesmo comportamento de
  qualquer fala fora do contrato do estado
- **AND** essas palavras também não fazem parte da gramática do estado, de modo que o
  decodificador não as tem como hipótese.

### Requirement: A ocorrência tem saída por voz e por toque

`TratandoExcecao` NÃO DEVE (MUST NOT) depender apenas de uma ação de toque para ser
encerrado: o sistema DEVE (MUST) aceitar "próximo" por voz com a mesma gramática fechada e
o mesmo perfil de endpoint curto usados nos demais avanços de uma palavra do fluxo — dados
de bancada reais mostram que o vocabulário aberto anteriormente usado para tentar capturar
um relato falado livre errava a transcrição de "próximo" com frequência (fora da
gramática, resultados como "prós", "aqui", "faria", "o próximo"), enquanto todo estado de
gramática fechada do fluxo reconhece a palavra de avanço de primeira tentativa no mesmo
log. O relato de texto livre nunca foi consumido pelo domínio (`ExcecaoRegistrada` não
carrega o texto reconhecido) e por isso deixa de ser uma via de entrada aceita.

#### Scenario: Saída curta por voz

- **WHEN** o estado é `TratandoExcecao` e o operador fala "próximo"
- **THEN** o sistema publica `ExcecaoRegistrada`.

#### Scenario: Fala fora do vocabulário não avança

- **WHEN** o estado é `TratandoExcecao` e o operador fala qualquer frase que não seja
  "próximo" ou um transversal (por exemplo, um relato descrevendo a ocorrência)
- **THEN** nenhum evento é publicado, o mesmo comportamento de qualquer outra fala fora do
  contrato do estado
- **AND** a tela do operador continua oferecendo a ação de toque como via de registro.

#### Scenario: Saída por toque na tela do operador

- **WHEN** o estado é `TratandoExcecao`
- **THEN** a tela do operador oferece uma ação de toque que publica `ExcecaoRegistrada`
- **AND** nenhum outro estado do fluxo ganha ação de toque para avançar.
