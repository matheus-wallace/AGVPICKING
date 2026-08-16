## Purpose

Define o contrato de leitura e escrita de dados de ordem, produto, lote, endereço e usuário durante o protótipo, garantindo zero dependência de rede nessa camada, mantendo-a trocável por uma integração real com o WMS depois, sem tocar em nenhum código que a consome.

## ADDED Requirements

### Requirement: Zero chamada de rede pra dados de escopo WMS
Ler ou escrever dados de ordem, produto, lote, endereço ou usuário através da implementação mockada NÃO DEVE nunca realizar uma chamada de rede.

#### Scenario: Ler uma ordem sem rede disponível
- **WHEN** o dispositivo não tem conectividade de rede
- **THEN** buscar a ordem atual, suas linhas e o operador ainda funciona, porque a implementação mockada nunca acessa a rede

### Requirement: Dados mockados estruturalmente realistas
Ordens mockadas DEVEM usar as convenções de campo e formato do WMS de produção da AGV — nomes de campo, contagem de dígitos e codificação de endereço — de forma que a lógica de decodificação e parsing desenvolvida contra o mock se comporte igual contra dados reais depois, e que a futura implementação HTTP mapeie campo a campo em vez de traduzir nomenclatura. Todo valor DEVE ser fictício: nenhum pedido, praça, produto, lote, UA ou endereço de produção pode aparecer no dataset.

#### Scenario: Ordem mockada bate com as convenções do WMS
- **WHEN** uma ordem mockada é carregada
- **THEN** o cabeçalho traz `praca` alfanumérica de 11 caracteres e `pedido` numérico de 6 dígitos, e cada linha traz `produto` numérico de 6 dígitos, `partida` (lote) de 8 dígitos, `ua` e `recnum` de 8 dígitos, `ean` EAN-13 e `dun14` DUN-14 ambos com dígito verificador válido e derivados do mesmo código base, e endereço decomposto em `cd`/`setor`/`andar`/`predio`/`rua` — não valores placeholder nem uma nomenclatura inventada

#### Scenario: Endereço mockado gera o código de barras que a etiqueta do armazém carrega
- **WHEN** o código de barras de um endereço mockado é montado
- **THEN** ele segue o layout `cd(2 dígitos) + setor(2 dígitos) + andar(1 letra) + predio(4 dígitos, zero à esquerda) + rua`, com `andar` como letra e `predio` guardado sem zeros à esquerda, exatamente como o cadastro de endereço e o app de RF do WMS fazem

### Requirement: Repositório trocável sem tocar nos consumidores
O acesso a dados de ordem/produto/endereço/usuário DEVE ser exposto por uma interface. Substituir a implementação mockada por uma implementação real (ex: baseada em HTTP) NÃO DEVE exigir nenhuma mudança em código que dependa da interface.

#### Scenario: Trocando a implementação mockada
- **WHEN** a implementação mockada em memória é substituída por uma implementação diferente da mesma interface de repositório
- **THEN** nenhum consumidor do repositório precisa mudar, porque os consumidores dependem só da interface
