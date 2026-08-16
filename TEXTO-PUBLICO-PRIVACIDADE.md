# Privacidade e Considerações Éticas — texto para a página pública

> Substitui a seção atual. Cada afirmação abaixo é verificável no código —
> nenhuma depende de promessa arquitetural.
> Base técnica: §9 da Documentação Técnica v1.4.

---

## Privacidade e Ética

O AGV Pick Voice opera com câmera e microfone em ambiente de trabalho
compartilhado. Tratamos isso como um compromisso de projeto, não como um
detalhe de conformidade.

### Captura pontual, nunca contínua

A câmera é ativada apenas em dois momentos do ciclo de separação: na leitura do
código do produto e na conferência final. Fora desses momentos ela permanece
desligada — o que representa a maior parte do tempo de operação. O operador é
avisado por sinal sonoro sempre que a captura está ativa.

O microfone opera durante a ordem de separação e processa fala exclusivamente
para reconhecer comandos e valores numéricos de um vocabulário restrito.

### Retenção mínima, com descarte verificável

A decodificação de um código de barras exige que a imagem exista em memória
durante o processamento. Não é uma escolha de otimização — é uma necessidade
técnica. O que controlamos é quanto tempo ela existe e o que sobra depois.

A imagem capturada é recortada na região do código imediatamente após a captura,
e o restante do quadro é descartado **antes de qualquer processamento**. O
sistema retém somente a região do código, em cache volátil, pelo tempo estrito
da operação. A deleção é executada por rotina explícita em código, inclusive em
caso de erro. Não há persistência em disco, galeria, backup ou sincronização.

O áudio é processado localmente e não é gravado nem armazenado em nenhum momento.

### Processamento restrito ao necessário

O sistema extrai exclusivamente códigos de barras e texto de rótulo. Nenhum
outro conteúdo da imagem é analisado, indexado ou retido. **Não há
reconhecimento facial ou de pessoas em nenhuma etapa do processamento.**

Somos francos sobre o limite disso: a lente dos óculos é grande-angular e capta
o quadro inteiro no instante da captura. Não existe captura seletiva em nenhum
dispositivo desse tipo. O que existe — e o que implementamos — é retenção
seletiva e processamento restrito.

### Operação local por padrão

Navegação, conferência de posição, decodificação e confirmação de quantidade
executam integralmente no dispositivo, sem qualquer transmissão de dados. Todo
o ciclo normal de separação funciona com o aparelho em modo avião.

A rede é acionada apenas quando a leitura local falha e o sistema recorre a
verificação assistida. Nesse caso é transmitido somente o recorte da região do
código, com metadados de localização e horário removidos. A cena completa não é
transmitida porque, nesse ponto, já não existe.

### Degradação em vez de interrupção

Se a rede estiver indisponível, o sistema não para: recorre à conferência por
código verificador falado, e a separação continua. Em operação farmacêutica,
continuidade não é conveniência — é requisito.

### A decisão final é humana

A inteligência artificial orienta e confere; não decide. O operador pode
rejeitar qualquer indicação do sistema, e a rejeição fica registrada. Toda
divergência entre o esperado e o conferido exige confirmação explícita do
operador antes de ser registrada.

O registro de cada conferência inclui **por qual método ela foi validada** —
leitura de código, reconhecimento de texto, verificação assistida ou código
falado — e o grau de confiança correspondente. Um item conferido por leitura
direta de DataMatrix e um conferido por método assistido não têm o mesmo peso
probatório, e o sistema não os trata como equivalentes.

### Dados do operador

Não coletamos dados pessoais do operador. Métricas de produtividade são
agregadas e anonimizadas: **o sistema não produz ranking individual e não é
instrumento de vigilância de desempenho.**

### Base legal (LGPD)

Cumprimento de obrigação regulatória de rastreabilidade (ANVISA, MAPA, SNCM) e
legítimo interesse para controle de qualidade em cadeia farmacêutica. Terceiros
presentes no ambiente são informados por sinalização no procedimento
operacional; o LED de captura dos óculos é hardware e permanece sempre ativo.

---

## Frases a evitar

Registro do que **não** deve voltar ao texto, e por quê:

| Não escrever | Motivo |
|---|---|
| "Zero armazenamento de imagens" | A decodificação exige a imagem em memória. É falso |
| "A câmera captura apenas os códigos" | A lente capta o quadro inteiro. Não há configuração que evite |
| "Nunca captura rostos ou ambientes" | Câmera na cabeça em galpão compartilhado vai captar colegas |
| "Processamento 100% local" | Verdadeiro para o ciclo normal, falso quando há verificação assistida |

Há banca técnica da Meta na avaliação, e eles conhecem as limitações do próprio
hardware. Uma afirmação frágil provoca a pergunta sem resposta — e contamina a
credibilidade das outras, que são verdadeiras e fortes.
