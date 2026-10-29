# Fase 6.2: filas e matchmaking ranqueado

## Responsabilidades e ativação

StarfinSocial fornece o estado e a revisão da Party. StarfinMinigames Bungee mantém a fila, consulta MMR somente em leitura, escolhe arena e times, grava reservas e transfere jogadores. StarfinBedWars valida a admissão, executa a partida e grava os resultados. StarfinLogin Bungee fornece a prova de autenticação da conexão atual. As filas ficam desabilitadas por padrão.

Atualize o proxy e o módulo Spigot do StarfinMinigames juntos. Atualize também BedWars e StarfinLogin. Compartilhe o mesmo MySQL entre o catálogo `sbw_network_*`, os perfis `sbw_ranked_*` e as reservas `sm_*`. Em cada backend, `network.yml` e `stats.yml` precisam estar ativos, com `server-id` correspondente ao proxy. Em `ranked.yml`, configure `enabled: true`, `authorized-plugins: [StarfinMinigames]` e `arenas.allowed-groups` iguais aos do proxy. Crie arenas com `arena_group: ranked`. Ative `ranked-matchmaking.enabled` no proxy somente depois de verificar as dependências. Arquivos de configuração já existentes não são sobrescritos: adicione as chaves novas manualmente.

`ranked-matchmaking.auth.require-starfin-login` vem ativo e falha fechado se a API de sessão do StarfinLogin não estiver disponível. Desativá-lo exige uma rede com outra autenticação equivalente. Party em Solo ranqueado permanece proibida; `allow-solo-party: true` é rejeitado pela validação para não criar uma falsa promessa de isolamento entre adversários.

## Comandos

- `/smranked join <solo|duo|trio|squad>`: inscreve o jogador ou, se ele for líder, sua Party online.
- `/smranked leave`: cancela uma busca ou formação ainda não admitida sem resultado competitivo.
- `/smranked status`: modalidade, estado, tempo, tamanho da Party, MMR médio e intervalo atual.
- `/smranked help`: ajuda.
- `/smranked admin <status|queues|players|matches|inspect UUID|opponents UUID|reload>`: diagnósticos com `starfinminigames.ranked.admin`. `opponents` lista adversários repetidos nos últimos sete dias; é evidência para revisão, sem punição automática.

`/smjoin` continua casual e ignora arenas do grupo ranqueado. Comandos existentes de perfil do BedWars continuam funcionando. `reload` aceita mensagens e limites, mas mudanças de identidade, MySQL, servidores ou período do agendador exigem reinício.

## Algoritmo e persistência

Cada inscrição guarda UUIDs, líder, Party/revisão, modalidade, contexto, MMR por membro, média do grupo, proxy dono, timestamps e estado. O proxy lê `sbw_ranked_policy` publicado pelo BedWars para o contexto e MMR inicial; não modifica ratings. Rejeita divergência de política entre arenas elegíveis e falha de consulta. A diferença máxima entre o maior e o menor MMR de uma Party é configurável.

O intervalo começa em ±250 pontos, cresce 75 a cada 30 segundos de espera e limita-se a ±600, pelos valores padrão. A comparação deve caber no intervalo de ambos os adversários. Uma busca limitada explora atribuições de grupos inteiros às capacidades reais das equipes, exige elenco completo e pelo menos duas equipes, e escolhe a menor diferença entre as médias das equipes dentro do limite configurado. Uma Party nunca é dividida. Sem distribuição válida, a inscrição espera.

`sm_ranked_queue_entries` e `sm_ranked_queue_members` têm estados `WAITING`, `RESERVED`, `TRANSFERRING`, `ADMITTED`, `CANCELLED` e `EXPIRED`. `sm_ranked_allocations`, `sm_ranked_allocation_members`, `sm_ranked_arena_leases` e `sm_ranked_mode_mutex` coordenam a mesma arena e inscrições entre proxies. Um bloqueio InnoDB por modalidade, o bloqueio da arena e a chave única por jogador fazem alocação e reservas de todo o elenco na mesma transação. Heartbeats revalidam presença e Party; filas e formações antigas expiram. Reinício de proxy não reutiliza inscrição sem sessão atual. A Party só entra se todos os membros online estiverem no mesmo proxy e lobby; transferência entre proxies de uma mesma Party continua indisponível.

Após a reserva, cada proxy dono transfere seus membros. Bungee pode confirmar a conexão, mas apenas a admissão no backend conta. O BedWars relê a alocação autoritativa no MySQL, verifica servidor/instância, arena, modo, contexto, UUIDs, IDs de reserva, equipes e validade, e marca cada chegada. Com o elenco completo, o módulo Spigot StarfinMinigames chama `RankedApi.authorize` como emissor autorizado; a contagem do BedWars só começa depois. Uma formação incompleta expira e é cancelada sem resultado. Transferência parcial não produz derrota competitiva.

Uma reconexão durante a partida usa o ReconnectService existente e mantém o vínculo ao match. A inscrição numa nova fila é rejeitada enquanto o catálogo registra uma partida ranqueada ativa para aquele jogador. Abandono definitivo entra no resultado do BedWars e pode criar suspensão temporária específica do contexto após o limiar configurado. O evento de abandono, suspensão e resultado compartilham a transação idempotente do match. A expiração de heartbeat e leases protege contra reserva esquecida; falhas de MySQL impedem novas admissões.

## Limites e homologação

As consultas do proxy são assíncronas, limitadas pelo pool de I/O. O algoritmo lê no máximo 48 inscrições e testa até oito âncoras, 24 compatíveis e 200 mil estados de busca por âncora; fila muito grande pode exigir aprimoramento de indexação e paginação. Não há transferência segura de Party distribuída entre proxies. O bloqueio de nova fila durante reconnect depende da publicação recente do catálogo do backend, portanto a confirmação final de reconexão no proxy continua uma área para reforço. A suspensão usa histórico de abandono já processado, não mera desconexão. O comando de diagnóstico não fabrica partidas.

Testes automatizados: Maven, testes puros de equipes/intervalos, migração casual, disputa de dois proxies pela mesma arena, inscrição duplicada, validade de reservas, autorização no backend, suspensão e transação de ratings em schemas MariaDB descartáveis. Consulte os relatórios Surefire para contagens.

**PENDENTE DE HOMOLOGAÇÃO MANUAL FINAL:** autenticar no proxy com Minecraft 1.8.8, inscrever Solo e Dupla, confirmar Party e times, testar reconexão/abandono, transferência parcial, indisponibilidade de destino, falha de MySQL, resultados e MMR, e verificar que casual não entra em arena ranqueada. Esta fase não instala nem ativa automaticamente as JARs nos servidores de produção.
