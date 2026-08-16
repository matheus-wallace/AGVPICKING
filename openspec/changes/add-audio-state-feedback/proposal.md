# Proposal: saída de áudio orientada ao estado

## Why

O aplicativo já reconhece comandos, cria sessões e lê códigos, mas não comunica ao
operador o próximo passo por voz. Isso deixa o fluxo dependente da tela do celular e
torna inútil o sinal de enquadramento produzido pela visão após oito segundos sem
leitura. A próxima fatia deve tornar perceptível o estado operacional sem antecipar a
integração HFP/Piper dos óculos.

## What Changes

- Criar uma abstração de saída de áudio e uma implementação de desenvolvimento baseada
  no sintetizador Android do celular.
- Projetar estados relevantes do picking em mensagens curtas e testáveis: sessão
  pronta, endereço, confirmação, escaneamento, quantidade, sucesso, erro e exceção.
- Consumir o sinal não visual de orientação da visão para falar, uma vez por ciclo,
  “aponte para o código do produto”.
- Aplicar fila, deduplicação e prioridade: alertas críticos preemptam mensagens de
  rotina; uma mensagem não deve repetir em recomposições ou emissões idênticas.
- Manter o contrato pronto para uma futura implementação Piper/HFP, sem alterar ASR,
  câmera, domínio ou dados mockados nesta fatia.

## Capabilities

### New Capabilities

- `audio-state-feedback`: comunica instruções operacionais e orientação de visão por
  áudio local, a partir de estados e diagnósticos já existentes.

### Modified Capabilities

Nenhuma. A saída de áudio ainda não possui especificação arquivada; o reconhecimento
de comandos continua com seu comportamento atual.

## Impact

- Novo pacote `audio/output/` e fiação no `AppContainer` e ciclo de vida da Activity.
- Leitura de `PickingActor.state` e do diagnóstico de visão; nenhuma escrita direta de
  estado e nenhum acesso a pixels.
- Pode requerer a inicialização assíncrona do motor TTS Android e tratamento explícito
  para motor/idioma indisponível.
