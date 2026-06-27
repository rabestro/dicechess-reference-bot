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

/** A Lichess-bot-style client of the Dice Chess Bot API: it listens on the account stream, accepts incoming challenges,
  * and plays each game with the engine.
  *
  * It never needs to know which colour it holds: the move endpoint resolves the bot's seat server-side, so the bot
  * simply reacts to every dice roll by computing and submitting a move — the server applies it only when it is in fact
  * this bot's turn (off-turn submissions are harmlessly rejected).
  */
final class ReferenceBot(config: Config, client: Client[IO], supervisor: Supervisor[IO]):

  private val algorithm = Engine.algorithm(config.algorithm)
  private val auth      = Authorization(Credentials.Token(AuthScheme.Bearer, config.token))

  /** Optionally fire an opening challenge (for bot-vs-bot demos), then react to account events forever. */
  def run: IO[Unit] = openingChallenge *> accountEvents.compile.drain

  private def openingChallenge: IO[Unit] =
    config.challenge.traverse_ : (team, name) =>
      client.status(POST(ChallengeTarget(team, name), config.baseUri / "bot" / "challenge").putHeaders(auth)).void

  private def accountEvents: Stream[IO, Unit] =
    ndjson[BotEvent](Request[IO](GET, config.baseUri / "bot" / "stream" / "event").putHeaders(auth)).evalMap(handle)

  private def handle(event: BotEvent): IO[Unit] = event match
    case BotEvent.ChallengeReceived(id, _) => accept(id)
    case BotEvent.GameStart(gameId)        => supervisor.supervise(playGame(gameId)).void
    case BotEvent.ChallengeDeclined(_)     => IO.unit

  private def accept(id: String): IO[Unit] =
    client.status(Request[IO](POST, config.baseUri / "bot" / "challenge" / id / "accept").putHeaders(auth)).void

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
    case _                                           => IO.unit

  private def maybeMove(gameId: String, handled: Ref[IO, Long], version: Long, dfen: String): IO[Unit] =
    handled
      .modify(last => if version > last then (version, true) else (last, false))
      .flatMap: fresh =>
        if !fresh then IO.unit
        else
          Engine.chooseMoves(algorithm, dfen) match
            case None        => IO.unit // forced pass: the server advances on its own
            case Some(moves) => submitMove(gameId, moves)

  private def submitMove(gameId: String, moves: List[String]): IO[Unit] =
    client.status(POST(BotMove(moves), config.baseUri / "bot" / "game" / gameId / "move").putHeaders(auth)).void

  /** Decode an ndjson response body line-by-line; undecodable lines (e.g. keep-alives) are dropped. */
  private def ndjson[A: Decoder](request: Request[IO]): Stream[IO, A] =
    client
      .stream(request)
      .flatMap(_.body.through(fs2.text.utf8.decode).through(fs2.text.lines))
      .filter(_.nonEmpty)
      .map(decode[A])
      .collect { case Right(value) => value }
