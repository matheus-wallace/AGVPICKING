# AGV Pick Voice — Documentação Técnica de Desenvolvimento

**Equipe AGVTronic** · AI Glasses Brasil 2026 · Trilha Produtividade
**Versão 1.5** — 15/08/2026

> Documento **interno de engenharia**. A Entrega Final de 22/08 usa o template
> obrigatório da organização — este documento alimenta aquele, não o substitui.

> **Mudança v1.4 → v1.5:** abstração da fonte de áudio para tornar o pipeline
> de voz testável antes de 18/09, log estruturado como requisito de arquitetura,
> plano de desenvolvimento de agosto, e carregamento antecipado do modelo Vosk.

> **Mudança v1.3 → v1.4:** descarte do quadro completo imediatamente após o
> recorte (60% centrais), com a cascata operando apenas sobre a região retida.
> Cooldown de 1,5 s em falha total aceito como custo. §9 reforçada.

> **Mudança v1.2 → v1.3:** ergonomia de captura definida (caixa a ~20 cm do
> rosto), retenção do frame completo até o fim da cascata, e §9 reescrita com
> afirmações verificáveis em código.

> **Mudança v1.1 → v1.2:** a restrição de rede vale **apenas para o WMS**.
> Ordem, produto, lote, endereço e usuário são mockados em memória e nunca
> são buscados, validados ou gravados via API. Outras APIs — notadamente o VLM
> de verificação visual — permanecem disponíveis nos caminhos de exceção.

---

## 1. Escopo

### 1.1 A hipótese sob teste

> Um operador consegue completar um ciclo de separação **mãos-livres**, guiado
> por voz e validado por visão, com acurácia igual ou superior e tempo de ciclo
> menor que o processo atual com coletor RF.

Tudo neste documento existe para testar essa afirmação. O que não contribui para
testá-la está fora de escopo.

### 1.2 Mock de dados, nunca de sensor

Esta é a regra que define a validade do experimento.

| Camada | Protótipo |
|---|---|
| Ordem, produto, lote, série, endereço, usuário | **Mockado** em memória, zero chamadas |
| Captura de áudio dos óculos | **Real** |
| Reconhecimento de fala | **Real** |
| Captura de imagem dos óculos | **Real** |
| Decodificação de código de barras | **Real** |
| Síntese e saída de áudio | **Real** |
| Verificação assistida por VLM (exceção) | **Real**, via API |

**Mockar o WMS é correto** porque a integração com WMS não é o que está em
dúvida — a AGV já opera um WMS e sabe integrá-lo. O risco está no laço de
áudio e na decodificação pela câmera, e ambos são testáveis integralmente com
dados fictícios.

**Mockar o resultado de decodificação invalidaria o experimento.** Se o app
"reconhece" o produto porque o código estava no mock, nada foi provado. A caixa
tem que ser física, o código tem que ser lido de verdade, e a falha de leitura
tem que ser uma falha real.

### 1.3 Fora de escopo

- Qualquer chamada ao WMS: buscar, validar ou gravar dados de separação.
- Backend próprio, autenticação, API REST da AGV.
- Persistência entre execuções — a ordem reinicia a cada sessão.
- Otimização de rota por IA — **removido permanentemente**. É problema de
  roteamento clássico; resolver com LLM sinaliza imaturidade técnica.
- Suporte iOS.

### 1.4 Stack definido

| Decisão | Escolha |
|---|---|
| App | Kotlin nativo novo, separado do app React Native existente |
| Fonte de dados | Repositório em memória, interface preparada para HTTP |
| STT | Vosk small pt-BR com gramática dinâmica |
| VAD / endpointing | Silero VAD via ONNX Runtime, endpointing próprio |
| Leitura de código | ML Kit **bundled** + zxing-cpp em cascata |
| TTS | Inventário pré-renderizado com Piper (build time) + concatenação |
| IA local (laço crítico) | Vosk (ASR) + ML Kit (visão) + Silero (VAD) |
| IA em nuvem (exceção) | VLM para verificação visual; LLM para relato livre |

**Princípio comum às quatro escolhas:** restringir o problema até que a
ferramenta modesta baste, e reservar a ferramenta pesada para onde a restrição
não se aplica.

### 1.5 A premissa que define o dia 18

O cronograma dá **~4 horas de código real** (11h–12h, 14h–17h30, descontando
checkpoints). O app chega em 18/09 **rodando completo contra Mock Device Kit**.
O dia é troca do device selector, calibração e polimento.

---

## 2. Restrições da plataforma

Extraídas da documentação oficial do Meta Wearables Device Access Toolkit.

### 2.1 Áudio

| Perfil | Direção | Qualidade | Uso |
|---|---|---|---|
| A2DP | Só saída | 44.1/48 kHz estéreo | Mídia |
| HFP | Bidirecional | **8 kHz mono** | Captura de microfone |

- Perfis **mutuamente exclusivos**. Com HFP ativo, a saída também cai para 8 kHz.
- Microfone usa **beamforming** que isola a voz do usuário — vantagem real em
  galpão ruidoso.
- **Ordem obrigatória:** configurar HFP → esperar a rota assentar → só então
  iniciar o stream de câmera. O inverso faz a rota falhar em silêncio.
- `setCommunicationDevice` exige API 31 → **minSdk = 31**.

