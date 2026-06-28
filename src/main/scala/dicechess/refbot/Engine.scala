package dicechess.refbot

import dicechess.engine.domain.{FenParser, Move}
import dicechess.engine.search.{BotRegistry, ClockState, SearchAlgorithm, TimeBudgetedSearch, TimeManager}

import scala.util.Random

/** Thin wrapper over the dice-chess engine — the single source of truth for legality and move choice. Mirrors
  * dicechess-play-api's EngineOps so the bot computes moves exactly as the server validates them.
  */
object Engine:

  /** Slack subtracted from the per-turn budget for in-process transport + rollout granularity (≈50ms per
    * [[TimeManager]]'s docs) — the bot can't interrupt an in-flight rollout, so the deadline is set short.
    */
  private val OverheadBufferMs = 50L

  /** A search algorithm by name (e.g. "greedy", "monte-carlo"), or a clear failure. */
  def algorithm(name: String): SearchAlgorithm =
    BotRegistry.getAlgorithm(name).getOrElse(sys.error(s"unknown algorithm: $name"))

  /** Choose a turn for the side to move in `dfen` (a DFEN with the rolled dice), as a UCI move list. `None` means there
    * is no legal move (a forced pass — the server advances on its own).
    *
    * When `remainingMs` is given and the algorithm can search under a deadline ([[TimeBudgetedSearch]], e.g.
    * monte-carlo), the moving side's clock is turned into a per-turn budget by [[TimeManager]] and the search is
    * bounded by that deadline. Otherwise the algorithm runs to its own internal budget — instant ones (greedy and
    * friends) don't use the clock at all.
    */
  def chooseMoves(algorithm: SearchAlgorithm, dfen: String, remainingMs: Option[Long]): Option[List[String]] =
    FenParser
      .parse(dfen)
      .toOption
      .flatMap: state =>
        val sequence = (algorithm, remainingMs) match
          case (timed: TimeBudgetedSearch, Some(remaining)) =>
            // incrementMs is 0: the bot is not told the time control yet, so Fischer is budgeted conservatively
            // (sudden-death-like, never counting on the increment). Surfacing the increment is a follow-up.
            val clock    = ClockState(remainingMs = remaining, incrementMs = 0L, moveNumber = state.fullMoveNumber)
            val budgetMs = TimeManager.budgetMs(clock, OverheadBufferMs)
            val deadline = System.nanoTime() + budgetMs * 1_000_000L
            timed.findBestMove(state, deadline, new Random())
          case _ => algorithm.findBestMove(state)
        sequence.map(_.moves.map(toUci))

  /** UCI for a search-layer `Move` (which has no `toNotation` of its own). */
  private def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")
