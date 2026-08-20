# Modelo de idioma do Picovoice Rhino — proveniência

Modelo de idioma do `MotorPicovoiceRhino` (change `add-picovoice-asr-engine`). Versionado no git
pela mesma decisão que já vale para o modelo Vosk e para o Silero VAD: um `git clone` que já
compila vale o espaço.

Baixado em 19/08/2026.

## Português (`rhino_params_pt.pv`)

| | |
|---|---|
| Arquivo | `rhino_params_pt.pv` |
| Origem | <https://raw.githubusercontent.com/Picovoice/rhino/master/lib/common/rhino_params_pt.pv> |
| Tamanho | 2.107.465 B |
| SHA-256 | `b2c3e28dab7966799f4598c765e43bbea7da626beaa60e7533b8a09e06627ffe` |
| Licença | Apache-2.0 (licença do repositório `Picovoice/rhino`, confirmada em `GET /repos/Picovoice/rhino/license` → `"spdx_id": "Apache-2.0"`; o `.pom` de `ai.picovoice:rhino-android` declara a mesma) |

O tamanho bate com o que a API do GitHub declara para o blob (`"size": 2107465`), então o
download não veio truncado.

## Por que este arquivo precisa estar aqui

São **duas coisas diferentes**, e confundi-las é o erro fácil deste motor:

- **o modelo de idioma** (`.pv`) — artefato fixo que a Picovoice publica, um por idioma
  suportado. É este arquivo;
- **o contexto** (`.rhn`) — o vocabulário fechado deste projeto, compilado manualmente no
  Picovoice Console. Fica em `../contexto-picovoice/` e **ainda não existe** (ver o
  PROVENIENCIA.md de lá).

O `.aar` de `ai.picovoice:rhino-android:4.0.2` embute **só o modelo de inglês**, em
`res/raw/rhino_params.pv` — verificado com `unzip -l` sobre o `.aar` resolvido do Maven Central,
não pela documentação. É o que o `Rhino.Builder` usa quando `setModelPath` não é chamado
(`Rhino.java`, `build()`: `if (modelPath == null) modelPath = DEFAULT_MODEL_PATH`), e é
exatamente por isso que o `MotorPicovoiceRhino` **sempre** chama `setModelPath` — sem essa
chamada o motor rodaria em inglês sem reclamar de nada.

Os modelos dos outros idiomas ficam em `lib/common/` do repositório `Picovoice/rhino`:
`de`, `es`, `fr`, `it`, `ja`, `ko`, `pt`, `zh`. A lista de idiomas aceitos também aparece no
próprio código do SDK (`Rhino.VALID_LANGUAGES`), e `pt` está lá — o que confirma "português"
como idioma suportado, **mas não confirma pt-BR**: nada no artefato distingue a variante, e essa
continua sendo a pergunta que só a bancada responde (design.md - Decisão 4).

## Como o SDK consome este caminho

`Rhino.Builder.setModelPath` aceita um caminho **relativo a `assets/`**: o `build()` testa se o
caminho existe no sistema de arquivos e, se não existir, abre o asset e o copia para
`context.getFilesDir()` (`Rhino.java`, `build()` → `extractResource`). Ou seja, o caminho passado
pelo `MotorPicovoiceRhino` é `modelo-picovoice-rhino/rhino_params_pt.pv`, e a cópia para o
armazenamento interno é feita pelo próprio SDK — não há `StorageService` como no Vosk.

Asset ausente não mata o processo: o `IOException` do `AssetManager` vira `RhinoIOException`, que
é `RhinoException`, que é `Exception` comum. O `runCatching` do `carregar()` a pega e devolve
`false`. É o oposto do sherpa-onnx, cujo arquivo faltando chamava `_Exit` — e por isso este motor
não precisa da conferência defensiva de assets que o `MotorSherpaOnnx` faz.

## Ao trocar de idioma ou de versão do SDK

Trocar de idioma é trocar este arquivo e a constante `MODELO` em `MotorPicovoiceRhino` — mais
nada. Ao subir a versão de `ai.picovoice:rhino-android`, conferir se o `.pv` continua compatível:
a Picovoice versiona modelo e runtime juntos, e um modelo de outra geração é recusado na
inicialização.
