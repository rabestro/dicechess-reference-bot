# dicechess-reference-bot

Reference bot **and starter-kit** for the **Dice Chess Bot API** — fork it, replace one method, and you
have your own bot. JVM (Scala 3), wraps the
[dice-chess engine](https://github.com/rabestro/dicechess-engine-scala) and plays via
[`dicechess-play-api`](https://github.com/rabestro/dicechess-play-api)'s Lichess-shaped Bot API
([full API reference](https://github.com/rabestro/dicechess-play-api/blob/main/docs/bot-api.md)).

## What it does

- Authenticates with a bot token and listens on `GET /bot/stream/event`.
- Accepts incoming challenges, and on `gameStart` streams the game (`GET /bot/game/stream/{id}`) and,
  on each dice roll, computes a turn and submits it (`POST /bot/game/{id}/move`).
- Reconnects the long-lived streams; the move call is fire-and-forget (the outcome arrives on the stream).

It never needs to know its colour: the move endpoint resolves the bot's seat **server-side**, so the
bot reacts to every roll and the server applies the move only on this bot's turn (off-turn submissions
are harmlessly rejected).

## Quickstart (play the house bot, no registration)

```bash
# 1. Mint an ephemeral anonymous token (unranked, zero registration)
T=$(curl -sX POST 'https://play-api.jc.id.lv/bot/anon?name=mybot' | jq -r .token)

# 2. Run the bot against the live API; challenge the always-on house bot on startup
BOT_TOKEN=$T PLAY_API_BASE_URL=https://play-api.jc.id.lv BOT_CHALLENGE='house|greedy' sbt run
```

You'll see the challenge, the game start, and each submitted turn logged. (Locally, point
`PLAY_API_BASE_URL` at your own `sbt run` of `dicechess-play-api` and use a `PLAY_BOT_TOKENS` entry.)

## Write your own bot

The whole client — auth, the account/game ndjson streams, reconnect, move submission, turn
de-duplication — is handled for you. **You implement one method:**

```scala
trait Strategy:
  /** Return the turn's micro-moves in UCI — one per die you use — or None to pass when there is no
    * legal move. The MoveContext carries the gameId (keep per-game state / tag logs), the DFEN
    * (position + rolled dice for the side to move), and the clock (your remaining time and the
    * opponent's, or None for an unlimited game). */
  def chooseMoves(ctx: MoveContext): Option[List[String]]
```

The default [`EngineStrategy`](src/main/scala/dicechess/refbot/Strategy.scala) just asks the dice-chess
engine. To build your bot:

1. Implement your own `Strategy` (your move-choosing logic).
2. Swap it into `Main` — `ReferenceBot(config, client, supervisor, MyStrategy())`.

That's it; you don't touch transport, streaming, or auth. (DFEN = standard FEN plus a 7th field listing
the rolled dice as piece letters; you move a piece of each die's type. Legality is the same engine the
server validates with, so a move your `Strategy` returns is accepted iff it's legal.)

## Configuration (environment)

| Var | Default | Meaning |
|-----|---------|---------|
| `PLAY_API_BASE_URL` | `http://localhost:8080` | the play-api base URL |
| `BOT_TOKEN` | — | the bot's Bearer token (anonymous via `POST /bot/anon`, or a `PLAY_BOT_TOKENS` entry) |
| `BOT_ALGORITHM` | `greedy` | engine search algorithm (used by the default `EngineStrategy`) |
| `BOT_CHALLENGE` | — | optional `team\|name` to challenge on startup (e.g. `house\|greedy`) |

## Self-play (local)

Run your own `dicechess-play-api` with two static tokens
(`PLAY_BOT_TOKENS='me|a|tokA,me|b|tokB'`), then start two instances — set `BOT_CHALLENGE='me|b'` on the
first so it opens the game; both accept and play to a finish. (On the live API, anonymous bots get
uuid-based names, so prefer challenging the house bot for a quick demo.)

## Container

CI publishes a multi-arch image to `ghcr.io/rabestro/dicechess-reference-bot` on every push to `main`
(and tagged `vX.Y.Z` on release). Run it next to the play-api via `docker-compose.yaml` — set
`BOT_TOKEN`, `PLAY_API_BASE_URL`, and `BOT_ALGORITHM` in `.env`:

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
