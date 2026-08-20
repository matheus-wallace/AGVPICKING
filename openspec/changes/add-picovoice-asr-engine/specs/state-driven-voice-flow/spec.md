## MODIFIED Requirements

### Requirement: Gramática depende do estado operacional

O sistema DEVE (MUST) selecionar uma gramática fechada e um perfil de endpoint a partir
do `PickingState` atual. Uma troca de estado DEVE (MUST) invalidar qualquer resultado ASR
iniciado sob a configuração anterior. Quando o motor ativo for Picovoice Rhino, a seleção
de gramática por estado PODE (MAY) deixar de ser uma reconstrução de configuração em
memória (como hoje, via `SeletorDeEscuta`) e passar a envolver destruir e recriar a
instância do motor apontando para um contexto `.rhn` diferente, pré-compilado para o
agrupamento de estado — o comportamento observável de "o vocabulário aceito depende do
estado atual" NÃO DEVE (MUST NOT) mudar, independente de qual mecanismo o motor ativo usar.

#### Scenario: Resultado atrasado não avança o próximo estado

- **WHEN** o ator muda de `ConfirmandoQuantidade` para `ReadbackQuantidade` antes de um
  resultado final de quantidade ser entregue
- **THEN** o resultado atrasado é descartado
- **AND** nenhum evento novo é enviado ao ator.

#### Scenario: Troca de contexto Rhino por transição de estado

- **WHEN** o motor ativo é Picovoice Rhino e o ator muda de estado para um que exige um
  contexto `.rhn` diferente do carregado
- **THEN** o motor recria sua instância apontando para o novo contexto antes de aceitar a
  próxima janela de áudio
- **AND** nenhuma amostra capturada durante a troca é atribuída ao contexto anterior nem ao
  novo incorretamente
