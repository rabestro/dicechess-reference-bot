# dicechess-reference-bot

Reference bot for the **Dice Chess Bot API** — an always-online opponent that dog-foods the API.
JVM (Scala 3), wraps the [dice-chess engine](https://github.com/rabestro/dicechess-engine-scala)
and plays via [`dicechess-play-api`](https://github.com/rabestro/dicechess-play-api)'s
Lichess-shaped Bot API.

## What it does

- Authenticates with a bot token and listens on `GET /bot/stream/event`.
- Accepts incoming challenges (`POST /bot/challenge/{id}/accept`).
- On `gameStart`, streams the game (`GET /bot/game/stream/{id}`) and, on each dice roll, computes a
  turn with the engine and submits it (`POST /bot/game/{id}/move`).

It never needs to know its colour: the move endpoint resolves the bot's seat **server-side**, so the
bot reacts to every roll and the server applies the move only on this bot's turn (off-turn
submissions are harmlessly rejected).

## Configuration (environment)

| Var | Default | Meaning |
|-----|---------|---------|
| `PLAY_API_BASE_URL` | `http://localhost:8080` | the play-api base URL |
| `BOT_TOKEN` | — | the bot's Bearer token (must match a `PLAY_BOT_TOKENS` entry on the server) |
| `BOT_ALGORITHM` | `greedy` | engine search algorithm |
| `BOT_CHALLENGE` | — | optional `team\|name` to challenge on startup (for bot-vs-bot demos) |

## Run

```bash
BOT_TOKEN=… PLAY_API_BASE_URL=http://localhost:8080 sbt run
```

For a bot-vs-bot demo, run two instances with distinct tokens and set `BOT_CHALLENGE` on one so it
opens the game; both accept and play to a finish.

### Container

CI publishes a multi-arch image to `ghcr.io/rabestro/dicechess-reference-bot` on every push to `main`
(and tagged `vX.Y.Z` on release). Run it next to the play-api on the homelab via `docker-compose.yaml`
— set `BOT_TOKEN`, `PLAY_API_BASE_URL`, and `BOT_ALGORITHM` in `.env`:

```bash
docker compose pull && docker compose up -d
```

The bot is an outbound client (no listening port); it reconnects to the play-api and plays whatever
challenges arrive.

## Stack

Scala 3 · cats-effect · fs2 · http4s-ember-client · Circe · the dice-chess engine (JVM). Same
toolchain as `dicechess-play-api`.

## License

[AGPL-3.0](./LICENSE) — inherited from the dice-chess engine this bot links.