### 2.2 Câmera

- Stream: `HIGH` 720×1280, `MEDIUM` 504×896, `LOW` 360×640. FPS: 2, 7, 15, 24, 30.
- Compressão por frame adapta-se à banda do Bluetooth Classic. **A imagem pode
  ser pior que a resolução nominal.** Resolução e FPS menores rendem qualidade
  maior por frame.
- `capturePhoto()` vem do sensor cheio, **não** do caminho de vídeo comprimido.

### 2.3 Sessão

- Uma sessão por dispositivo por vez.
- Usuário pausa fechando as hastes, tirando os óculos ou tocando nos óculos.
  **Tratar isso é obrigatório** — durante a demo alguém vai tirar os óculos.

---

## 3. Máquina de estados

Núcleo do app. Cada estado declara: o que falar, qual gramática aceitar, qual
perfil de endpoint usar, e se a câmera está ativa.

Implementação: `sealed interface` + `StateFlow`, processada por ator único (§5).

### 3.1 Tabela de estados

| Estado | Saída de áudio | Gramática | Perfil endpoint | Câmera |
|---|---|---|---|---|
| `Ocioso` | — | — | — | off |
| `Registrando` | — | — | — | off |
| `PreparandoSessao` | earcon pronto | — | — | off |
| `AguardandoOrdem` | — | COMANDOS | COMANDO_CURTO | off |
| `OrdemCarregada` | resumo da ordem | COMANDOS | COMANDO_CURTO | off |
| `NavegandoParaEndereco` | endereço turn-by-turn | COMANDOS | COMANDO_CURTO | **off** |
| `AguardandoCheckDigit` | "confirme a posição" | DIGITOS | DIGITOS | **off** |
| `EscaneandoProduto` | earcon escaneando | COMANDOS | COMANDO_CURTO | **on** 7fps |
| `DecodificandoProduto` | — | — | — | on → off |
| `VerificacaoAssistida` | "verificando" | COMANDOS | COMANDO_CURTO | off |
| `ValidandoContraDados` | — | — | — | off |
| `ConfirmandoQuantidade` | "colete N unidades" | DIGITOS | **DIGITOS** | off |
| `ReadbackQuantidade` | readback dígito a dígito | CONFIRMACAO | COMANDO_CURTO | off |
| `AlocandoCarrinho` | compartimento + progresso | COMANDOS | COMANDO_CURTO | off |
| `ItemConcluido` | earcon sucesso | — | — | off |
| `TratandoExcecao` | "descreva a ocorrência" | **livre** | TEXTO_LIVRE | off |
| `ConferenciaFinal` | progresso | COMANDOS | COMANDO_CURTO | on 7fps |
| `OrdemConcluida` | resumo | COMANDOS | COMANDO_CURTO | off |
| `SessaoPausada` | — | — | — | off |
| `Erro` | earcon erro + causa | COMANDOS | COMANDO_CURTO | off |

**Nota sobre `TratandoExcecao`:** é o único estado com gramática livre. O Vosk
roda sem restrição de vocabulário ali, o que em 8 kHz produz transcrição
ruidosa — e tudo bem. O LLM que estrutura o relato recebe também o contexto
(produto esperado, lote, tipos de exceção possíveis) e é robusto a erro de ASR
justamente por ter esse contexto para ancorar. Ver §6.4.

**A coluna Câmera é a estratégia de bateria** (§8), não um detalhe: desligada em
todos os estados exceto dois.

### 3.2 Fluxo principal

```
AguardandoOrdem
  └─> OrdemCarregada
        └─> NavegandoParaEndereco ──┐
              └─> AguardandoCheckDigit
                    ├─ dígito errado ─> NavegandoParaEndereco (repete)
                    └─> EscaneandoProduto
                          └─> DecodificandoProduto
                                ├─ falha local ─> VerificacaoAssistida
                                │                   ├─ sem rede ─> CheckDigit do produto
                                │                   └─> ValidandoContraDados
                                └─> ValidandoContraDados
                                      ├─ divergência ─> TratandoExcecao
                                      └─> ConfirmandoQuantidade
                                            └─> ReadbackQuantidade
                                                  ├─ "corrigir" ─> ConfirmandoQuantidade
                                                  └─> AlocandoCarrinho
                                                        └─> ItemConcluido
                                                              ├─ restam itens ─┘
                                                              └─> ConferenciaFinal
                                                                    └─> OrdemConcluida
```

### 3.3 Transições transversais

De qualquer estado operacional:

- `"parar"` / `"emergência"` → `SessaoPausada`
- `"repetir"` → repete a última fala sem mudar de estado
- `"avaria"` / `"ruptura"` / `"divergência"` → `TratandoExcecao`
- Evento de pausa do DAT (hastes/remoção/toque) → `SessaoPausada`
- Perda de conexão BT → `Erro`, com retomada no mesmo item

### 3.4 Invariantes

1. **Troca de gramática só na transição de estado**, nunca dentro do loop de
   áudio — recriar o `Recognizer` custa 10–30 ms.
2. **Nada é registrado sem readback confirmado** quando o valor diverge do
   esperado.
3. **Câmera só liga em `EscaneandoProduto` e `ConferenciaFinal`.**
4. Toda transição gera log auditável com timestamp, estado origem/destino e
   evento.

