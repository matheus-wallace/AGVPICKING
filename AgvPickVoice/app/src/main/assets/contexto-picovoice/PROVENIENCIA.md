# Contexto do Picovoice Rhino — proveniência

Contexto (`.rhn`) do `MotorPicovoiceRhino` (change `add-picovoice-asr-engine`).

## Estado atual: o arquivo existe

| | |
|---|---|
| Arquivo | `picovoice-pt.rhn` |
| Constante que o consome | `MotorPicovoiceRhino.CONTEXTO` |
| Origem | Picovoice Console (<https://console.picovoice.ai/>), conta do trial — compilado a partir de `picovoice-pt.yaml` deste mesmo diretório |
| Nome de download do Console | `AGVTRONIC_pt_android_v4_0_0.rhn` (renomeado para `picovoice-pt.rhn` ao vendorizar — o nome não muda o conteúdo, é só o caminho que `CONTEXTO` espera) |
| Baixado/vendorizado em | 19/08/2026 |
| Tamanho | 10.388 B |
| SHA-256 | `7f359f118186fd382cad94128e5d9bb90e87e34c50899702899368e1bc174bf2` |
| Licença | Picovoice, termos em <https://picovoice.ai/docs/terms-of-use/> (`LICENSE.txt` do pacote de download, conta de trial — não redistribuível fora deste uso) |

Um `.rhn` é artefato binário compilado pelo Console a partir do YAML de contexto, específico de
idioma **e** de plataforma (o de Android não serve no de iOS).

**Não verificado ainda**: se este contexto compilado cobre de fato todas as expressões do YAML
fonte (`picovoice-pt.yaml`, tarefas 3.1-3.3) sem erro/corte silencioso do Console — isso só se
confirma rodando o motor e conferindo o log de carga de `SintetizadorDeIntencaoRhino.INTENCOES`
contra o Console, e depois na bancada (tarefa 6, grupo "Bancada comparativa").

## O que o contexto precisa declarar

O outro lado do contrato é o `SintetizadorDeIntencaoRhino`: ele converte `intent`/`slots` em texto
para o `InterpretadorDeFala`, e o que ele não souber traduzir vira texto vazio (nenhum evento, uma
linha no log). As duas metades têm que casar.

### Intenções de palavra única

O nome da intenção é **a palavra do `VocabularioDeVoz` sem acento** — nome de intenção no Console
é identificador, e `próximo` não é aceito. A lista completa está em
`SintetizadorDeIntencaoRhino.INTENCOES` e também é impressa no logcat quando o motor carrega:

| Intenção | Texto sintetizado |
|---|---|
| `iniciar` | `iniciar` |
| `cheguei` | `cheguei` |
| `confirmar` | `confirmar` |
| `corrigir` | `corrigir` |
| `alocado` | `alocado` |
| `proximo` | `próximo` |
| `concluir` | `concluir` |
| `encerrar` | `encerrar` |
| `retomar` | `retomar` |
| `parar` | `parar` |
| `emergencia` | `emergência` |
| `repetir` | `repetir` |
| `avaria` | `avaria` |
| `ruptura` | `ruptura` |
| `divergencia` | `divergência` |

### Intenções com slot (check digit e quantidade)

Intenção **com slot** é tratada por outro caminho: o nome da intenção é ignorado e o **valor do
slot vira o texto inteiro**. Por isso o nome dessas intenções é livre (`check_digit`,
`quantidade`, o que for) — o que importa é o valor enumerado do slot.

O valor pode ser enumerado no Console em qualquer das duas formas que o operador usa, porque as
duas funcionam sem o app saber qual veio:

- algarismos fundidos — o slot devolve `47`, e `VocabularioDeVoz.digitos("47")` dá `"47"`;
- por extenso — o slot devolve `quarenta e sete`, e `VocabularioDeVoz.checkDigitExtenso` dá
  `"47"`.

Isso é o que a tarefa 3.2 tem de cobrir: as variações de fala do check digit por extenso são
**autoria manual** no Console, uma a uma, e é o custo real da rota (design.md - Risks).

## Ao trocar o contexto

Recompilar no Console, substituir o arquivo aqui e reinstalar. Não há cópia velha a invalidar do
lado do app: o `Rhino.Builder` reextrai o asset para o armazenamento interno a cada construção
(`Rhino.java`, `build()` → `extractResource`), sobrescrevendo o que estiver lá. Registrar abaixo,
quando o arquivo existir, a data de compilação, o tamanho e o SHA-256 — mesma tabela dos outros
modelos vendorizados deste projeto.
