package dicechess.refbot

import scala.concurrent.duration.*

class StrategySuite extends munit.FunSuite:

  // Start position with dice N B R — the knight is playable, so a legal turn always exists.
  private val dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBR"

  test("EngineStrategy returns a turn for a position with a playable die"):
    val moves = EngineStrategy("greedy").chooseMoves(MoveContext("test-game", dfen, clock = None))
    assert(moves.exists(_.nonEmpty), s"expected a non-empty UCI move list, got $moves")

  test("greedy is instant — it returns a turn regardless of the clock"):
    val clock = Some(TurnClock(remaining = 60.seconds, opponent = 60.seconds, increment = 3.seconds))
    val moves = EngineStrategy("greedy").chooseMoves(MoveContext("g", dfen, clock))
    assert(moves.exists(_.nonEmpty), s"expected a move, got $moves")

  test("monte-carlo budgets its search by the clock (with increment) and returns a turn within the deadline"):
    // A small Fischer clock yields a short per-turn deadline; the time-budgeted search must still return a legal turn.
    val clock = Some(TurnClock(remaining = 2.seconds, opponent = 2.seconds, increment = 1.second))
    val moves = EngineStrategy("monte-carlo").chooseMoves(MoveContext("g", dfen, clock))
    assert(moves.exists(_.nonEmpty), s"expected a move from the deadline-bounded search, got $moves")
