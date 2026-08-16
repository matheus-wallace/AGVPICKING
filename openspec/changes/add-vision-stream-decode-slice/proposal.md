## Why

Áudio e sessão já têm produtores reais de `PickingEvent` (`add-dat-session-mockdevice`, `add-audio-single-grammar-slice`), mas a câmera continua sendo o único sensor do sistema que ninguém jamais ligou: `DecodificacaoConcluida` só existe hoje como um botão do painel de dev. O doc §15 classifica "DataMatrix não decodifica em 720p" como risco de **probabilidade alta e impacto alto**, e o doc §6.2 aposta explicitamente que o passo 1 da cascata — decodificar o próprio stream, sem foto — resolve o caso comum da etiqueta de expedição. Enquanto esse passo não roda contra frames de verdade, a aposta é só uma frase no documento, e a frente de maior risco do projeto (doc §13.2, Paulo Henrique) não tem nenhum número seu. Fazer a fatia mais fina agora é o que permite chegar em 18/09 com a varredura de distância do §10.2 medindo alguma coisa em vez de ainda estar sendo escrita.

## What Changes

- Novo pacote `vision/` com um produtor de evento no mesmo padrão de `dat/DatSessionController` e `audio/ReconhecedorDeComando`: liga o stream de câmera da `DeviceSession` já existente, roda ML Kit sobre os frames e publica `PickingEvent.DecodificacaoConcluida(codigoLido)` no `PickingActor`.
- A câmera é ligada **e desligada** pelo estado do ator: o stream existe enquanto o estado for `EscaneandoProduto` e não existe em nenhum outro momento — é o doc §3.4.3/§8 ("câmera desligada na maior parte do ciclo") e a base da afirmação de privacidade do §9.2. É a primeira vez que um componente do app observa o estado do ator em vez de só publicar nele.
- Recorte de 60% central aplicado a cada frame **antes** de qualquer tentativa de decodificação, com o frame completo liberado explicitamente logo em seguida, em `finally` — doc §4.4 e §6.3. Nenhum frame é gravado em disco em momento algum.
- Nova transição no reducer: `EscaneandoProduto` + `DecodificacaoConcluida` → `ValidandoContraDados`, que é o "se decodificou, acabou: sem foto" do doc §6.2. `CapturaDisparada` e o estado `DecodificandoProduto` continuam existindo intocados, para o caminho de escalonamento por foto do Marco 2.
- `MEDIUM` (504×896) a **7 fps**, os valores que o doc §8 fixa por bateria e qualidade por frame, expostos junto com o fator de recorte num `AjustesVisao` lido de arquivo no aparelho — mesmo mecanismo de calibração sem recompilar já usado por `AjustesAsr`, e o que o doc §6.1/§10.2 exige ao dizer que distância e recorte são parâmetro, não constante.
- Permissão de câmera em duas camadas: a permissão Android (necessária em debug porque o MockDeviceKit pode espelhar a câmera do celular) e a permissão de câmera do próprio DAT (`Wearables.checkPermissionStatus(Permission.CAMERA)`), com o mesmo tratamento de degradação graciosa das fatias anteriores — sem permissão, o painel de dev continua dirigindo o fluxo por toque.
- Bootstrap de debug ganha uma fonte de imagem para o óculos simulado (`services.camera.setCameraFeed`), para que a bancada tenha o que decodificar sem óculos físico.
- Nova dependência: `com.google.mlkit:barcode-scanning` na distribuição **bundled** (doc §6.3 — a via Play Services baixa o modelo no primeiro uso e quebra o requisito offline).

## Capabilities

### New Capabilities
- `vision-stream-decode`: leitura de código de barras a partir do stream de câmera do DAT, ligada apenas no estado de escaneamento, com recorte de ROI e liberação determinística do frame, publicando o código lido como evento no ator único.

### Modified Capabilities
- `picking-state-machine`: a leitura pelo stream conclui o escaneamento direto, sem passar pelo estado de captura/decodificação — `EscaneandoProduto` passa a aceitar `DecodificacaoConcluida`.

## Impact

- Código novo: pacote `vision/` (controlador de stream, decodificador HEVC com saída por buffer, recorte de ROI, leitor ML Kit, `AjustesVisao`).
- Código alterado: `PickingReducer.kt` (uma transição), `AppContainer.kt` (fiação do novo componente, que precisa do `PickingActor` nos dois sentidos e da `DeviceSession` viva), `DatSessionController.kt` (expor a sessão corrente para quem adiciona a capability de câmera), `MainActivity.kt` (permissão `CAMERA`), `AndroidManifest.xml`, `build.gradle.kts` e `gradle/libs.versions.toml` (ML Kit).
- Código alterado só em debug: `MockDeviceBootstrap.kt` (fonte de imagem do óculos simulado).
- Sem mudança no contrato de dado mockado. O que a validação faz com o código lido continua sendo o que já é hoje — esta fatia entrega o código, não a comparação contra a ordem.
- Nenhuma chamada de rede: ML Kit bundled roda offline, e o passo de VLM do doc §6.4 está fora desta fatia.
