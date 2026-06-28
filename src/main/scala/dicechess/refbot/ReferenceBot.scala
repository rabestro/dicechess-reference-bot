package dicechess.refbot

import cats.effect.{IO, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import dicechess.refbot.Protocol.*
import dicechess.refbot.Protocol.given
import fs2.Stream
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Request}

import scala.concurrent.duration.*

/** Per-game memory: the highest game-event version already handled (turn de-duplication) plus the time control, which
  * rides only on the Snapshot yet is needed to budget the increment on later DiceRolled turns.
  */
final private case class GameMemory(handled: Long, timeControl: Option[TimeControl])

/** A Lichess-bot-style client of the Dice Chess Bot API: it listens on the account stream, accepts incoming challenges,
  * and plays each game with the engine.
  *
  * It never needs to know which colour it holds: the move endpoint resolves the bot's seat server-side, so the bot
  * simply reacts to every dice roll by computing and submitting a move — the server applies it only when it is in fact
  * this bot's turn (off-turn submissions are harmlessly rejected).
  */
final class ReferenceBot(config: Config, client: Client[IO], supervisor: Supervisor[IO], strategy: Strategy):

  private val auth = Authorization(Credentials.Token(AuthScheme.Bearer, config.token))

  // Unary calls (challenge / accept / move) fast-fail on a short timeout; the base `client` stays untimed
  // (Main sets withTimeout(Inf)) for the long-lived ndjson streams.
  private def fireUnary(request: Request[IO]): IO[Unit] = client.status(request).timeout(10.seconds).void

  /** React to account events forever, with the optional opening challenge fired in the background once the account
    * stream is up (so we don't miss our own gameStart).
    */
  def run: IO[Unit] = openingChallenge.background.surround(accountEvents.compile.drain)

  private def openingChallenge: IO[Unit] =
    config.challenge.traverse_ : (team, name) =>
      // Brief delay so the account stream is subscribed before we challenge and the game starts.
      IO.sleep(2.seconds) *> IO.println(s"[refbot] challenging $team|$name") *>
        fireUnary(POST(ChallengeTarget(team, name), config.baseUri / "bot" / "challenge").putHeaders(auth))

  private def accountEvents: Stream[IO, Unit] =
    ndjson[BotEvent](Request[IO](GET, config.baseUri / "bot" / "stream" / "event").putHeaders(auth)).evalMap(handle)

  private def handle(event: BotEvent): IO[Unit] = event match
    case BotEvent.ChallengeReceived(id, _) => IO.println(s"[refbot] accepting challenge $id") *> accept(id)
    case BotEvent.GameStart(gameId)        =>
      IO.println(s"[refbot] game $gameId started") *> supervisor.supervise(playGame(gameId)).void
    case BotEvent.ChallengeDeclined(id) => IO.println(s"[refbot] challenge $id declined")

  private def accept(id: String): IO[Unit] =
    fireUnary(Request[IO](POST, config.baseUri / "bot" / "challenge" / id / "accept").putHeaders(auth))

  /** Stream one game to its terminal, submitting a move on each fresh dice roll for our turn. */
  private def playGame(gameId: String): IO[Unit] =
    Ref
      .of[IO, GameMemory](GameMemory(handled = -1L, timeControl = None))
      .flatMap: mem =>
        ndjson[GameEvent](Request[IO](GET, config.baseUri / "bot" / "game" / "stream" / gameId).putHeaders(auth))
          .evalMap(event => onGameEvent(gameId, mem, event))
          .compile
          .drain

  private def onGameEvent(gameId: String, mem: Ref[IO, GameMemory], event: GameEvent): IO[Unit] = event match
    case GameEvent.DiceRolled(v, seat, _, dfen, clocks) =>
      mem.get.flatMap(m => maybeMove(gameId, mem, v, dfen, turnClock(seat, clocks, m.timeControl)))
    case GameEvent.Snapshot(v, ps) =>
      // The time control rides only on the Snapshot; remember it so later DiceRolled turns can carry the increment.
      mem.update(_.copy(timeControl = ps.timeControl)) *>
        (if ps.dicePending then maybeMove(gameId, mem, v, ps.dfen, turnClock(ps.activeSeat, ps.clocks, ps.timeControl))
         else IO.unit)
    case GameEvent.GameEnded(_, over) =>
      IO.println(s"[refbot] game $gameId ended: ${over.result} (${over.termination})")
    case _ => IO.unit

  /** The side-to-move's clock (with the Fischer increment from the time control), or `None` for an unlimited game. */
  private def turnClock(toMove: Seat, clocks: Option[Clocks], timeControl: Option[TimeControl]): Option[TurnClock] =
    val increment = timeControl match
      case Some(TimeControl.Fischer(_, incrementSeconds)) => incrementSeconds.seconds
      case _                                              => Duration.Zero
    clocks.flatMap: c =>
      toMove match
        case Seat.White     => Some(TurnClock(c.white.millis, c.black.millis, increment))
        case Seat.Black     => Some(TurnClock(c.black.millis, c.white.millis, increment))
        case Seat.Spectator => None

  private def maybeMove(
      gameId: String,
      mem: Ref[IO, GameMemory],
      version: Long,
      dfen: String,
      clock: Option[TurnClock]
  ): IO[Unit] =
    mem
      .modify(m => if version > m.handled then (m.copy(handled = version), true) else (m, false))
      .flatMap: fresh =>
        if !fresh then IO.unit
        else
          // Run the (CPU-bound, synchronous) strategy on the blocking pool so a slow search never starves the compute
          // pool — that would stall the keep-alive on the long-lived ndjson streams and drop the connection.
          IO.blocking(strategy.chooseMoves(MoveContext(gameId, dfen, clock)))
            .flatMap:
              case None        => IO.unit // forced pass: the server advances on its own
              case Some(moves) => submitMove(gameId, moves)

  private def submitMove(gameId: String, moves: List[String]): IO[Unit] =
    IO.println(s"[refbot] game $gameId submitting $moves") *>
      fireUnary(POST(BotMove(moves), config.baseUri / "bot" / "game" / gameId / "move").putHeaders(auth))

  /** Decode an ndjson response body line-by-line; undecodable lines (e.g. keep-alives) are dropped. */
  private def ndjson[A: Decoder](request: Request[IO]): Stream[IO, A] =
    client
      .stream(request)
      .flatMap(_.body.through(fs2.text.utf8.decode).through(fs2.text.lines))
      .filter(_.nonEmpty)
      .map(decode[A])
      .collect { case Right(value) => value }