---

## 4. Modelo de concorrência

Decisão estrutural. Não seguir isso produz corrida de dados na segunda semana.

### 4.1 Regra dura

**O `Recognizer` do Vosk não é thread-safe. O `OrtSession` do Silero não suporta
`run()` concorrente.** Ambos vivem em **uma única thread** — a de áudio.

### 4.2 Thread de áudio

Lê 256 amostras a cada 32 ms e roda Silero (~1–2 ms) e Vosk (poucos ms) inline.
Cabe com folga, e inline evita latência de fila.

Ela **nunca** toca ML Kit, disco ou I/O de qualquer tipo.

**Armadilha específica:** `trocarGramatica()` recria o `Recognizer`. Chamar isso
da thread da máquina de estados é corrida garantida. A troca entra como mensagem
na fila da thread de áudio, aplicada entre um frame e outro.

### 4.3 A máquina de estados é um ator

Um `Channel` único recebe **todos** os eventos — VAD, ASR final, frame de
câmera, resultado de decode, evento de lifecycle do DAT — e uma corrotina única
processa sequencialmente.

Confinamento por thread única elimina a maior parte dos bugs de concorrência
antes de existirem, e torna o log de transições trivialmente ordenado — que é
exatamente o que a auditoria de rastreabilidade precisa.

| Componente | Contexto |
|---|---|
| Captura + VAD + ASR | Thread dedicada de áudio |
| Máquina de estados | Corrotina única consumindo `Channel` |
| Frames de câmera | `Dispatchers.Default` |
| ML Kit / zxing | Executor próprio do ML Kit |
| TTS | `AudioTrack` próprio |
| Repositório de dados | `Dispatchers.Default` (em memória, sem I/O) |

Ninguém escreve estado diretamente. Todos enviam evento ao canal.

### 4.4 Liberação determinística de imagem

O descarte do frame não pode depender do garbage collector — a afirmação de
privacidade da §9 exige ponto de liberação explícito.

Todo ciclo de captura roda dentro de `try/finally` (ou `use {}`), com a deleção
do arquivo de cache e a liberação do buffer no bloco de saída. **O descarte
acontece mesmo quando a cascata lança exceção.** TTL de segurança varre o cache
na entrada de `EscaneandoProduto`, cobrindo o caso de crash.

**Ponto de descarte:** imediatamente após o recorte da ROI (§6.3), **antes** de
qualquer passo da cascata. O quadro completo tem a vida mais curta que a
implementação permite: existe entre a captura e o crop, e nada além disso.

### 4.5 Log estruturado — requisito de arquitetura, não observabilidade

O plano de calibração (§10) pressupõe dados. **Se o log entrar depois, todo o
trabalho de agosto é perdido como fonte de medição.** Ele precisa existir desde
o commit inicial.

Registro em JSON, por evento:

| Evento | Campos |
|---|---|
| Transição de estado | timestamp, origem, destino, gatilho |
| Resultado de ASR | timestamp, gramática ativa, **as 3 hipóteses n-best com scores**, hipótese escolhida, valor final, latência |
| Tentativa de decodificação | timestamp, passo da cascata, método, tempo, resultado, distância estimada |
| Captura | timestamp, motivo do disparo, sinais do gatilho (§6.2) |
| Descarte de imagem | timestamp, confirmação de deleção |

Guardar as hipóteses n-best é o que permite reavaliar os pesos de plausibilidade
(§10.5) **sem regravar o corpus** — você reprocessa o log com pesos novos. Sem
isso, cada ajuste de parâmetro exige nova sessão de gravação.

O log de descarte de imagem é a evidência auditável da afirmação de privacidade
da §9.2.

---

## 5. Pipeline de áudio

```
HFP 8kHz ─> AudioRecord (VOICE_COMMUNICATION + AEC)
         ─> ring buffer
         ─> Silero VAD (janela 256 amostras)
              ├─ pré-roll 300ms
              └─ endpointing por perfil do estado
         ─> Vosk (gramática do estado, n-best 3)
         ─> parse pt-BR (dígitos, "meia"=6)
         ─> reranking contra a expectativa do mock
         ─> evento no canal
```

### 5.1 Perfis de endpoint

| Perfil | Silêncio final | Uso |
|---|---|---|
| `COMANDO_CURTO` | 280 ms | confirmar, cancelar, repetir |
| `DIGITOS` | 700 ms | quantidade, check digit |
| `TEXTO_LIVRE` | 900 ms | relato de exceção |

O valor de 700 ms corrige o bug `572 → 570`: o endpointer padrão fechava a
elocução na micropausa antes do último dígito.

### 5.2 Abstração da fonte de áudio

**O Mock Device Kit simula a câmera. Não há equivalente para o caminho de áudio
HFP.** Sem abstração, o pipeline de voz — o de maior esforço no projeto — só é
exercitado em 18/09, no dia em que não sobra tempo.

```kotlin
interface FonteAudio {
    val sampleRate: Int
    fun fluxo(tamanhoJanela: Int): Flow<FloatArray>
}
```

Duas implementações:

| Implementação | Uso |
|---|---|
| `AudioHfpOculos` | Produção. Captura via HFP a 8 kHz (§2.1) |
| `AudioMicrofoneSimulado` | Desenvolvimento. Microfone do celular a 16 kHz + band-pass 300–3400 Hz + downsample para 8 kHz |

