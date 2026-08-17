## ADDED Requirements

### Requirement: Janela de câmera acomoda o tempo real do operador
Em `EscaneandoProduto`, o sistema NÃO DEVE (MUST NOT) encerrar a oportunidade de leitura antes de o operador ter tempo de abrir a caixa e contar os itens — o tempo de câmera ligada não pode ser dimensionado só pelo tempo de decodificação do código.

#### Scenario: Operador ainda está manuseando a caixa
- **WHEN** o operador chega ao endereço, confirma o check digit e ainda está abrindo a caixa/contando os itens
- **THEN** a câmera continua disponível para leitura quando o operador afinal apontar o código
- **AND** o sistema não force uma nova tentativa manual só porque um tempo fixo se esgotou.
