# StarfinMinigames — Fase 1

API compartilhada de descoberta de minigames para Java 8 e Spigot/PaperSpigot 1.8.8. Este projeto é independente do StarfinBedWars e não implementa Party, matchmaking entre servidores, transferência BungeeCord, kits, ranqueadas ou campeonatos.

## Módulos e responsabilidades

| Módulo | Responsabilidade |
|---|---|
| `starfinminigames-api` | Contratos Java puros, snapshots imutáveis, estados e registro em memória. Não importa Bukkit. |
| `starfinminigames-spigot` | Plugin Spigot, publicação pelo `ServicesManager`, associação dos registros aos plugins proprietários, comandos e verificações de thread. O JAR final contém exatamente uma cópia da API. |
| StarfinCore | Continua responsável por bibliotecas e infraestrutura compartilhadas já existentes. StarfinMinigames não usa HikariCP nem cria conexão SQL nesta fase. |
| StarfinBedWars | Mantém suas regras, arenas, partidas, perfis, XP, preferências e catálogo MySQL. Publica somente projeções e eventos no registro compartilhado. |

Não há nova tabela de estatísticas nem outro catálogo SQL. O catálogo `sbw_network_*` do BedWars continua sendo a fonte de anúncios entre servidores. A API deste projeto representa o estado local do processo Spigot; não é uma reserva distribuída de vagas.

## Compilação e instalação

```text
cd StarfinMinigames
mvn -B clean install
cd ../StarfinBedWars
mvn -B clean verify
```

O primeiro comando instala `starfinminigames-api:0.1.0-SNAPSHOT` no Maven local, necessário para compilar o BedWars. Instale `starfinminigames-spigot/target/StarfinMinigames-Spigot.jar` antes da JAR do BedWars. Em cada Spigot de BedWars, a ordem de carga é StarfinCore → StarfinMinigames → StarfinBedWars. O BedWars exige StarfinCore e declara StarfinMinigames como `softdepend`; se o novo plugin estiver ausente ou sua API não estiver disponível, o gameplay e a persistência existentes continuam funcionando, e a integração fica indisponível até o próximo reinício. StarfinMinigames poderá tornar-se obrigatório quando uma futura funcionalidade depender de descoberta entre jogos.

O plugin gera `plugins/StarfinMinigames/config.yml` com `server-id: local-01`. Defina um ID único e estável por servidor, por exemplo `bedwars-01`, antes de publicar. Se o catálogo `network.yml` do BedWars estiver ativo, seu `server-id` deve ser igual; divergência impede apenas o registro na API para evitar identidades contraditórias. Alterar `server-id` exige reinício.

## API pública

Obtenha `MinigameRegistry` pelo Bukkit `ServicesManager`. Os contratos em `com.starfinplugins.minigames.api` incluem:

- `MinigameProvider`: fornece descritor, arenas, partidas e jogadores atuais.
- `MinigameDescriptor`: ID estável, nome, servidor, instância UUID e modalidades disponíveis.
- `MinigameArenaSnapshot`: arena, modalidade, grupo, capacidade, ocupação e possibilidade de entrada.
- `MinigameMatchSnapshot`: partida UUID, arena, estado público, capacidade, ocupação, UUIDs dos participantes e horário de início.
- `MinigamePlayerSnapshot`: identidade UUID e papel na partida. Nome não é chave primária.
- `MinigameRegistry`, `MinigameRegistration` e `MinigameSubscription`: registro, consultas, publicação de eventos e encerramento.
- `MinigameLifecycleListener` e `MinigameEvent`: notificações informativas.

Todos os snapshots fazem cópias defensivas de coleções; não contêm mundo, `Location`, `GameMatch` nem referência mutável ao proprietário. O registro chama o provedor **na thread principal Bukkit** a cada consulta, de modo que a resposta reflete mudanças de arena e partida sem registrar novamente. Chamadas fora dessa thread são recusadas pelo adaptador Spigot. Consumidores que precisam de trabalho assíncrono devem copiar o snapshot na thread principal e processá-lo depois.

O `MinigameRegistration` retornado por `register` pertence ao plugin que forneceu a implementação. Fechá-lo remove o registro. O plugin Spigot também remove automaticamente registros e inscrições em `PluginDisableEvent`, além de limpar tudo no próprio `onDisable`. Uma falha em listener externo é isolada e não interrompe a partida. Eventos são entregues de modo síncrono na thread principal e não permitem modificar a partida.

