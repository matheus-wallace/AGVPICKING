## Purpose

Traduz o ciclo de vida real do SDK do DAT — registro do app, criação e estado da sessão do dispositivo, pausas e perdas de conexão — para os `PickingEvent`s de sessão que a `picking-state-machine` já sabe processar, substituindo o autobootstrap simulado por um produtor de eventos que fala com o dispositivo de verdade (físico em produção, MockDeviceKit em debug).

## ADDED Requirements

### Requirement: Registro do dispositivo inicia a sessão
Assim que o app sai do estado inicial (permissões Android concedidas), o sistema DEVE iniciar o registro do dispositivo junto ao SDK do DAT e publicar um evento de registro iniciado. Quando o SDK reportar o registro concluído, o sistema DEVE publicar um evento de registro concluído; quando o SDK reportar falha de registro, o sistema DEVE publicar um evento de registro falhado com o detalhe fornecido pelo SDK.

#### Scenario: Registro bem-sucedido
- **WHEN** o app inicia o registro e o SDK reporta o dispositivo como registrado
- **THEN** um evento de registro concluído é publicado

#### Scenario: Registro falha
- **WHEN** o app inicia o registro e o SDK reporta um erro de registro
- **THEN** um evento de registro falhado é publicado, carregando o detalhe do erro relatado pelo SDK

### Requirement: Sessão do dispositivo é criada após o registro
Depois que o registro é concluído, o sistema DEVE criar e iniciar uma sessão do dispositivo junto ao SDK do DAT. Quando a sessão atingir o estado ativo, o sistema DEVE publicar um evento de sessão preparada; quando a criação falhar ou a sessão não atingir o estado ativo, o sistema DEVE publicar um evento de sessão falhada com o detalhe disponível.

#### Scenario: Sessão sobe com sucesso
- **WHEN** o registro foi concluído e a sessão criada atinge o estado ativo
- **THEN** um evento de sessão preparada é publicado

#### Scenario: Sessão não sobe
- **WHEN** o registro foi concluído mas a criação da sessão falha ou a sessão nunca atinge o estado ativo
- **THEN** um evento de sessão falhada é publicado com o detalhe disponível

### Requirement: Pausa relatada pelo SDK publica evento de pausa
Quando a sessão ativa passa a reportar estado de pausa, o sistema DEVE publicar um evento de pausa do DAT. Quando essa mesma sessão volta a reportar estado ativo, o sistema DEVE publicar um evento de sessão retomada.

#### Scenario: Sessão pausa
- **WHEN** a sessão está ativa e o SDK reporta o estado de pausa
- **THEN** um evento de pausa do DAT é publicado

#### Scenario: Sessão retoma sozinha
- **WHEN** uma sessão pausada volta a reportar estado ativo
- **THEN** um evento de sessão retomada é publicado

### Requirement: Encerramento inesperado da sessão publica perda de conexão
Quando a sessão ativa (ou pausada) passa a reportar estado encerrado sem ter sido solicitado pelo próprio fluxo operacional, o sistema DEVE publicar um evento de perda de conexão Bluetooth. Quando o dispositivo voltar a ficar disponível depois disso, o sistema DEVE criar uma nova sessão e, assim que ela atingir o estado ativo, publicar um evento de conexão Bluetooth restabelecida.

#### Scenario: Sessão cai inesperadamente
- **WHEN** a sessão está ativa e o SDK reporta o estado encerrado sem que o fluxo tenha pedido o encerramento
- **THEN** um evento de perda de conexão Bluetooth é publicado

#### Scenario: Conexão volta depois de uma queda
- **WHEN** o dispositivo volta a ficar disponível após uma perda de conexão
- **THEN** uma nova sessão é criada e, ao atingir o estado ativo, um evento de conexão Bluetooth restabelecida é publicado

### Requirement: Sessão de debug roda contra dispositivo simulado
Em build de debug, o sistema DEVE parear, ligar e colocar em uso automaticamente um dispositivo simulado pelo MockDeviceKit antes de iniciar o registro, de forma que todo o ciclo de vida descrito nos requisitos acima seja exercitável sem hardware físico. Em build de release, o mesmo componente DEVE operar sem nenhuma dependência do MockDeviceKit, sem exigir troca de código além da seleção de build.

#### Scenario: Fluxo completo roda sem óculos físicos
- **WHEN** o app é iniciado em build de debug, sem nenhum dispositivo Bluetooth real pareado
- **THEN** um dispositivo simulado é disponibilizado automaticamente e o ciclo de vida de registro e sessão progride normalmente até o evento de sessão preparada
