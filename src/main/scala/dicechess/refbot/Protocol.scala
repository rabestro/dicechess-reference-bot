package dicechess.refbot

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}

/** The Bot API wire vocabulary, mirroring dicechess-play-api's protocol so the bot speaks the same JSON. Only what the
  * bot sends or receives is modelled here.
  */
object Protocol:

  enum Side:
    case White, Black

  enum Seat:
    case White, Black, Spectator

  enum Principal:
    case Guest(id: String)
    case User(id: String)
    case Bot(team: String, name: String)

  enum Termination:
    case KingCaptured, Resign, Draw, Aborted, Timeout

  enum GameResult:
    case Win(side: Side)
    case Draw

  final case class GameOver(result: GameResult, termination: Termination)

  enum GameStatus:
    case Active
    case Ended(over: GameOver)

  final case class PublicGameState(
      version: Long,
      dfen: String,
      activeSeat: Seat,
      dicePending: Boolean,
      status: GameStatus
  )

  /** Events on a game's stream (`GET /bot/game/stream/{id}`). */
  enum GameEvent:
    case Snapshot(v: Long, state: PublicGameState)
    case DiceRolled(v: Long, seat: Seat, dice: List[Int], dfen: String)
    case TurnPlayed(v: Long, seat: Seat, moves: List[String], fenAfter: String)
    case GameEnded(v: Long, over: GameOver)
    case Rejected(v: Long, seat: Seat, reason: String)

  /** Events on the account stream (`GET /bot/stream/event`). */
  enum BotEvent:
    case ChallengeReceived(id: String, challenger: Principal)
    case ChallengeDeclined(id: String)
    case GameStart(gameId: String)

  final case class Challenge(id: String, challenger: Principal, target: Principal)
  final case class BotGame(gameId: String)
  final case class ChallengeTarget(team: String, name: String)
  final case class BotMove(moves: List[String])

  // ── codecs ──────────────────────────────────────────────────────────────────

  // Total, exception-free enum codec: decode by case name, encode as the case name.
  private def nameCodec[A](label: String, values: Array[A]): Codec[A] =
    val byName = values.iterator.map(v => v.toString -> v).toMap
    Codec.from(
      Decoder.decodeString.emap(s => byName.get(s).toRight(s"invalid $label: $s")),
      Encoder.encodeString.contramap(_.toString)
    )

  given Codec[Side]        = nameCodec("Side", Side.values)
  given Codec[Seat]        = nameCodec("Seat", Seat.values)
  given Codec[Termination] = nameCodec("Termination", Termination.values)

  given Codec[GameResult]      = deriveCodec
  given Codec[GameOver]        = deriveCodec
  given Codec[GameStatus]      = deriveCodec
  given Codec[Principal]       = deriveCodec
  given Codec[PublicGameState] = deriveCodec
  given Codec[GameEvent]       = deriveCodec
  given Codec[BotEvent]        = deriveCodec
  given Codec[Challenge]       = deriveCodec
  given Codec[BotGame]         = deriveCodec
  given Codec[ChallengeTarget] = deriveCodec
  given Codec[BotMove]         = deriveCodec