### Estados e eventos

Estados públicos: `WAITING`, `STARTING`, `IN_GAME`, `ENDING`, `RESETTING`, `CLOSED`. O BedWars adapta seus estados internos sem substituí-los; `ERROR` aparece como `CLOSED`, e o evento de remoção apresenta `CLOSED` mesmo após o reset interno voltar a `WAITING`.

| Evento público | Origem no BedWars |
|---|---|
| `MINIGAME_REGISTERED`, `MINIGAME_UNREGISTERED` | Registro/remoção do provedor. |
| `MATCH_CREATED` | `CREATED` |
| `MATCH_STARTING` | `COUNTDOWN_STARTED` |
| `MATCH_STARTED` | `STARTED` |
| `PLAYER_JOINED_MATCH` | `PLAYER_JOINED` |
| `PLAYER_LEFT_MATCH` | `PLAYER_LEFT` |
| `PLAYER_ELIMINATED` | `PLAYER_ELIMINATED` |
| `MATCH_ENDED` | `ENDED` |
| `MATCH_REMOVED` | `INSTANCE_REMOVED` |

O evento contém tipo, ID do minigame, instante em milissegundos, snapshot da partida quando aplicável e UUID do participante quando aplicável. `COUNTDOWN_CANCELLED`, mortes comuns, camas e outros eventos específicos do BedWars não geram um segundo evento genérico. As consultas refletem o estado atual mesmo quando uma transição não possui evento público.

### Exemplo para um futuro StarfinSkyWars

O futuro plugin pode depender apenas de `starfinminigames-api` com escopo Maven `provided` e declarar `softdepend: [StarfinMinigames]` ou `depend: [StarfinMinigames]` em seu `plugin.yml`:

```java
RegisteredServiceProvider<MinigameRegistry> service =
    Bukkit.getServicesManager().getRegistration(MinigameRegistry.class);
MinigameRegistry registry = service.getProvider();
MinigameProvider skywars = new MinigameProvider() {
    public MinigameDescriptor descriptor() {
        return new MinigameDescriptor("skywars", "StarfinSkyWars",
            registry.getServerId(), registry.getInstanceId(),
            Collections.singleton("solo"));
    }
    public Collection<MinigameArenaSnapshot> arenas() {
        return Collections.singletonList(new MinigameArenaSnapshot(
            "skywars", "sky_castle", "Sky Castle", "solo", "default",
            registry.getServerId(), 2, 12, currentPlayerCount(),
            true, true, currentMatchId()));
    }
    public Collection<MinigameMatchSnapshot> matches() {
        return Collections.singletonList(new MinigameMatchSnapshot(
            currentMatchId(), "skywars", "sky_castle", registry.getServerId(),
            MinigameMatchState.IN_GAME, 12, currentPlayerCount(),
            currentPlayerUuids(), startedAtMillis()));
    }
    public Collection<MinigamePlayerSnapshot> players() {
        return currentPlayerSnapshots();
    }
};
MinigameRegistration registration = registry.register(skywars);
// Guarde registration e chame registration.close() no onDisable.
```

Os métodos `current...()` representam dados internos do SkyWars e devem produzir valores coerentes; nenhuma classe do BedWars é importada.

## Administração e diagnóstico

Permissão `starfinminigames.admin` (operador por padrão):

- `/sminigames status`: versão da API, IDs de servidor/instância, quantidade de jogos e presença do StarfinCore.
- `/sminigames list`: jogos e modalidades registrados.
- `/sminigames info <id>`: modalidades, arenas, partidas e jogadores do jogo.
- `/sminigames reload`: relê a configuração sem recriar serviços; mudança de `server-id` solicita reinício.

## Limites desta fase

Não há transporte BungeeCord, consulta remota, reservas distribuídas, descoberta de capacidade entre servidores nem persistência própria. UUID da instância muda a cada inicialização; `server-id`, IDs de jogo, arena e modalidade são estáveis. Os snapshots são locais e pontuais; consumidores devem consultar novamente quando precisarem do estado mais recente. A API não agrega XP nem perfis: esses dados permanecem no MySQL existente do BedWars.
