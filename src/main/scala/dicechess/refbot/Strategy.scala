package dicechess.refbot

import scala.concurrent.duration.FiniteDuration

/** What a strategy is told about the turn it must play. New fields can be added here without changing the [[Strategy]]
  * signature, so a fork's `chooseMoves` keeps compiling as the bot learns to surface more context.
  *
  *   - `gameId` — lets a fork keep per-game state (a search tree, a transposition table) or tag its logs.
  *   - `dfen` — the position plus the rolled dice for the side to move.
  *   - `clock` — remaining time for the side to move and its opponent, or `None` for an unlimited game.
  */
final case class MoveContext(gameId: String, dfen: String, clock: Option[TurnClock])

/** The clock for a timed game, so a strategy can budget its thinking.
  *
  *   - `remaining` — time left for the side to move (before this turn).
  *   - `opponent` — time left for the other side.
  *   - `increment` — the Fischer increment credited per completed turn (`Duration.Zero` for sudden death / per-move).
  */
final case class TurnClock(remaining: FiniteDuration, opponent: FiniteDuration, increment: FiniteDuration)

/** The one thing a bot author replaces.
  *
  * Given a [[MoveContext]], return the turn's micro-moves in UCI (one per die you use), or `None` to pass when there is
  * no legal move. Everything else — auth, the account/game ndjson streams, reconnect, move submission, turn
  * de-duplication — is handled by [[ReferenceBot]], so a fork only implements this trait and wires it in `Main`.
  *
  * The bot never needs to know its colour: the server resolves the seat, so `chooseMoves` is called on every roll and
  * an off-turn submission is harmlessly rejected — `clock.remaining` is therefore always the time for the side to move
  * in `dfen`. [[ReferenceBot]] runs this on the blocking pool, so a slow search cannot stall the keep-alive on the
  * long-lived streams.
  */
trait Strategy:
  def chooseMoves(ctx: MoveContext): Option[List[String]]

/** The reference strategy: the dice-chess engine's search — the same engine the server validates with, so legality
  * matches exactly. For a time-budgeted algorithm (e.g. monte-carlo) in a timed game it spends the moving side's
  * remaining time as a per-turn search deadline (via the engine's TimeManager); instant algorithms (greedy) ignore the
  * clock. Swap this out for your own [[Strategy]] in `Main` to build your bot.
  */
final class EngineStrategy(algorithmName: String) extends Strategy:
  private val algorithm = Engine.algorithm(algorithmName)

  def chooseMoves(ctx: MoveContext): Option[List[String]] =
    Engine.chooseMoves(
      algorithm,
      ctx.dfen,
      ctx.clock.map(c => Engine.TurnBudget(c.remaining.toMillis, c.increment.toMillis))
    )
