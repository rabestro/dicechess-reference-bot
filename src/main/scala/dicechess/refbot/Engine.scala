package dicechess.refbot

import dicechess.engine.domain.{FenParser, Move}
import dicechess.engine.search.{BotRegistry, ClockState, SearchAlgorithm, TimeBudgetedSearch, TimeManager}

import scala.util.Random

/** Thin wrapper over the dice-chess engine — the single source of truth for legality and move choice. Mirrors
  * dicechess-play-api's EngineOps so the bot computes moves exactly as the server validates them.
  */
object Engine:

  /** The moving side's clock for budgeting: time left before this turn and the per-turn Fischer increment, in
    * milliseconds (`incrementMs == 0` for sudden death / per-move).
    */
  final case class TurnBudget(remainingMs: Long, incrementMs: Long)

  /** Slack subtracted from the per-turn budget for in-process transport + rollout granularity (≈50ms per
    * [[TimeManager]]'s docs) — the bot can't interrupt an in-flight rollout, so the deadline is set short.
    */
  private val OverheadBufferMs = 50L

  /** One RNG for the (non-deterministic) time-budgeted search; `java.util.Random` is thread-safe, so a single shared
    * instance is fine across concurrently-played games.
    */
  private val rng = new Random()

  /** A search algorithm by name (e.g. "greedy", "monte-carlo"), or a clear failure. */
  def algorithm(name: String): SearchAlgorithm =
    BotRegistry.getAlgorithm(name).getOrElse(sys.error(s"unknown algorithm: $name"))

  /** Choose a turn for the side to move in `dfen` (a DFEN with the rolled dice), as a UCI move list. `None` means there
    * is no legal move (a forced pass — the server advances on its own).
    *
    * When `budget` is given and the algorithm can search under a deadline ([[TimeBudgetedSearch]], e.g. monte-carlo),
    * the moving side's clock is turned into a per-turn budget by [[TimeManager]] (honouring the Fischer increment) and
    * the search is bounded by that deadline. Otherwise the algorithm runs to its own internal budget — instant ones
    * (greedy and friends) don't use the clock at all.
    */
  def chooseMoves(algorithm: SearchAlgorithm, dfen: String, budget: Option[TurnBudget]): Option[List[String]] =
    FenParser
      .parse(dfen)
      .toOption
      .flatMap: state =>
        val sequence = (algorithm, budget) match
          case (timed: TimeBudgetedSearch, Some(b)) =>
            val clock =
              ClockState(remainingMs = b.remainingMs, incrementMs = b.incrementMs, moveNumber = state.fullMoveNumber)
            val budgetMs = TimeManager.budgetMs(clock, OverheadBufferMs)
            val deadline = System.nanoTime() + budgetMs * 1_000_000L
            timed.findBestMove(state, deadline, rng)
          case _ => algorithm.findBestMove(state)
        sequence.map(_.moves.map(toUci))

  /** UCI for a search-layer `Move` (which has no `toNotation` of its own). */
  private def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")