A implementação simulada usa exatamente a mesma degradação de canal descrita em
§10.1, o que significa que a calibração feita em agosto transfere para o
hardware real com ajuste mínimo.

Trocar entre as duas é injeção de dependência — mesma regra do
`PickingRepository` (§11.1).

### 5.3 Carregamento do modelo

`Model(path)` do Vosk leva alguns segundos e é I/O de disco. **Carregar na
inicialização do app**, não ao criar a sessão — senão a primeira interação de
voz trava na frente do usuário. Manter em memória pela vida do processo; só o
`Recognizer` é recriado na troca de gramática (§4.2).

### 5.4 Saída

Inventário de ~150 fragmentos pré-renderizados com Piper (`pt_BR-faber-medium`),
processados offline para 8 kHz: band-pass 300–3400 Hz, compressão de dinâmica,
normalização de loudness, velocidade 1,15–1,25x.

**Convenção "meia" = 6 na entrada e na saída.** O jargão telefônico brasileiro
criou isso porque "seis" e "três" se confundem em banda estreita — exatamente o
canal que temos.

Earcons (< 200 ms) para sucesso e erro. Fila com preempção: alerta de produto
incorreto corta fala rotineira.

---

## 6. Pipeline de visão

### 6.1 Ergonomia de captura

**Postura definida:** o operador segura a caixa a aproximadamente **20 cm** do
rosto, com o código voltado para a câmera.

**Ressalva a validar antes de fixar esse número.** A câmera dos óculos é
grande-angular de foco fixo, e câmeras assim costumam ter distância mínima de
foco entre 30 e 50 cm. A 20 cm ganha-se tamanho angular do código e perde-se
nitidez — e borrão de foco derruba decodificação de DataMatrix mais rápido que
tamanho pequeno.

**Varredura obrigatória:** mesma caixa a 15, 20, 25, 30 e 40 cm, medindo taxa de
decodificação. É o primeiro teste a rodar no onboarding do dia 18 (§13.3), antes
de qualquer decisão de ajuste. A distância vira parâmetro de configuração, não
constante no código.

Benefício colateral da postura próxima e centrada: lente grande-angular tem
distorção de barril acentuada nas bordas, que prejudica decodificação. Com a
caixa no centro do quadro, opera-se na região de menor distorção.

### 6.2 Gatilho de captura

Ordem correta de tentativa — **decodificar o stream vem primeiro**:

1. **Tentar decodificar os frames do stream.** Etiqueta de expedição com Code 128
   grande decodifica bem a 504×896. Se decodificou, acabou: sem foto, sem
   latência extra, sem gasto de bateria. A foto é escalonamento, não passo
   obrigatório.
2. Se o stream não decodifica, disparar `capturePhoto()` na conjunção de três
   sinais:

| Sinal | Método | Custo |
|---|---|---|
| Densidade de borda no ROI central | Variância do Laplaciano subamostrada | ~1 ms |
| Estabilidade temporal | Diff médio entre frames < limiar por 3 frames (≈430 ms a 7fps) | ~1 ms |
| Ausência de borrão | Mesma variância do Laplaciano, segundo limiar | grátis |

Regras operacionais:

- **Cooldown de 1,5 s** após captura fracassada — sem isso, dispara em rajada
- **Máximo 3 tentativas** antes de escalar para check digit do produto
- **Gate de intenção é implícito:** só existe no estado `EscaneandoProduto`, que
  só se alcança após check digit da posição confirmado
- Após 8 s sem disparo: "aponte para o código do produto"

O limiar de estabilidade precisa ser calibrado **com pessoas usando os óculos**,
não com o celular apoiado na mesa. Cabeça humana nunca fica parada.

### 6.3 Cascata de decodificação

| Passo | Ação |
|---|---|
| 1 | ML Kit **bundled** nos frames do stream |
| 2 | `capturePhoto()` → ML Kit no frame cru |
| 3 | ML Kit em variantes: crop ROI, upscale 2x, cinza, threshold adaptativo |
| 4 | zxing-cpp nas mesmas variantes |
| 5 | ML Kit Text Recognition v2 → fuzzy match contra o lote esperado |
| 6 | **Verificação assistida por VLM** (§6.4) — único passo que usa rede |
| 7 | Check digit do produto por voz |

**Ordem obrigatória: capturar → recortar 60% centrais → descartar o quadro
completo → rodar a cascata sobre o recorte.**

O descarte acontece antes do passo 2, não depois da cascata. A cascata inteira,
incluindo as variantes de pré-processamento (~50–100 ms cada) e a transmissão
para VLM, opera exclusivamente sobre a região retida.

**Falha total → recaptura**, com cooldown de 1,5 s. Custo aceito, e
frequentemente melhor que reprocessar: quando o quadro falhou por borrão de
movimento, ângulo ruim ou código parcialmente fora de campo, a informação não
está no arquivo e nenhuma variante de threshold a recupera. Uma nova captura
resolve, porque o operador reposiciona naturalmente ao ouvir a falha.

**Consequência do descarte precoce:** não há como tentar outra região de recorte
sobre o mesmo quadro. Por isso 60% e não 40% — recorte apertado com descarte
precoce é a combinação ruim.

