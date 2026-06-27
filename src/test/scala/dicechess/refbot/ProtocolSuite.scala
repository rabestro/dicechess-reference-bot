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
    roundtrip[GameEvent](GameEvent.DiceRolled(1L, Seat.White, List(1, 2, 6), "dfen"))
    roundtrip[GameEvent](GameEvent.GameEnded(9L, GameOver(GameResult.Win(Side.Black), Termination.KingCaptured)))

  // Pin compatibility with the exact JSON dicechess-play-api emits, so a server-side codec change
  // that would break this bot fails here.
  test("decodes the play-api wire shapes the bot consumes"):
    assertEquals(decode[BotEvent]("""{"GameStart":{"gameId":"g1"}}"""), Right(BotEvent.GameStart("g1")))
    assertEquals(
      decode[GameEvent]("""{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen"}}"""),
      Right(GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen"))
    )

  test("encodes what the bot sends"):
    assertEquals(BotMove(List("e2e4", "g1f3")).asJson.noSpaces, """{"moves":["e2e4","g1f3"]}""")
    assertEquals(ChallengeTarget("acme", "bob").asJson.noSpaces, """{"team":"acme","name":"bob"}""")
