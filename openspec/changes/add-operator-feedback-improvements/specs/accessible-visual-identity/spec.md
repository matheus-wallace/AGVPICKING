## Purpose

Definir uma paleta de cores e tipografia acessíveis para as telas operacional e de
espelho, inspirada nos princípios de acessibilidade publicados sobre o íon Itaú, sem
reaproveitar ativos de marca do Itaú.

## ADDED Requirements

### Requirement: Contraste mínimo AA em toda combinação texto/fundo

O sistema DEVE (MUST) usar, em toda combinação de cor de texto sobre cor de fundo nas
telas operacional e de espelho, uma taxa de contraste de pelo menos 4,5:1 (WCAG 2.1,
nível AA).

#### Scenario: Texto sobre fundo cumpre o contraste mínimo

- **WHEN** qualquer texto é renderizado sobre um fundo definido pelo tema do app
- **THEN** a taxa de contraste entre a cor do texto e a cor do fundo é de pelo menos
  4,5:1.

### Requirement: Paleta reduzida com verde como cor principal

O sistema DEVE (MUST) usar uma paleta com verde como cor principal (dois tons), acentos
em verde-limão e cinza, e laranja restrito a destaques pontuais — nunca como cor
dominante de tela.

#### Scenario: Laranja não é cor dominante

- **WHEN** o tema define a cor de uma superfície grande da tela (fundo, cartão principal)
- **THEN** essa cor não é laranja.

### Requirement: Alvos de toque de pelo menos 48dp

O sistema DEVE (MUST) dimensionar todo elemento interativo (botão, item clicável) nas
telas operacional e de espelho com pelo menos 48dp de lado.

#### Scenario: Botão dentro do alvo mínimo de toque

- **WHEN** um botão é renderizado em `OperationScreen` ou `MirrorScreen`
- **THEN** sua área de toque tem pelo menos 48dp de largura e altura.
