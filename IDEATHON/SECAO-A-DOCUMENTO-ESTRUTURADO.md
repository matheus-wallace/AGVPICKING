# Seção A — Documento estruturado

## Identificação da proposta

**Projeto:** AGV Pick Voice  
**Equipe:** AGVTronic  
**Categoria:** Produtividade / logística assistida por wearables

O AGV Pick Voice é um protótipo Android para separação de pedidos em operações logísticas reguladas. Ele usa os óculos Meta como interface de sensores e o smartphone como unidade de processamento: o operador recebe instruções por áudio, responde por voz e confirma o produto pela câmera, mantendo as mãos livres para movimentar caixas.

## 1. Problema

Operações 3PL que movimentam produtos de saúde humana e animal precisam conciliar produtividade com rastreabilidade de lote, série e validade. No processo convencional, o operador alterna entre caixa, coletor RF e tela; isso reduz a disponibilidade das mãos e favorece erros em embalagens parecidas. A conferência posterior diminui o risco, mas adiciona tempo e não impede todas as divergências.

O problema central é validar a coleta certa, no local certo e na quantidade certa sem transformar o operador em digitador de um coletor. O risco é especialmente relevante em itens regulados por ANVISA, MAPA e SNCM, nos quais uma troca pode comprometer a rastreabilidade e gerar retrabalho.

### Hipótese validada pelo protótipo

Um operador consegue completar um ciclo de picking mãos livres, orientado por voz e validado por visão, com dados operacionais simulados e sensores reais. A validade da demonstração não depende de integrar um WMS: depende de o microfone reconhecer comandos, a câmera ler um código físico e o aplicativo conduzir corretamente os caminhos de sucesso e exceção.

## 2. Usuário-alvo

**Usuário principal:** operador de separação em armazém 3PL, farmacêutico ou de saúde animal, que percorre endereços, manipula caixas e precisa conferir itens regulados.

**Contexto de uso:** ambiente potencialmente ruidoso, com produtos semelhantes, conectividade variável e necessidade de atenção ao entorno. O operador usa os óculos Meta pareados a um smartphone Android; os dados da ordem são carregados de um repositório local de demonstração.

**Necessidades atendidas:**

- Receber endereço, item e quantidade sem olhar para um coletor.
- Confirmar posição e quantidade por voz, com leitura de volta para evitar confirmação cega.
- Validar o código físico do produto antes de concluir a coleta.
- Relatar avaria, ruptura ou divergência sem sair do fluxo.
- Pausar a operação com segurança quando os óculos são removidos, fechados ou a conexão é perdida.

## 3. Walkthrough de uso

### Fluxo principal

1. O operador registra o app e inicia uma sessão com os óculos Meta. O protótipo disponibiliza uma ordem local simulada.
2. Ao iniciar a ordem, o sistema fala o próximo endereço e o item esperado. A câmera permanece desligada durante o deslocamento.
3. Na posição, o operador confirma o check digit por voz. Se estiver correto, o app entra no estado de escaneamento.
4. A câmera é ligada sob demanda. O stream é decodificado, recortado para a região central e analisado localmente por uma cascata de leitura de códigos.
5. O código lido é comparado literalmente ao código esperado para a linha atual. Em caso de correspondência, o app informa o sucesso por áudio.
6. O operador fala a quantidade coletada. O sistema repete os dígitos e só aceita o avanço após a confirmação explícita.
7. O app informa o compartimento do carrinho e o progresso. O ciclo reinicia para o próximo item.
8. Ao final, ocorre a conferência final e o resumo da ordem.

### Fluxos de exceção

- **Check digit incorreto:** o sistema repete o endereço e não liga a câmera.
- **Leitura local sem resultado ou divergente:** o item não é aceito automaticamente. O fluxo segue para verificação assistida; sem rede, há fallback por check digit falado do produto.
- **Quantidade incorreta:** o operador diz “corrigir”; o app retorna à confirmação de quantidade e repete o readback.
- **Avaria, ruptura ou divergência:** um comando transversal abre o relato de exceção. A gramática fica livre apenas neste estado; o evento é registrado no repositório local do protótipo.
- **Pausa, remoção dos óculos ou perda de Bluetooth:** câmera e processamento associado são desligados; a sessão entra em pausa/erro e exige retomada segura.

## 4. Decisões técnicas

### Plataforma e arquitetura

- **Android nativo em Kotlin + Meta Wearables DAT.** A sessão DAT é criada antes de anexar a capacidade de câmera, seguindo o ciclo de vida do SDK. Foi escolhido Kotlin nativo para acesso direto aos fluxos, permissões e estados do DAT.
- **Máquina de estados com ator único.** `PickingState` e `PickingEvent` são tipos selados; `PickingActor` é a única origem de transições. Isso evita que câmera, ASR e interface alterem a operação concorrentemente.
- **Repositório local de dados.** Ordem, produto, endereço, lote e usuário são simulados em memória. Uma interface preserva a possibilidade de integração futura, mas o protótipo não consulta, valida nem grava no WMS.

### Voz e áudio