Recorte definido: **60% centrais**, constante de configuração. Com a caixa a
~20 cm o código ocupa boa fração do quadro; 60% dá margem para desalinhamento do
operador sem reter cena desnecessária. Ajustável em uma linha se a varredura
(§10.2) indicar outro valor.

**Use a distribuição bundled** (`com.google.mlkit:barcode-scanning`), não a via
Play Services. A unbundled baixa o modelo no primeiro uso e quebra o requisito
offline — falhando silenciosamente, num celular recebido há duas horas.

### 6.4 Verificação assistida por VLM

Único ponto do sistema que usa rede, e apenas quando toda a cascata local
falhou. Latência de 2–3 s é aceitável porque é caminho de exceção.

**Envio:** apenas o **recorte da ROI**, nunca a cena completa. EXIF removido
(geolocalização e timestamp). Junto vão os valores esperados vindos do mock.

**Prompt:** verificação, não extração — "esta imagem mostra GTIN X, lote Y,
validade Z?" Resposta em JSON: `{confirma, campos_lidos, confianca}`.

**Modelo:** Llama. Ressalva verificada — o model card do Llama 3.2 Vision lista
apenas inglês para tarefas com imagem; Llama 4 é nativamente multimodal. Para
alfanumérico o idioma importa pouco: prompt em inglês, saída JSON.

**Estruturação de relato livre** usa o mesmo caminho de rede, no estado
`TratandoExcecao`: transcrição ruidosa + contexto → JSON estruturado
(`{tipo, divergencia_lote, qtd_aproveitavel, requer_supervisor}`).

**Degradação obrigatória.** Sem rede, o passo é pulado:
- Verificação visual indisponível → check digit do produto por voz (§7.2)
- Relato livre indisponível → menu estruturado por voz

Se a rede cair, o operador continua trabalhando com o sistema degradado. Em
operação farmacêutica isso é continuidade de negócio, não detalhe — e é a
resposta ao critério de considerações éticas.

**Para a demo:** ter resposta em cache. Rede convidado na sede da Meta em dia de
evento é aposta.

### 6.5 Parsing GS1

AIs relevantes: `(01)` GTIN, `(10)` lote, `(17)` validade AAMMDD, `(21)` série.

**Armadilha:** FNC1 chega como `\u001D` em alguns casos e some em outros. O
parser trata AIs de tamanho fixo por tabela **e** delimitados por GS.

### 6.6 Assimetria explorada

Não estamos lendo um lote desconhecido — estamos **verificando** contra um valor
já conhecido. Isso vale para a cascata inteira e torna o passo 5 viável onde
extração cega falharia.

---

## 7. Check digit

### 7.1 Especificação

**Dois dígitos**, impressos na etiqueta da posição. Cem combinações discriminam
bem contra posições visualmente próximas. Três é o padrão industrial em armazém
grande; dois basta para o protótipo e é mais rápido de falar.

**Não pode ser derivável do endereço.** Se for função de rua/prédio/nível/posição,
o operador aprende a fórmula e confirma sem chegar lá — a verificação vira
teatro. Tem que ser valor arbitrário armazenado por posição.

**Validação exata, nunca fuzzy.** Todo o resto do sistema usa correspondência
tolerante; aqui não. Fuzzy num dígito de verificação destrói a razão de ele
existir.

**Em divergência, nunca revele o valor correto.** Fala só "posição incorreta" e
repete o endereço. Três erros escalam para exceção — provavelmente o operador se
perdeu ou a etiqueta está danificada, e ambos precisam de registro.

### 7.2 Check digit de produto

Fallback final da cascata de visão (§6.3, passo 7). Mesmas regras. Usa os dois
últimos dígitos do lote impresso na embalagem.

---

## 8. Eficiência de bateria (checkpoint obrigatório)

| Medida | Mecanismo |
|---|---|
| Câmera desligada na maior parte do ciclo | Só `EscaneandoProduto` e `ConferenciaFinal` (§3.1) |
| Stream em FPS baixo | `MEDIUM` @ 7fps — também melhora qualidade por frame |
| Decode do stream antes da foto | Evita `capturePhoto()` quando o código é grande |
| HFP aberto uma vez por ordem | Evita assentamento de rota repetido por item |
| TTS sem inferência | Inventário pré-renderizado, tocado da memória |
| Processamento no celular | ASR, VAD, decode e TTS no telefone, não nos óculos |
| Rede só em exceção | Nenhuma chamada no laço normal; VLM apenas quando a cascata local falha |

**Metodologia:** percentual de bateria dos óculos por ordem completa, medido em
3 ordens consecutivas. Declarar a metodologia junto do número — estimativa com
método declarado vale mais que número redondo sem origem.

---

## 9. Privacidade e dados

> **Substitui integralmente a seção correspondente da página pública**, que
> contém afirmações insustentáveis. Considerações éticas valem 20 pontos no
> Segundo Filtro, e o texto atual pode subtrair em vez de somar.

### 9.1 O que controlamos e o que não

Quatro etapas que a página pública tratava como uma só:

| Etapa | Sob nosso controle |
|---|---|
| Captura | **Não** — é o sensor, e a lente é grande-angular |
| Processamento | Sim |
| Retenção | Sim |
| Transmissão | Sim |

