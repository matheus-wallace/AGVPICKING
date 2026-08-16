# Design: fallback de leitura por captura de foto

## Context

`ControladorDeVisao` já recebe HEVC do stream, mantém um decoder para análise e outro
para o preview, recorta a ROI central em NV21 e entrega a leitura ao ML Kit. Essa é a
via primária e não deve ser interrompida por uma captura. `Stream.capturePhoto()`
fornece a imagem de sensor com qualidade superior, mas traz custo de latência e exige
tratamento cuidadoso de memória e privacidade.

O documento técnico do produto define a captura como segunda etapa da cascata, após o
stream, condicionada por borda, estabilidade e desfoque; também define cooldown de
1,5 s, máximo de três tentativas e descarte imediato da foto completa após o recorte.

## Goals

- Escalar automaticamente para uma foto apenas quando o stream não tiver leitura e o
  enquadramento indicar uma chance razoável de sucesso.
- Reaproveitar ML Kit, a máquina de estados e a sessão de câmera já existentes.
- Preservar privacidade: somente ROI efêmera em memória, sem arquivo, UI ou log de
  imagem.
- Tornar o comportamento mensurável e testável com MockDeviceKit.

## Non-Goals

- OCR, VLM, ZXing, consulta remota, parsing GS1 ou nova regra de validação.
- Exibir a foto capturada ou alterar o preview de visão.
- Implementar a fala de orientação após timeout; este slice somente expõe o sinal de
  diagnóstico que poderá ser consumido pelo slice de áudio.

## Decisions

### 1. Métricas pequenas antes do gatilho

Será criado um componente puro de métricas para a ROI NV21. Ele gera, por quadro,
variância do Laplaciano (detalhe/desfoque) e uma miniatura de luminância limitada para
comparar o quadro atual ao anterior. A miniatura substitui a anterior a cada análise;
nenhum quadro completo é retido. Um `GatilhoDeCaptura` serializado recebe os valores
depois de uma tentativa do ML Kit sem leitura e controla três quadros estáveis,
tentativas, cooldown e tempo sem captura.

Os parâmetros (fator da ROI, limiares, quantidade de quadros, cooldown e máximo de
tentativas) entram em `AjustesVisao`. Valores iniciais serão validados em aparelho
físico; a estrutura não depende de constantes escondidas no controlador.

### 2. Captura continua no estado de escaneamento

`capturePhoto()` será iniciado enquanto o ator ainda está em `EscaneandoProduto`, que
é o estado que possui a câmera. O controlador bloqueia análises concorrentes durante
a captura. Depois que a foto for processada completamente em memória, ele publica
`CapturaDisparada` seguido do resultado (`DecodificacaoConcluida` ou, no limite,
`DecodificacaoFalhou`). Assim, a transição para `DecodificandoProduto` não encerra a
câmera no meio de uma chamada de captura e a regra de propriedade atual da câmera é
preservada.

Uma foto sem código antes do limite não publica eventos de domínio: libera o bloqueio,
inicia o cooldown e devolve a prioridade ao stream. Uma confirmação tardia só é aceita
se o ciclo de escaneamento que a iniciou ainda for o ciclo ativo.

### 3. Adaptador de foto com descarte determinístico

Um adaptador local converte as variantes de `PhotoData` (`Bitmap` e HEIC), aplica a
orientação EXIF quando houver, cria o recorte central com o mesmo fator configurado do
stream e entrega apenas esse recorte ao leitor ML Kit. A foto completa é reciclada
antes da leitura. A ROI é reciclada ao término da `Task` do ML Kit.

Todo caminho usa `try/finally`: bitmap, buffers e eventual arquivo temporário são
liberados; o aplicativo não cria arquivo de foto. Na entrada de um novo ciclo, um
limpador defensivo remove somente arquivos temporários explicitamente pertencentes a
capturas anteriores, caso uma execução interrompida tenha deixado algum. Nenhum nome
de arquivo, URI, bytes ou imagem será colocado em logs ou `DiagnosticoVisao`.

### 4. Leitor único e diagnósticos sem conteúdo visual

`LeitorDeCodigo` será estendido para aceitar uma entrada de foto recortada, mantendo
o mesmo conjunto de formatos, executor e consenso usados pelo stream. O controlador
continua sendo o único dono da câmera, dos jobs de visão e do contador de tentativas.
`DiagnosticoVisao` receberá apenas estado da captura, contadores, timestamps/durações,
métricas do gatilho e categoria de erro.

Marcadores de log propostos: `PHOTO_CAPTURE_TRIGGERED`, `PHOTO_CAPTURE_RESULT`,
`PHOTO_CAPTURE_CLEANUP` e `PHOTO_CAPTURE_EXHAUSTED`.

### 5. MockDeviceKit sem dependência de hardware

`MockDeviceBootstrap` ganhará um comando de depuração análogo ao feed de vídeo para
receber uma URI de imagem e chamar `setCapturedImage(uri)`. Esse recurso existe apenas
no caminho de debug e permite reproduzir sucesso, erro e retentativas em testes locais.

## Alternatives Considered

- **Capturar após qualquer falha do stream:** descartado por aumentar latência, consumo
  e capturas de baixa qualidade.
- **Salvar a foto em cache e decodificar depois:** descartado por violar a regra de
  descarte imediato e ampliar risco de privacidade.
- **Adicionar outro leitor de código:** descartado; ML Kit já é o leitor instalado e o
  objetivo do slice é melhorar a fonte de imagem, não criar uma cascata nova.
- **Mudar para um estado novo de domínio durante a chamada de captura:** descartado
  agora porque a máquina atual desliga a câmera fora de `EscaneandoProduto`; processar
  a foto antes de publicar a transição mantém o ciclo seguro e menor.

## Risks and Mitigations

- Limiar agressivo pode gerar fotos demais: limites configuráveis, logs e máximo de
  três tentativas reduzem o risco.
- Foto grande pode pressionar memória: recorte imediato, reciclagem e uma captura por
  vez.
- Foto pode levar tempo ou falhar na SDK: timeout/cancelamento, cooldown e retorno ao
  stream.
- Sem óculos físicos não há calibração real: MockDeviceKit cobre o fluxo e a tarefa de
  validação em aparelho físico fecha os parâmetros iniciais.

## Migration Plan

O comportamento entra desativado somente se a configuração de visão do ambiente o
desabilitar; a implantação normal usa os valores padrão. Não há migração de dados nem
arquivos persistidos. Em caso de regressão, desabilitar o gatilho devolve o aplicativo
ao caminho atual de leitura exclusiva pelo stream.
