package dicechess.refbot

import dicechess.engine.domain.{FenParser, Move}
import dicechess.engine.search.{BotRegistry, SearchAlgorithm}

/** Thin wrapper over the dice-chess engine — the single source of truth for legality and move choice. Mirrors
  * dicechess-play-api's EngineOps so the bot computes moves exactly as the server validates them.
  */
object Engine:

  /** A search algorithm by name (e.g. "greedy", "monte-carlo"), or a clear failure. */
  def algorithm(name: String): SearchAlgorithm =
    BotRegistry.getAlgorithm(name).getOrElse(sys.error(s"unknown algorithm: $name"))

  /** Choose a turn for the side to move in `dfen` (a DFEN with the rolled dice), as a UCI move list. `None` means there
    * is no legal move (a forced pass — the server advances on its own).
    */
  def chooseMoves(algorithm: SearchAlgorithm, dfen: String): Option[List[String]] =
    FenParser.parse(dfen).toOption.flatMap(state => algorithm.findBestMove(state).map(_.moves.map(toUci)))

  /** UCI for a search-layer `Move` (which has no `toNotation` of its own). */
  private def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")
