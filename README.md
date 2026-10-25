# StarfinMinigames — Fase 1.2

API compartilhada e primeira versão do roteamento BungeeCord para Java 8 e Spigot/PaperSpigot 1.8.8. Party, amizades, clãs, ranqueadas e campeonatos permanecem fora deste módulo.

## Módulos e responsabilidades

| Módulo | Responsabilidade |
|---|---|
| `starfinminigames-api` | Contratos Java puros, snapshots imutáveis, estados e registro em memória. Não importa Bukkit. |
| `starfinminigames-spigot` | Plugin Spigot, publicação pelo `ServicesManager`, associação dos registros aos plugins proprietários, comandos e verificações de thread. O JAR final contém exatamente uma cópia da API. |
| `starfinminigames-bungee` | Plugin BungeeCord independente: lê o catálogo MySQL via adaptador BedWars, seleciona arenas, grava reservas atômicas e transfere jogadores. Empacota HikariCP, MySQL Connector/J e SLF4J isolados. |
| StarfinCore | Continua responsável pelas bibliotecas compartilhadas do Spigot. O Bungee empacota seu próprio acesso MySQL e não depende do Core Spigot. |
| StarfinBedWars | Mantém suas regras, arenas, partidas, perfis, XP, preferências e catálogo MySQL. Publica somente projeções e eventos no registro compartilhado. |

Não há nova tabela de estatísticas. O catálogo `sbw_network_*` do BedWars continua sendo a fonte de anúncios entre servidores; a nova tabela aditiva `sm_network_reservations` guarda reservas temporárias. A API local do Spigot continua independente do proxy.

## Compilação e instalação

```text
cd StarfinMinigames
mvn -B clean install
cd ../StarfinBedWars
mvn -B clean verify
```

O primeiro comando instala `starfinminigames-api:0.1.0-SNAPSHOT` no Maven local, necessário para compilar o BedWars. Instale `starfinminigames-spigot/target/StarfinMinigames-Spigot.jar` antes da JAR do BedWars. Em cada Spigot de BedWars, a ordem de carga é StarfinCore → StarfinMinigames-Spigot → StarfinBedWars. Instale `starfinminigames-bungee/target/StarfinMinigames-Bungee.jar` somente na pasta de plugins do BungeeCord. O Bungee **não substitui** o módulo Spigot. Sem StarfinMinigames, o BedWars local continua funcionando.

O plugin gera `plugins/StarfinMinigames/config.yml` com `server-id: local-01`. Defina um ID único e estável por servidor, por exemplo `bedwars-01`, antes de publicar. Se o catálogo `network.yml` do BedWars estiver ativo, seu `server-id` deve ser igual; divergência desativa a publicação do catálogo e preserva o jogo local. Alterar `server-id` exige reinício.

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

## Proxy BungeeCord e reservas

1. Configure no BungeeCord os nomes reais dos servidores backend. No `plugins/StarfinMinigames/config.yml` do proxy, ative `network.enabled` e `mysql.enabled`, defina o lobby e associe cada ID estável ao nome registrado no proxy em `network.servers`. Exemplo: `bedwars-01: survival`. O proxy só aceita destinos dessa lista e da configuração BungeeCord. Nunca publique a senha MySQL no Git.
2. Em cada Spigot BedWars, configure o mesmo `server-id` no StarfinMinigames e no `plugins/StarfinBedWars/network.yml`. Ative `stats.yml` com MySQL compartilhado e depois `network.yml` com `enabled: true`; configure `lobby-server` com o nome Bungee. O catálogo usa o banco de `stats.yml` e publica heartbeat, arenas e jogadores sem bloquear a thread Bukkit.
3. O proxy precisa usar o **mesmo schema MySQL**. O primeiro início cria apenas `sm_network_reservations`; não recria nem apaga `sbw_network_*`. O usuário SQL precisa de SELECT/INSERT/UPDATE/DELETE nessa tabela e SELECT no catálogo. O BedWars precisa de leitura e escrita no catálogo e nas reservas. Faça backup dos YAML e JARs antes de atualizar.

`/smjoin bedwars [solo|duo|trio|squad]` consulta anúncios recentes, verifica o heartbeat e a instância UUID, prioriza partida aguardando com vaga e reserva uma posição sob bloqueio transacional de servidor e arena. A reserva guarda UUID do jogador, jogo, modalidade, servidor, instância, arena, estado e validade. O proxy transfere pela API nativa BungeeCord. Na chegada, o BedWars reivindica a reserva por UUID no MySQL, solicita entrada ao `MatchEngineService` e confirma **somente após** o evento de entrada efetiva. Falhas e expiração liberam a vaga; a admissão local continua verificando capacidade e estado real. A entrega pelo banco funciona mesmo com servidor inicialmente vazio. Reservas não substituem a reconexão BedWars.

O comando administrativo `/smnetwork <status|servers|games|arenas|players|reload>` requer `starfinminigames.admin`. `reload` aplica mensagens e limites de matchmaking; mudanças de identidade, servidor ou MySQL requerem reinício. `/smjoin` aceita jogadores. A configuração do proxy inclui prefixo de mensagens; detalhes de banco e senha nunca são enviados ao jogador.

### Atualização e homologação

1. Faça backup das JARs e configurações, instale o Bungee no proxy e os módulos Spigot/Core/BedWars nos backends. Reinicie os backends antes do proxy para publicar o catálogo. Verifique `/smnetwork status`, `servers` e `arenas`.
2. No lobby, entre com um cliente 1.8.8 e execute `/smjoin bedwars solo`. Confirme no banco a transição `PENDING → CLAIMED → CONFIRMED` e no jogo a arena correta. Teste dois jogadores disputando a última vaga, destino offline, arena cheia, desconexão na transferência e retorno/reconexão BedWars.
3. Teste o BedWars com `network.enabled: false` para confirmar o modo local. Para voltar à versão anterior, pare os serviços, restaure JARs e YAML de backup e reinicie; a tabela de reservas pode permanecer sem afetar dados antigos.

O teste SQL de concorrência usa um schema **descartável** informado por `SM_TEST_MYSQL_HOST`, `SM_TEST_MYSQL_DATABASE`, `SM_TEST_MYSQL_USER` e `SM_TEST_MYSQL_PASSWORD`. Sem essas variáveis, o teste é ignorado. Ele cria linhas de catálogo próprias, disputa a última vaga em duas threads e as remove ao terminar.

### Limites desta fase

O proxy possui adaptador para BedWars; SkyWars/Murder precisam publicar catálogo e implementar seu próprio adaptador. A reserva protege concorrência entre requisições do proxy, mas entradas locais ainda exigem validação final no BedWars. Não há Party nem reserva em lote. UUID da instância muda a cada inicialização; `server-id`, IDs de jogo, arena e modalidade são estáveis. A API não agrega XP nem perfis: esses dados permanecem no MySQL existente do BedWars.
`network-id` é um identificador administrativo nesta versão; o catálogo legado `sbw_network_*` não possui essa coluna. Use um schema MySQL exclusivo por rede para impedir mistura de redes distintas.
