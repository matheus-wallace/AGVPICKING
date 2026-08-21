# Seção B — Diagrama de arquitetura

## Arquivo de imagem para upload

Use [agv-pick-voice-arquitetura.svg](agv-pick-voice-arquitetura.svg) como a imagem do diagrama. O arquivo é vetorial e pode ser enviado diretamente onde o Ideathon aceitar SVG.

## Código-fonte Mermaid

```mermaid
flowchart TB
  subgraph G[Óculos Meta]
    CAM[Câmera]
    SPK[Alto-falantes open-ear]
  end

  subgraph A[Aplicativo Android Kotlin]
    DAT[Meta Wearables DAT<br/>Sessão e permissões]
    SM[PickingActor<br/>Máquina de estados]
    CFG[Central de configurações<br/>ASR e câmera]
    ASR[ASR local<br/>Rhino principal / Vosk alternativo]
    TTS[TTS local<br/>Android TextToSpeech pt-BR]
    VIS[Visão local<br/>HEVC -> recorte -> ML Kit]
    CMP[Comparador de código<br/>+ consenso]
    REPO[PickingRepository<br/>dados mockados em memória]
    LOG[Logs e diagnóstico<br/>sem imagens]
  end

  subgraph X[Exceção opcional]
    VLM[Verificação assistida<br/>rede somente após falha local]
  end

  CAM -->|stream/foto sob demanda| DAT --> VIS --> CMP --> SM
  ASR -->|comandos, dígitos e relato| SM
  CFG -. escolhe motor .-> ASR
  CFG -. calibra vídeo .-> VIS
  REPO -->|ordem, item e endereço| SM
  SM -->|instruções e alertas| TTS --> SPK
  SM --> LOG
  CMP -. leitura local sem resultado .-> VLM
  VLM -. resultado assistido .-> SM
```

## Leitura do diagrama

- Os óculos fornecem câmera e reprodução de áudio; o smartphone hospeda o aplicativo e o processamento.
- A máquina de estados é o ponto único que aceita eventos e decide transições.
- A central de configurações, persistida para a bancada, escolhe o motor ASR e calibra qualidade, FPS, rotação e captura por foto. Alterações são aplicadas após reiniciar o app.
- Ordem e dados de referência vêm de memória no protótipo; não há integração WMS nesta entrega.
- A verificação assistida é opcional e só acontece depois de falha no caminho local.