Três de quatro é bastante, e sustenta afirmações fortes. A quarta não se
sustenta e não deve ser afirmada.

### 9.2 Redações verificáveis

**Sobre armazenamento e retenção.** A decodificação exige que a imagem exista em
memória durante o processamento — não é otimização, é necessidade técnica. O
quadro completo é recortado imediatamente após a captura e **descartado antes de
qualquer processamento**; o sistema retém somente a região do código. O recorte
permanece em cache volátil pelo tempo estrito da operação e é deletado por
rotina explícita em código (§4.4), com TTL de segurança. Não há persistência,
galeria, backup ou sincronização.

**Sobre o escopo da captura.** A captura é pontual e sob demanda, nunca
contínua — a câmera só é ativada nos estados de escaneamento e conferência
(§3.1). O processamento extrai exclusivamente códigos de barras e texto de
rótulo; nenhum outro conteúdo da imagem é analisado, indexado ou retido. Não há
reconhecimento facial ou de pessoas em nenhuma etapa.

**Sobre transmissão.** Quando a cascata local falha e a verificação assistida é
acionada, sai do dispositivo apenas o **recorte da região do código**, com EXIF
removido. A cena completa não é transmitida porque, nesse ponto, já não existe.

**Sobre dados operacionais.** Ordem, produto, endereço e operador são mockados e
não trafegam em nenhuma hipótese.

> **Nota de redação.** Não afirmar "captura focada apenas nos códigos". A lente
> capta o quadro inteiro e não há configuração que evite isso. É a frase que
> provoca a pergunta sem resposta — e uma afirmação frágil contamina a
> credibilidade das outras, que são verdadeiras.

### 9.3 Demais compromissos

**Laço normal sem transmissão.** Navegação, check digit, decodificação e
confirmação de quantidade rodam integralmente no dispositivo — demonstrável ao
vivo em modo avião.

**Sinalização por earcon.** O operador sabe quando a câmera está ativa.

**Relato de exceção.** Sai a transcrição em texto, nunca o áudio.

**Sinalização a terceiros.** O LED de captura dos óculos é hardware e sempre
ativo. Complementar com sinalização no procedimento operacional.

**Áudio.** Processado localmente, não armazenado, não transmitido.

**LGPD.** Base legal: cumprimento de obrigação regulatória (rastreabilidade
ANVISA/MAPA/SNCM) e legítimo interesse para controle de qualidade. Dados do
operador não são coletados. Métricas de produtividade agregadas e anonimizadas —
**sem ranking individual**.

**Autonomia.** A IA é apoio; a decisão final é humana. O operador pode rejeitar
qualquer sugestão, e a rejeição é registrada.

---

## 10. Plano de calibração

### 10.1 Simulação do canal (não precisa dos óculos)

> Pré-requisito: o log estruturado da §4.5 precisa estar ativo antes da primeira
> gravação. Sem as hipóteses n-best registradas, cada reajuste de peso exige
> regravar o corpus.


Grave a 16 kHz, **guarde o original**, e gere a versão degradada com band-pass
300–3400 Hz + downsample para 8 kHz. O delta mede o custo do canal HFP. Mesmo
princípio para visão: fotos reduzidas a 720×1280 com JPEG agressivo dão o limite
inferior; a original dá o superior.

### 10.2 Varredura de distância e recorte

Antes do corpus de voz, resolver os dois parâmetros de visão:

| Parâmetro | Varredura | Métrica | Prioridade |
|---|---|---|---|
| Distância caixa–câmera | 15, 20, 25, 30, 40 cm | Taxa de decodificação | **Bloqueante** |
| Tamanho do recorte | 50%, 60%, 70% centrais | Taxa de decodificação | Ajuste fino |

Os dois interagem: recorte apertado exige alinhamento melhor, que fica mais
difícil quanto mais perto. A distância é bloqueante porque pode invalidar a
ergonomia definida; o recorte parte de 60% e é ajustável em uma linha.

**Sinal de diagnóstico:** quando a decodificação falha, verificar na tela
espelho (§12) se o recorte cortou o código. É o indicador direto de que 60% está
apertado para a distância escolhida.

### 10.3 Corpus

- ~100 elocuções de 3–4 dígitos (~350 tokens)
- **Diversidade de locutor importa mais que volume.** Não gravar só com a equipe
  de dev. Incluir vozes femininas, sotaques distintos, operadores reais.
- Ruído do galpão gravado à parte, para mistura em SNR controlado.

### 10.4 Métricas (não medir WER)

| Métrica | Por quê |
|---|---|
| **Taxa de falso aceite** | Único indicador de dano real. É o que se otimiza |
| **Taxa de truncamento** | Confirma que a correção de endpointing funcionou |
| **Taxa de readback** | Alta demais → operador confirma no automático, anula a proteção |
| **Latência fim-de-fala → primeiro áudio** | O que o operador sente. Alvo < 700 ms |
| **Tempo de ciclo por item** | A métrica da hipótese (§1.1). Comparar com coletor RF |

Falso aceite e taxa de readback movem-se em direções opostas. **Plote a curva** —
é slide de pitch e demonstra domínio do trade-off.

### 10.5 Gatilhos de decisão

