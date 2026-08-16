# Modelo Vosk pt — proveniência

| | |
|---|---|
| Modelo | `vosk-model-small-pt-0.3` |
| Origem | <https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip> |
| Baixado em | 16/08/2026 |
| Tamanho | 31 MB compactado, 51 MB descompactado |
| Licença | Apache 2.0 (mesma do `vosk-api`) |

Modelo de ASR local do doc §5, usado pelo `ReconhecedorDeComando`. Fica versionado
no git por decisão explícita — ver `openspec/changes/add-audio-single-grammar-slice/design.md`,
Decisão 5: um `git clone` que já compila vale os 51 MB, num projeto em que a manhã
de 18/09 não pode ter passo de setup que falhe.

É o menor modelo pt publicado pelo projeto Vosk. O único outro (`vosk-model-pt-fb-v0.1.1`)
tem 1,69 GB e está fora de questão para embutir num APK.

## O arquivo `uuid`

Não vem no zip oficial — foi criado aqui. O `StorageService.sync` do `vosk-android`
lê `<assets>/uuid`, compara com a cópia já desempacotada em `getExternalFilesDir()` e
só recopia os 51 MB quando os dois diferem. Sem esse arquivo, `sync` lança `IOException`.

**Ao trocar de modelo, mude o conteúdo do `uuid` junto** — senão os aparelhos que já
rodaram uma versão anterior seguem usando o modelo velho já desempacotado, e a troca
não tem efeito nenhum até alguém limpar os dados do app.
