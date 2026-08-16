## Purpose

Fornece uma superfície de UI que visualiza o `PickingState` atual e permite a um desenvolvedor dirigir o `PickingActor` através de toques em botões, servindo de substituto para voz/câmera enquanto esses pipelines não existem e provando o padrão de ator em hardware real.

## ADDED Requirements

### Requirement: A tela reflete o estado atual do ator
A tela DEVE exibir o nome do estado de picking atual, atualizado sempre que o `PickingActor` publica um novo estado.

#### Scenario: Estado exibido após evento
- **WHEN** um evento é enviado ao `PickingActor` através de um botão
- **THEN** a tela exibe o nome do novo estado assim que o reducer o produzir, sem precisar reiniciar a tela

### Requirement: A tela usa dados reais do repositório mockado
Quando o estado atual referenciar um item em andamento, a tela DEVE exibir o produto e o endereço daquela linha, obtidos do `PickingRepository`, nunca valores fixos codificados na tela.

#### Scenario: Dados da linha exibidos durante o fluxo
- **WHEN** o estado avança para um estado que referencia uma linha de picking (ex: `EscaneandoProduto`)
- **THEN** a tela mostra o produto e o endereço daquela linha específica, carregados da ordem mockada, não um texto estático

### Requirement: Cada botão dispara exatamente um evento
Cada botão da tela DEVE corresponder a exatamente um `PickingEvent`, enviado ao ator sem transformação adicional.

#### Scenario: Botão de emergência funciona a partir de qualquer estado operacional
- **WHEN** o botão de parar/emergência é tocado em qualquer estado operacional
- **THEN** o evento de parar/emergência é enviado ao ator e o estado observado na tela se torna `SessaoPausada`, consistente com o comportamento já garantido pela capability `picking-state-machine`
