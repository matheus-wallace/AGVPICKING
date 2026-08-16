## Why

A sessão DAT já é o produtor real do ciclo de vida (`add-dat-session-mockdevice`), mas hoje a única fonte de `PickingEvent`s operacionais continua sendo o painel de dev — nenhum áudio real, simulado ou não, jamais chegou ao `PickingActor`. O doc §5.2 é explícito sobre o risco disso: "o Mock Device Kit simula a câmera. Não há equivalente para o caminho de áudio HFP. Sem abstração, o pipeline de voz — o de maior esforço no projeto — só é exercitado em 18/09, no dia em que não sobra tempo." O dono desta frente (Matheus Wallace, doc §13.2) não pode avançar para a cascata completa de VAD/gramática por estado/reranking (Marco 2, doc §13.1) sem antes provar, num corte fino, que microfone real → reconhecimento → evento no ator funciona de ponta a ponta — a mesma lógica de "fatia vertical fina" já aplicada à sessão DAT.

## What Changes

- Nova abstração `FonteAudio` (doc §5.2) em pacote próprio `audio/`, com uma única implementação nesta fatia: `AudioMicrofoneSimulado` (microfone do celular a 16 kHz, band-pass 300–3400 Hz, downsample para 8 kHz — mesma degradação de canal do §10.1). `AudioHfpOculos` fica como interface satisfeita apenas na fatia que trocar o device selector no dia do evento (doc §13.3); não é criada aqui.
- Novo componente `audio/ReconhecedorDeComando` (nome sugerido) que carrega o modelo Vosk pt-BR na inicialização do app (doc §5.3 — nunca ao criar a sessão), mantém uma única `Recognizer` com **uma gramática fixa e estática** (`["parar", "repetir"]`, perfil de endpoint `COMANDO_CURTO`, 280 ms de silêncio final), e publica `PickingEvent.ComandoParar`/`PickingEvent.ComandoRepetir` — eventos transversais que já existem e já são tratados pelo reducer, nenhum evento novo é criado.
- Endpointing por silêncio fixo (janela de 280 ms sem energia acima do limiar), sem o VAD Silero do doc §5 — a cascata completa (VAD dedicado, troca de gramática por estado, reranking, TTS de saída) é Marco 2 (doc §13.1) e fica fora desta fatia; ver design.md - Non-Goals.
- Todo o pipeline roda numa única thread de áudio dedicada, nunca na coroutine do ator nem na UI — restrição de arquitetura do projeto (`Vosk Recognizer` não é thread-safe), já vale a partir desta fatia mesmo com um único componente de IA local em uso.
- `AppContainer` passa a montar e expor `fonteAudio: FonteAudio` e o novo reconhecedor, resolvendo o `TODO(#audio-source-abstraction)` já existente no arquivo, seguindo a mesma convenção manual de DI usada para `datSessionController`.
- Nova dependência de biblioteca: `org.vosk:vosk-android` (ASR local, offline) e um modelo pt-BR compacto embutido nos assets do app — nenhuma chamada de rede.
- O painel de dev deixa de ser a única forma de disparar `ComandoParar`/`ComandoRepetir`: falar "parar" ou "repetir" perto do microfone do celular agora produz o mesmo evento que o botão correspondente já produzia. Os botões permanecem (doc `dev-event-panel` não muda).

## Capabilities

### New Capabilities
- `audio-source`: abstração de fonte de áudio (`FonteAudio`) com a implementação de microfone simulado, mais um reconhecedor de comando com gramática fixa (`parar`/`repetir`) rodando numa thread de áudio dedicada e publicando os `PickingEvent`s transversais já existentes.

### Modified Capabilities
(nenhuma — os eventos publicados já existem e já são especificados em `picking-state-machine`; esta mudança só acrescenta um novo produtor real, mesmo padrão da mudança anterior sobre a sessão DAT)

## Impact

- Código novo: pacote `audio/` (`FonteAudio`, `AudioMicrofoneSimulado`, `ReconhecedorDeComando`, thread/`Channel` dedicados).
- Código alterado: `AppContainer.kt` (resolve o TODO, expõe `fonteAudio` e o reconhecedor), `AgvPickVoice/app/build.gradle.kts` e `gradle/libs.versions.toml` (nova dependência `vosk-android`), manifesto (permissão `RECORD_AUDIO` em tempo de execução, solicitada de forma análoga a `BLUETOOTH_CONNECT` em `MainActivity.kt`).
- Assets novos: modelo Vosk pt-BR compacto embutido no app (aumenta o tamanho do APK; sem chamada de rede).
- Sem mudança de contrato de dado mockado ou de máquina de estados — este é o primeiro código do projeto que fala com áudio de verdade (ainda que via microfone do celular, não HFP dos óculos).
