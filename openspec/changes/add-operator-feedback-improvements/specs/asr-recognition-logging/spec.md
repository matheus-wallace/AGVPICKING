## Purpose

Registrar todo resultado final do ASR, aceito ou não pela gramática, para que a taxa de reconhecimento por comando possa ser medida em bancada em vez de inferida por sintoma ("falei e nada aconteceu").

## ADDED Requirements

### Requirement: Todo resultado final do ASR é logado
O sistema DEVE (MUST) logar de forma estruturada todo resultado final devolvido pelo Vosk, incluindo os descartados por não corresponder à gramática do estado atual ou por pertencerem a uma versão de estado obsoleta.

#### Scenario: Resultado fora da gramática é logado
- **WHEN** o Vosk devolve um resultado final que não corresponde a nenhuma palavra aceita na gramática do estado atual
- **THEN** o sistema loga o texto reconhecido e o motivo do descarte
- **AND** nenhum `PickingEvent` é publicado.

#### Scenario: Resultado de estado obsoleto é logado
- **WHEN** um resultado final chega associado a uma versão de estado anterior à atual
- **THEN** o sistema loga o texto reconhecido e o motivo do descarte (estado obsoleto)
- **AND** nenhum `PickingEvent` é publicado.