**X = 0,5% de falso aceite.** Enunciado invertido em relação à v1.0: readback é
**obrigatório por padrão**, e X é o limiar que autoriza *pular* o readback quando
o valor bate com o esperado. O modo seguro é o default; a otimização é que
precisa ser justificada por dado.

**Y = 85% de decodificação em até 3 segundos.** Justificativa: a proposta promete
+40% de produtividade contra o coletor RF. Abaixo de 85% na primeira tentativa,
o tempo médio com retentativas ultrapassa o coletor e a proposta de valor
inverte. Abaixo de Y, o peso do passo VLM (§6.4) na cascata aumenta e ele deixa
de ser exceção rara para virar caminho frequente — o que muda o custo de API e o
perfil de latência, e precisa ser reavaliado.

*Ambos os valores carecem de validação do Thiago contra a operação real.*

---

## 11. Dados mockados

### 11.1 Interface

Repositório em memória, com assinatura **idêntica à que uma implementação HTTP
teria**. Trocar `MockPickingRepository` por `HttpPickingRepository` quando a
integração real acontecer deve ser uma linha de injeção de dependência.

```kotlin
interface PickingRepository {
    suspend fun operadorAtual(): Operador
    suspend fun ordensDisponiveis(): List<ResumoOrdem>
    suspend fun ordem(id: String): Ordem
    suspend fun registrarColeta(ordemId: String, linha: Int, coleta: Coleta)
    suspend fun registrarExcecao(ordemId: String, excecao: Excecao)
    suspend fun fecharConferencia(ordemId: String): Conferencia
}
```

### 11.2 Modelo

```kotlin
data class Linha(
    val sku: String,
    val descricao: String,
    val endereco: Endereco,        // rua, predio, nivel, posicao
    val checkDigitPosicao: String, // 2 dígitos, arbitrário
    val gtin: String,
    val lote: String,
    val serie: String,
    val validade: LocalDate,
    val quantidade: Int,
    val saldoEndereco: Int,        // alimenta maximoPlausivel do reranking
    val compartimento: String,
)

data class Coleta(
    val quantidade: Int,
    val lote: String,
    val serie: String,
    val timestamp: Instant,
    val metodoValidacao: MetodoValidacao,
    val confianca: Float,
    val readbackConfirmado: Boolean,
)

enum class MetodoValidacao {
    DATAMATRIX_STREAM, DATAMATRIX_FOTO, CODE128,
    OCR_FUZZY, VLM_ASSISTIDO, CHECK_DIGIT_VOZ, MANUAL
}
```

### 11.3 Por que `metodoValidacao` é o campo mais importante

É a resposta para "como vocês provam a rastreabilidade". O sistema não registra
apenas que o item foi conferido — registra **por qual caminho, com que confiança,
e se houve confirmação humana explícita**.

Um item validado por DataMatrix e um validado por OCR com fuzzy match não têm o
mesmo peso probatório, e o sistema não finge que têm. Isso responde tanto em
auditoria ANVISA quanto na pergunta da banca sobre considerações éticas.

### 11.4 Realismo do dataset

Os dados são fictícios, mas devem ser **estruturalmente reais**: SKUs, GTINs e
formatos de lote extraídos de produtos que a AGV realmente movimenta, e as caixas
usadas na demo devem ser caixas reais com códigos reais. O mock substitui o
sistema de origem, não a física do problema.

---

## 12. Telas

Os óculos não têm display. O celular é painel de operação e depuração.

| Tela | Função |
|---|---|
| Pareamento | `Wearables.startRegistration` (deeplink Meta AI, tratar retorno). **Sem login/token** — operador é selecionado de lista mockada |
| Ordem de separação | Lista mockada, seleção, `createSession` |
| **Operação (espelho)** | Frame ao vivo **com a moldura do recorte de 60% desenhada**, transcrição parcial, estado atual, último código, fala em curso. **+ modo calibração** |
| Divergência | Única tela com toque, para quando a voz falhar |

**Mudança da v1.0:** a tela de login/token some. Sem API não há token a obter.

A tela espelho é indispensável: sem ela não há depuração no hackathon (ninguém
enxerga pelos óculos); projetada no pitch, é o que torna a demo legível; e em
produção é a visão do supervisor.

---

## 13. Plano de desenvolvimento

### 13.1 Marcos até 18/09

**Marco 1 — fatia vertical fina.** O primeiro marco não é "pipeline de áudio
pronto". É **um item separado de ponta a ponta**, com cada componente no nível
mais tosco aceitável: um item mockado, TTS falando o endereço, check digit
reconhecido, código lido, quantidade confirmada, item encerrado.

Isso expõe cedo os problemas que só aparecem na junção — ordem de HFP e câmera
(§2.1), troca de gramática entre estados (§4.2), ciclo de vida da sessão
(§2.3) — enquanto ainda há semanas para resolvê-los. Aprofundar cada componente
vem depois.

**Marco 2 — profundidade por componente.** Cascata de visão completa (§6.3),
gramáticas e perfis de endpoint (§5.1), inventário TTS (§5.4), tratamento de
exceção.

**Marco 3 — instrumento de calibração.** Modo calibração da tela espelho (§12)
rodando o corpus e despejando métricas. Precisa estar pronto **antes** de 18/09,
porque é o que se usa às 9h30.

