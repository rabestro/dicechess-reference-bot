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
      .of[IO, Long](-1L)
      .flatMap: handled =>
        ndjson[GameEvent](Request[IO](GET, config.baseUri / "bot" / "game" / "stream" / gameId).putHeaders(auth))
          .evalMap(event => onGameEvent(gameId, handled, event))
          .compile
          .drain

  private def onGameEvent(gameId: String, handled: Ref[IO, Long], event: GameEvent): IO[Unit] = event match
    case GameEvent.DiceRolled(v, _, _, dfen)         => maybeMove(gameId, handled, v, dfen)
    case GameEvent.Snapshot(v, ps) if ps.dicePending => maybeMove(gameId, handled, v, ps.dfen)
    case GameEvent.GameEnded(_, over)                =>
      IO.println(s"[refbot] game $gameId ended: ${over.result} (${over.termination})")
    case _ => IO.unit

  private def maybeMove(gameId: String, handled: Ref[IO, Long], version: Long, dfen: String): IO[Unit] =
    handled
      .modify(last => if version > last then (version, true) else (last, false))
      .flatMap: fresh =>
        if !fresh then IO.unit
        else
          // Run the (CPU-bound, synchronous) strategy on the blocking pool so a slow search never starves the compute
          // pool — that would stall the keep-alive on the long-lived ndjson streams and drop the connection.
          IO.blocking(strategy.chooseMoves(gameId, dfen))
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
