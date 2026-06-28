package dicechess.refbot

class StrategySuite extends munit.FunSuite:

  test("EngineStrategy returns a turn for a position with a playable die"):
    // Start position with dice N B R — the knight is playable, so the engine strategy chooses a move.
    val dfen  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBR"
    val moves = EngineStrategy("greedy").chooseMoves("test-game", dfen)
    assert(moves.exists(_.nonEmpty), s"expected a non-empty UCI move list, got $moves")
