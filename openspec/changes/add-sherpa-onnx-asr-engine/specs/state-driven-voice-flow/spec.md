## MODIFIED Requirements

### Requirement: Gramática depende do estado operacional

O sistema DEVE (MUST) selecionar, a partir do `PickingState` atual, o conjunto de
palavras aceitas e o perfil de endpoint correspondentes. Uma troca de estado DEVE (MUST)
invalidar qualquer resultado ASR iniciado sob a configuração anterior. O conjunto de
palavras aceitas por estado NÃO DEVE (MUST NOT) mais restringir as hipóteses do próprio
decodificador de reconhecimento — o decodificador é de vocabulário aberto — e passa a ser
aplicado como uma validação sobre o texto já reconhecido: texto que não corresponder a
nenhuma palavra aceita do estado atual é descartado do mesmo jeito que antes, mas depois
de reconhecido, não antes.

#### Scenario: Resultado atrasado não avança o próximo estado

- **WHEN** o ator muda de `ConfirmandoQuantidade` para `ReadbackQuantidade` antes de um
  resultado final de quantidade ser entregue
- **THEN** o resultado atrasado é descartado
- **AND** nenhum evento novo é enviado ao ator.

#### Scenario: Texto reconhecido fora do vocabulário do estado é descartado após o reconhecimento

- **WHEN** o estado atual aceita um conjunto fechado de palavras e o texto reconhecido não
  corresponde a nenhuma delas
- **THEN** nenhum evento é publicado no ator
- **AND** a rejeição acontece pela comparação do texto já reconhecido contra o conjunto de
  palavras aceitas do estado, não por o decodificador ter sido impedido de produzir aquela
  hipótese.

### Requirement: Check digit falado é validado localmente

O sistema DEVE (MUST) comparar o check digit reconhecido com o dado operacional esperado
antes de enviar o evento ao ator. O valor esperado NÃO DEVE (MUST NOT) aparecer na saída
de áudio nem em logs de diagnóstico, em nenhum build. Em build de release, o valor
esperado também NÃO DEVE (MUST NOT) aparecer no painel. Em build de debug, o painel de
desenvolvimento PODE (MAY) exibir o valor esperado como apoio à depuração de bancada —
essa exceção é escopada exclusivamente ao build de debug. O sistema DEVE (MUST) aceitar o
check digit falado **apenas dígito a dígito** ("quatro", "sete"), com dois algarismos
exatos. Dados de bancada reais com voz humana e ruído de fundo mostram que ampliar o
vocabulário aceito com palavras de dezena degrada o reconhecimento da própria leitura
dígito a dígito — o vocabulário mínimo é parte do requisito, não detalhe de
implementação — a mesma regra vale para `ConfirmandoQuantidade`.

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
- **AND** essa rejeição acontece por comparação do texto reconhecido contra o vocabulário
  mínimo aceito do estado, mesmo que o decodificador seja capaz de produzir "quarenta e
  sete" como hipótese — a garantia de que a palavra nunca vira check digit não depende
  mais de o decodificador ser impedido de reconhecê-la.
