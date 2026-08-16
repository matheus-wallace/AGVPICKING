## Purpose

Define o contrato de leitura e escrita de dados de ordem, produto, lote, endereço e usuário durante o protótipo, garantindo zero dependência de rede nessa camada, mantendo-a trocável por uma integração real com o WMS depois, sem tocar em nenhum código que a consome.

## ADDED Requirements

### Requirement: Zero chamada de rede pra dados de escopo WMS
Ler ou escrever dados de ordem, produto, lote, endereço ou usuário através da implementação mockada NÃO DEVE nunca realizar uma chamada de rede.

#### Scenario: Ler uma ordem sem rede disponível
- **WHEN** o dispositivo não tem conectividade de rede
- **THEN** buscar a ordem atual, suas linhas e o operador ainda funciona, porque a implementação mockada nunca acessa a rede

### Requirement: Dados mockados estruturalmente realistas
Ordens mockadas DEVEM usar formatos de SKU, GTIN e lote estruturalmente consistentes com produtos reais que a operação movimenta, de forma que a lógica de decodificação e parsing desenvolvida contra o mock se comporte igual contra dados reais depois.

#### Scenario: Ordem mockada bate com formatos de produto reais
- **WHEN** uma ordem mockada é carregada
- **THEN** todo GTIN, lote e campo de endereço de cada linha segue o mesmo formato que produtos reais da AGV e endereços de armazém reais usam, não valores placeholder ou arbitrários

### Requirement: Repositório trocável sem tocar nos consumidores
O acesso a dados de ordem/produto/endereço/usuário DEVE ser exposto por uma interface. Substituir a implementação mockada por uma implementação real (ex: baseada em HTTP) NÃO DEVE exigir nenhuma mudança em código que dependa da interface.

#### Scenario: Trocando a implementação mockada
- **WHEN** a implementação mockada em memória é substituída por uma implementação diferente da mesma interface de repositório
- **THEN** nenhum consumidor do repositório precisa mudar, porque os consumidores dependem só da interface
