package dicechess.refbot

import dicechess.refbot.Protocol.Seat

class ReferenceBotSuite extends munit.FunSuite:

  test("searches only on our own rolls once the seat is known"):
    assert(ReferenceBot.shouldSearch(Some(Seat.White), Seat.White))
    assert(ReferenceBot.shouldSearch(Some(Seat.Black), Seat.Black))
    assert(!ReferenceBot.shouldSearch(Some(Seat.White), Seat.Black))
    assert(!ReferenceBot.shouldSearch(Some(Seat.Black), Seat.White))

  // The invariant the whole optimisation hangs on: the bot must never need its colour to keep playing. An unresolved
  // seat has to degrade to the historical behaviour — search every roll, let the server reject the off-turn ones —
  // rather than sit out its own turns.
  test("an unknown seat falls back to searching every roll"):
    assert(ReferenceBot.shouldSearch(None, Seat.White))
    assert(ReferenceBot.shouldSearch(None, Seat.Black))
