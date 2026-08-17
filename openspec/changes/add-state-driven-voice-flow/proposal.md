# Proposal: fluxo de separação comandado por voz

## Why

O fluxo operacional ainda depende dos botões do painel de desenvolvimento para
avançar entre etapas. O reconhecimento atual só entende `parar` e `repetir`; portanto,
depois de receber uma instrução falada, o operador precisa olhar para o celular e tocar
em “próximo”. Isso contradiz o objetivo de uma separação hands-free e impede validar o
fluxo real de ponta a ponta.

O painel continua útil para desenvolvimento, mas não pode continuar sendo o controlador
da operação. Cada ação normal da separação deve vir de fala, de uma leitura da câmera ou
de uma transição automática já segura.

## What Changes

- Substituir a gramática fixa do reconhecedor por uma gramática selecionada pelo
  `PickingState`, usando os perfis de endpoint já definidos em `PerfilEndpoint`.
- Interpretar o resultado final de ASR no contexto do estado e publicar somente os
  `PickingEvent`s existentes e válidos para aquela etapa.
- Cobrir início/continuidade da ordem, chegada ao endereço, check digit, quantidade,
  readback, alocação, fechamento e comandos transversais de parar, repetir e exceção.
- Validar check digits e resolver a próxima linha pelo `PickingRepository`, sem expor o
  valor esperado na fala, na UI ou nos logs.
- Manter a câmera como produtora exclusiva dos eventos de leitura óptica e preservar a
  tela de divergência por toque como contingência explícita quando a voz não funcionar.
- Rebaixar os botões do painel a recurso de desenvolvimento: eles não são necessários
  para concluir uma ordem normal.

## Capabilities

### New Capabilities

- `state-driven-voice-flow`: reconhecimento e interpretação de voz dependentes do estado
  para dirigir a separação sem toques durante a operação normal.

### Modified Capabilities

- `audio-source`: a gramática deixa de ser fixa em `parar`/`repetir` e passa a trocar de
  modo com segurança nas transições de estado.
- `dev-event-panel`: os botões permanecem para diagnóstico, mas não representam o caminho
  operacional obrigatório.

## Impact

- `audio/ReconhecedorDeComando` passa a observar o estado sem publicar mudanças diretas;
  um interpretador puro separa texto reconhecido de regras de domínio e I/O.
- `AppContainer` recebe as dependências de repositório necessárias para validar a fala e
  escolher o próximo item.
- Requer a saída de áudio orientada ao estado para instruir o operador e uma validação
  física de ciclo completo com microfone. A rota HFP dos óculos continua uma fatia
  posterior, atrás da mesma abstração `FonteAudio`.
