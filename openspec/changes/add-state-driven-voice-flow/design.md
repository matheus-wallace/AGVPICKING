# Design: fluxo de separação comandado por voz

## Decisões

1. **A fala é interpretada pelo estado atual, mas o ator continua sendo o único a mudar
   estado.** `ReconhecedorDeComando` observa `PickingActor.state`, configura o Vosk e
   envia um `PickingEvent`; ele nunca escreve em `StateFlow` nem chama o reducer.

2. **A gramática é fechada em todos os estados, exceto exceção.** Isso reduz falsos
   positivos no galpão. `TratandoExcecao` usa o perfil de texto livre já previsto no
   documento; o relato passa por validação estruturada antes de `ExcecaoRegistrada`.

3. **A gramática troca somente depois de uma transição observada.** A troca fecha o
   recognizer anterior e cria o próximo na thread dedicada de áudio. Resultados finais
   associados a uma versão antiga de estado são descartados, impedindo que uma fala
   iniciada em uma etapa avance outra.

4. **A lista/seleção inicial de ordem e a divergência permanecem contingências de tela.**
   A operação de separação, depois de a ordem estar selecionada, não exige toque. A
   seleção de uma ordem por voz (por identificador ou lista falada) só entra se houver
   requisito de eliminar também a tela de preparação; não deve ser inventada contra o
   dataset/mock atual. A tela de divergência é o fallback documentado para falha de voz.

5. **Não existe confirmação cega.** Check digit falado é comparado localmente com o dado
   esperado e só então vira `CheckDigitCorreto`/`CheckDigitIncorreto`. Quantidade sempre
   segue para `ReadbackQuantidade`; “confirmar” e “corrigir” apenas mapeiam para os
   eventos de readback existentes.

6. **TTS e ASR não devem disputar uma instrução.** Enquanto a fala do sistema está em
   reprodução, o ASR suspende a aceitação de resultados e reinicia a janela de endpoint
   ao terminar. Comandos ditos depois da fala (incluindo “parar”) devem continuar
   funcionando; a política de barge-in só pode ser adotada após medição no aparelho.

## Mapeamento de entrada

| Estado | Fala aceita | Evento/transição |
|---|---|---|
| `OrdemCarregada` | “iniciar” | `NavegacaoIniciada` |
| `NavegandoParaEndereco` | “cheguei” | `EnderecoAlcancado` |
| `AguardandoCheckDigit` | dois dígitos | `CheckDigitCorreto` ou `CheckDigitIncorreto` |
| `EscaneandoProduto` | “cancelar”/transversais | somente evento transversal; código vem da visão |
| `ConfirmandoQuantidade` | número inteiro permitido | `QuantidadeInformada` |
| `ReadbackQuantidade` | “confirmar” / “corrigir” | `ReadbackConfirmado` / `ReadbackCorrecaoSolicitada` |
| `AlocandoCarrinho` | “alocado” | `ItemAlocado` |
| `ItemConcluido` | “próximo” | `ItemFinalizado` com próxima linha resolvida |
| `ConferenciaFinal` | “concluir” | `ConferenciaConcluida` |
| `OrdemConcluida` | “encerrar” | `OrdemEncerrada` |

`parar`, `emergência`, `repetir`, `avaria`, `ruptura` e `divergência` permanecem
disponíveis nos estados operacionais em que o reducer já os aceita. Estados que esperam
processamento de câmera/rede não recebem uma fala de avanço comum: o produtor que iniciou
o processamento é o responsável pelo seu resultado.

## Non-goals

- Trocar a fonte de microfone do celular pelo HFP dos óculos.
- Fazer a seleção/pareamento inicial por voz.
- Remover os botões do build de desenvolvimento.
- Alterar a cascata de visão ou o contrato do `PickingRepository` de produção.
