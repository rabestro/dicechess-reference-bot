package dicechess.refbot

import dicechess.refbot.Protocol.*
import dicechess.refbot.Protocol.given
import io.circe.parser.decode
import io.circe.syntax.*

class ProtocolSuite extends munit.FunSuite:

  private def roundtrip[A: io.circe.Codec](value: A): Unit =
    assertEquals(decode[A](value.asJson.noSpaces), Right(value))

  test("bot events round-trip"):
    roundtrip[BotEvent](BotEvent.ChallengeReceived("c1", Principal.Bot("acme", "alice")))
    roundtrip[BotEvent](BotEvent.GameStart("g1"))
    roundtrip[BotEvent](BotEvent.ChallengeDeclined("c1"))

  test("game events round-trip"):
    roundtrip[GameEvent](GameEvent.DiceRolled(1L, Seat.White, List(1, 2, 6), "dfen", Some(Clocks(180000, 175000))))
    roundtrip[GameEvent](GameEvent.DiceRolled(2L, Seat.Black, List(4), "dfen", None))
    roundtrip[GameEvent](GameEvent.GameEnded(9L, GameOver(GameResult.Win(Side.Black), Termination.KingCaptured)))

  // Pin compatibility with the exact JSON dicechess-play-api emits, so a server-side codec change
  // that would break this bot fails here.
  test("decodes the play-api wire shapes the bot consumes"):
    assertEquals(decode[BotEvent]("""{"GameStart":{"gameId":"g1"}}"""), Right(BotEvent.GameStart("g1")))
    // Timed game: clocks present (remaining ms per side).
    assertEquals(
      decode[GameEvent](
        """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":{"white":180000,"black":175000}}}"""
      ),
      Right(GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", Some(Clocks(180000, 175000))))
    )
    // Unlimited game: clocks null.
    assertEquals(
      decode[GameEvent]("""{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":null}}"""),
      Right(GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", None))
    )
    // Snapshot carries the time control (the only event that does) — the bot needs the Fischer increment to budget.
    val snapshot = decode[GameEvent](
      """{"Snapshot":{"v":0,"state":{"version":0,"dfen":"fen","activeSeat":"White","dicePending":true,"status":{"Active":{}},"clocks":{"white":300000,"black":300000},"timeControl":{"Fischer":{"initialSeconds":300,"incrementSeconds":3}}}}}"""
    )
    assertEquals(
      snapshot.toOption.collect { case GameEvent.Snapshot(_, s) => s.timeControl },
      Some(Some(TimeControl.Fischer(300, 3)))
    )

  test("encodes what the bot sends"):
    assertEquals(BotMove(List("e2e4", "g1f3")).asJson.noSpaces, """{"moves":["e2e4","g1f3"]}""")
    assertEquals(ChallengeTarget("acme", "bob").asJson.noSpaces, """{"team":"acme","name":"bob"}""")