- **ASR local e orientado ao estado.** O motor principal é o **Picovoice Rhino**, um motor fala-para-intenção de vocabulário fechado. Ele usa contextos pt-BR pré-carregados para comandos/check digits e para quantidades de 1 a 9.999; o estado atual escolhe o contexto sem recriar engines durante a operação. O **Vosk** permanece como alternativa selecionável para reconhecimento fala-para-texto com gramática fechada. Sherpa-onnx também fica disponível para bancada, mas não é o motor padrão. Restringir a linguagem reduz falsos positivos no galpão; texto livre fica restrito ao relato de exceção.
- **Central de configurações de bancada.** Uma tela/repositório de configurações persistidas reúne a escolha do motor ASR, uso de óculos simulados, origem de microfone, qualidade do vídeo, FPS, rotação e ativação de captura por foto. A configuração é lida antes de criar DAT, câmera e ASR; mudanças exigem reiniciar o app, pois esses componentes não são trocados com segurança em voo. Isso permite calibração rápida no dispositivo sem alterar código.
- **TTS Android em pt-BR.** `TextToSpeech` usa a voz padrão disponível no aparelho e aplica fallback por qualidade/latência. Enquanto o sistema fala, o ASR não aceita resultados, evitando que o reconhecimento interprete a própria instrução.
- **Alternativa descartada: Piper como TTS ativo.** Foi descartado para o protótipo por não oferecer uma integração Android pronta compatível com o cronograma e a licença/runtime selecionados.

### Visão computacional

- **ML Kit bundled como primeira etapa da cascata.** O leitor de código é empacotado no APK e não depende de download em tempo de execução. A leitura usa dados físicos: não há código “reconhecido” a partir do mock.
- **Stream de vídeo comprimido, 7 FPS, com descarte de frames.** A resolução/FPS são configuráveis, mas a política privilegia 7 FPS durante o escaneamento. Um frame é descartado se já houver uma análise em andamento, evitando fila e pressão de memória.
- **Recorte e foto de fallback.** O quadro é recortado antes do processamento; quando necessário, o fluxo pode recorrer à foto do sensor cheio. A imagem mantida é apenas a necessária para a leitura e é removida também em falhas.
- **Alternativa descartada: visão em nuvem no caminho crítico.** A leitura primária é local para preservar latência, continuidade offline e privacidade. A rede é reservada para verificação assistida de exceção.

## 5. Concorrentes e diferenciação

| Categoria | Exemplos | Limitação no cenário proposto | Diferencial do AGV Pick Voice |
|---|---|---|---|
| Coletor RF tradicional | Coletores industriais / RF guns | Exige alternar entre dispositivo e caixa; interação predominantemente manual | Interface por voz e mãos livres |
| Voice picking convencional | Soluções de voice picking corporativas | Normalmente guia e confirma por voz, sem validação visual no ponto da coleta | Leitura de código físico pela câmera antes da confirmação |
| Dispositivos rugged com scanner | Handhelds e wearables industriais com scanner | Podem adicionar peso, custo e dependência de hardware especializado | Usa óculos Meta e smartphone já presentes no ecossistema DAT |

O diferencial não é “usar IA” isoladamente. É fechar o laço operacional: instrução por áudio, confirmação verbal, leitura física do código e uma máquina de estados que impede avanços sem evidência suficiente.

## 6. Cinco pilares técnicos obrigatórios

### 6.1 Uso de IA

O projeto usa IA local em duas tarefas verificáveis: reconhecimento de intenção por voz com Picovoice Rhino e leitura de códigos com ML Kit. Vosk é uma alternativa configurável de ASR para bancada. A IA não aprova a coleta sozinha: a leitura gera um evento que é comparado ao item esperado pela máquina de estados. Para casos em que a leitura local falha, a verificação assistida é um caminho de exceção, não dependência do ciclo normal.

### 6.2 Câmera e microfone

A câmera dos óculos é usada para ler códigos de barras e conferir itens no momento da coleta. O microfone é usado para comandos, check digits, quantidade e relato de exceções. Câmera e microfone não são recursos decorativos: ambos alimentam eventos reais no fluxo de separação.

### 6.3 Saída por áudio

O app orienta o operador por fala sintetizada em pt-BR e sinais sonoros. Endereço, confirmação de produto, quantidade, compartimento, progresso e alertas são comunicados por áudio. A saída preserva a percepção do ambiente, coerente com os alto-falantes open-ear dos óculos.

### 6.4 Privacidade

A câmera fica ativa somente durante escaneamento e conferência final; o restante do ciclo a mantém desligada. O frame precisa existir em memória para ser decodificado, mas o sistema retém somente o recorte necessário, em memória volátil, e executa limpeza explícita inclusive em erro. Não há gravação de áudio, galeria, backup ou reconhecimento facial.

O protótipo é transparente sobre a limitação física: a lente pode captar uma cena ampla no instante da captura. O controle aplicado é de retenção e processamento mínimo, não uma alegação incorreta de “captura somente do código”.

### 6.5 Eficiência de bateria

O principal controle de consumo é orientado ao estado: câmera ligada somente em `EscaneandoProduto` e `ConferenciaFinal`; desligada em navegação, confirmação de quantidade, alocação e pausa. O processamento pesado ocorre no smartphone, e o pipeline descarta frames enquanto uma análise está em curso. Restringir a câmera a poucos estados também reduz uso de Bluetooth e de CPU.

## 7. Escopo do protótipo

Inclui: sessão DAT, câmera real, microfone do dispositivo Android, reconhecimento de comandos, síntese de voz, leitura real de códigos físicos, máquina de estados, tratamento de exceções e MockDeviceKit para testes sem óculos físicos.

Não inclui: integração com WMS, backend próprio, persistência entre execuções, autenticação, otimização de rota por IA, iOS ou promessas de autonomia medida em produção. Esses limites evitam apresentar como implementado aquilo que é apenas uma direção futura.