**Marco 4 — ensaio.** Ciclo completo cronometrado, com as caixas físicas e as
etiquetas de check digit reais. Define se a demo de dois atos (§16, item 7) é
viável.

Todo o desenvolvimento roda contra `AudioMicrofoneSimulado` (§5.2) e Mock Device
Kit. Nenhum marco depende dos óculos.

### 13.2 Divisão proposta

| Pessoa | Frente |
|---|---|
| Matheus Wallace | DAT (sessão, permissões, lifecycle) + pipeline de áudio |
| Paulo Henrique | Pipeline de visão + gatilho de captura + dataset mockado |
| Thiago Serra | Dados realistas, calibração, checkpoints e pitch |

**Mudança da v1.0:** Paulo sai de "backend mock local" — que deixou de existir —
e assume o pipeline de visão inteiro, que é a frente de maior risco.

**Caminho crítico:** DAT → HFP → câmera. Se a sessão não sobe, nada mais importa.
Matheus não pega nenhuma tarefa secundária antes disso estar verde.

### 13.3 Linha do tempo (18/09)

| Hora | Ação |
|---|---|
| 09h30 | Onboarding: pareamento, device selector, **varredura de distância (§10.2)** e corpus de voz |
| ~10h00 | Números reais de decode e ASR em mãos, antes de escrever integração |
| 11h00 | Início do desenvolvimento — ajuste, não integração |
| 15h00 | Checkpoint 1: IA, câmera/microfone, output por áudio |
| 16h00 | Checkpoint 2: privacidade e bateria |
| 17h30 | Trava. Sem exceção |

O modo calibração da tela espelho roda o corpus e despeja as métricas. Chegar
com o instrumento de medição pronto transforma o dia de "integrar e torcer" em
"validar e ajustar".

**A troca do device selector e da `FonteAudio` deve ser a única mudança de
código necessária pela manhã.** Se algo além disso precisar mudar, o Marco 1
não estava realmente completo.

---

## 14. Pré-requisitos com latência de aprovação

**Começar hoje** — nada é difícil, tudo tem tempo de espera:

- [ ] Conta no Wearables Developer Center (deslogar de developers.meta.com antes)
- [ ] Organização criada
- [ ] App registrado → `APPLICATION_ID` + `CLIENT_TOKEN`
- [ ] PAT clássico do GitHub com escopo `read:packages`
- [ ] **Todos os membros do release channel com conta Meta já criada**
- [ ] Firmware dos óculos ≥ v125
- [ ] Modelo Vosk small pt-BR empacotado em `assets/`
- [ ] Inventário TTS gerado e processado para 8 kHz
- [ ] Confirmar template da Entrega Final com a organização
- [ ] Dataset mockado estruturalmente realista + caixas físicas para a demo
- [ ] **Etiquetas de check digit impressas e coladas** nas posições da demo —
      valores arbitrários, não deriváveis do endereço (§7.1)
- [ ] Caixas de teste com códigos em estados variados (íntegro, gasto, amassado)

---

## 15. Riscos

| Risco | Prob. | Impacto | Mitigação |
|---|---|---|---|
| DataMatrix não decodifica em 720p | **Alta** | Alto | Cascata §6.3; gatilho Y §10.5; check digit de produto |
| Rota HFP não assenta / falha silenciosa | Média | Alto | Ordem obrigatória §2.1; verificar rota antes de seguir |
| ASR degradado em 8 kHz | Média | Médio | Gramática fechada + n-best + reranking |
| Corrida de dados entre threads | Média | Alto | Modelo de ator §4; confinamento em thread única |
| Sessão pausa durante a demo | Média | Médio | `SessaoPausada` com retomada no mesmo item |
| Celular sem pacotes offline | Baixa | Alto | Vosk e ML Kit **bundled**; nada baixado em runtime |
| Demo parecer roteirizada | Média | Médio | §11.4 — caixas e códigos reais; falha de leitura real |
| Rede indisponível no dia | **Alta** | Baixo | Laço crítico offline; degradação §6.4; cache para a demo |
| **20 cm abaixo da distância mínima de foco** | **Alta** | Alto | Varredura §10.2; distância é parâmetro, não constante |
| Recorte de 60% cortando o código | Média | Médio | Descarte precoce impede recorte alternativo no mesmo quadro; falha custa recaptura (1,5 s). Diagnóstico visual na tela espelho §12 |
| Tempo insuficiente no dia | **Alta** | Alto | Chegar com app completo em Mock Device Kit |

**Nota:** rede indisponível tem impacto **baixo** por construção — afeta apenas
o passo de exceção, e a degradação está especificada.

---

## 16. Pendências abertas

1. Confirmar divisão de trabalho (§13.2)
2. Validar os limiares X e Y contra a operação real (§10.5)
3. Confirmar template da Entrega Final
4. Reescrever a seção de privacidade da página pública com o conteúdo da §9
5. Remover a menção a otimização de rota por IA da página pública
6. Ajustar a menção a "Agente de IA Contextual" da página pública — o agente
   existe, mas atua em exceção, não no laço principal
7. Decidir se o pitch demonstra modo avião no laço principal e reativa a rede
   para exibir a escalada por VLM (§6.4) — é a demo mais forte, mas exige ensaio
