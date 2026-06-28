package dicechess.refbot

/** The one thing a bot author replaces.
  *
  * Given the `gameId` and a DFEN — the position plus the rolled dice for the side to move — return the turn's
  * micro-moves in UCI (one per die you use), or `None` to pass when there is no legal move. `gameId` lets a fork keep
  * per-game state (a search tree, a transposition table) or tag its logs; the reference engine ignores it. Everything
  * else — auth, the account/game ndjson streams, reconnect, move submission, turn de-duplication — is handled by
  * [[ReferenceBot]], so a fork only implements this trait and wires it in `Main`.
  *
  * The bot never needs to know its colour: the server resolves the seat, so `chooseMoves` is called on every roll and
  * an off-turn submission is harmlessly rejected. [[ReferenceBot]] runs this on the blocking pool, so a slow search
  * cannot stall the keep-alive on the long-lived streams.
  */
trait Strategy:
  def chooseMoves(gameId: String, dfen: String): Option[List[String]]

/** The reference strategy: the dice-chess engine's search — the same engine the server validates with, so legality
  * matches exactly. Swap this out for your own [[Strategy]] in `Main` to build your bot.
  */
final class EngineStrategy(algorithmName: String) extends Strategy:
  private val algorithm = Engine.algorithm(algorithmName)

  def chooseMoves(gameId: String, dfen: String): Option[List[String]] = Engine.chooseMoves(algorithm, dfen)
