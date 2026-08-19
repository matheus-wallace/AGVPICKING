# Modelos sherpa-onnx — proveniência

Modelos do `MotorSherpaOnnx` (change `add-sherpa-onnx-asr-engine`). Ficam versionados no git
pela mesma decisão que já vale para o modelo Vosk: um `git clone` que já compila vale o
espaço, num projeto em que a manhã de 18/09 não pode ter passo de setup que falhe.

Baixados em 18/08/2026, todos de *releases* oficiais do `k2-fsa/sherpa-onnx` (Apache-2.0).

## Silero VAD

| | |
|---|---|
| Arquivo | `silero_vad.onnx` |
| Origem | <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx> |
| Tamanho | 643.854 B |
| SHA-256 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Substitui o endpointer por silêncio do Vosk. **Só aceita 16 kHz** — a `FonteAudio` deste
projeto entrega 8 kHz, e o `MotorSherpaOnnx` reamostra antes de alimentá-lo. Taxa errada não
lança exceção: `SHERPA_ONNX_EXIT` chama `_Exit` e mata o processo. Ver design.md do change,
seção "Verificação da API do sherpa-onnx", itens (a) e (b).

## Whisper tiny multilíngue, quantizado int8

> **Os arquivos abaixo não estão mais neste diretório.** O change
> `add-sherpa-onnx-omnilingual-decoder` removeu `whisper-tiny/` depois que a bancada de
> 18/08/2026 mediu o decodificador alucinando em comandos curtos de pt-BR; o motor passou a
> usar `../modelo-sherpa-onnx-omnilingual/`. O registro fica aqui de propósito, para que a
> volta seja um `git checkout` do diretório sem perder a proveniência dele
> (design.md daquele change - Decisão 4). O Silero VAD acima **não** foi afetado: ele é
> independente do decodificador e continua em uso.

| | |
|---|---|
| Pacote | `sherpa-onnx-whisper-tiny.tar.bz2` (116.204.861 B compactado) |
| Origem | <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2> |
| SHA-256 do pacote | `c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1` |

Do pacote entram só os três arquivos abaixo — os `.onnx` em fp32 (`tiny-encoder.onnx`,
`tiny-decoder.onnx`, 152 MB somados) e os WAVs de teste ficam de fora.

| Arquivo | Tamanho |
|---|---|
| `whisper-tiny/tiny-encoder.int8.onnx` | 12.937.772 B |
| `whisper-tiny/tiny-decoder.int8.onnx` | 89.855.401 B |
| `whisper-tiny/tiny-tokens.txt` | 816.730 B |

É a variante **multilíngue** (`sherpa-onnx-whisper-tiny`), não a `tiny.en`: o vocabulário
de 51.865 tokens é o que faz o decodificador sozinho pesar 86 dos 99 MB, e é também a única
razão de o modelo saber português. `tiny.en` seria muito menor e completamente inútil aqui.

O `language` do `OfflineWhisperModelConfig` fica em `"pt"` — o modelo é multilíngue por
característica dele, não porque o app precise detectar idioma.

## Ao trocar de modelo

Diferente do Vosk, **não há arquivo `uuid` nem cópia para `getExternalFilesDir`**: o
sherpa-onnx lê os modelos direto do `AssetManager`. Trocar os arquivos aqui e reinstalar já
basta; não existe cópia velha desempacotada no aparelho para invalidar.

Se trocar `tiny` por `base` (tarefa 6.4 do change), os nomes de arquivo mudam junto —
`base-encoder.int8.onnx` etc. — e os caminhos ficam em `MotorSherpaOnnx`, num lugar só.
