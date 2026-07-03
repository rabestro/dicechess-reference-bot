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

import java.security.SecureRandom
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

  // One shared, thread-safe CSPRNG for dice seeds (reused across games rather than re-seeded per call).
  private val rng = SecureRandom()

  /** Standing-seek refresh cadence — comfortably under the server's ~2-minute bot-seek TTL. */
  private val SeekPollInterval: FiniteDuration = 45.seconds

  // Unary calls (challenge / accept / move) fast-fail on a short timeout; the base `client` stays untimed
  // (Main sets withTimeout(Inf)) for the long-lived ndjson streams.
  private def fireUnary(request: Request[IO]): IO[Unit] = client.status(request).timeout(10.seconds).void

  /** React to account events forever, with the optional opening challenge and the standing-seek keeper running in the
    * background once the account stream is up (so we don't miss our own gameStart).
    */
  def run: IO[Unit] =
    (openingChallenge.background, seekKeeper.background).tupled.surround(accountEvents.compile.drain)

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

  // ── standing lobby seeks (#14) ──────────────────────────────────────────────

  /** Keep `BOT_OPEN_SEEKS` open lobby seeks standing, so a human browsing the lobby always finds this bot to play. Each
    * tick refreshes the held seeks (the capability poll doubles as the keep-alive under the server's bot-seek TTL) and
    * tops the pool back up. A matched seek starts the game here — seek matches emit no `GameStart` on the account
    * stream; this poll IS the discovery. Everything is best-effort: a server without the seek endpoints (404) or at the
    * cap (429) just logs and retries next tick, so deploy order doesn't matter.
    */
  private def seekKeeper: IO[Unit] =
    if config.openSeeks <= 0 then IO.unit
    else
      Ref.of[IO, Map[String, String]](Map.empty).flatMap { held =>
        val tick = (refreshSeeks(held) *> topUpSeeks(held))
          .handleErrorWith(e => IO.println(s"[refbot] seek keeper tick failed (retrying): $e"))
        // Brief delay so the first tick lands after the account stream is up, then keep the pool topped forever.
        IO.sleep(2.seconds) *> (tick *> IO.sleep(SeekPollInterval)).foreverM
      }

  /** Poll every held seek: the capability read keeps it alive server-side, reports a match (start playing, drop it —
    * the next top-up posts a replacement), and a 404 (expired / cancelled / pre-seeks server) drops it too.
    */
  private def refreshSeeks(held: Ref[IO, Map[String, String]]): IO[Unit] =
    held.get.flatMap(_.toList.traverse_ { (seekId, secret) =>
      val uri = (config.baseUri / "lobby" / "seeks" / seekId).withQueryParam("secret", secret)
      fetch[SeekState](Request[IO](GET, uri).putHeaders(auth)).flatMap {
        case Right(state) if state.matched =>
          held.update(_ - seekId) *>
            state.gameId.traverse_ : gameId =>
              IO.println(s"[refbot] seek $seekId matched -> game $gameId") *>
                supervisor.supervise(playGame(gameId)).void
        case Right(_)    => IO.unit // still open; the poll refreshed its TTL
        case Left(error) =>
          IO.println(s"[refbot] seek $seekId lost ($error) — will repost") *> held.update(_ - seekId)
      }
    })

  /** Post seeks until the pool holds the configured number. Failures (a pre-seeks server, the per-bot cap) are logged
    * and retried next tick — the keeper must never crash the bot.
    */
  private def topUpSeeks(held: Ref[IO, Map[String, String]]): IO[Unit] =
    held.get.flatMap { current =>
      List.fill(config.openSeeks - current.size)(()).traverse_ { _ =>
        fetch[CreatedSeek](POST(io.circe.Json.obj(), config.baseUri / "bot" / "seeks").putHeaders(auth)).flatMap {
          case Right(created) =>
            IO.println(s"[refbot] standing seek ${created.seekId} posted") *>
              held.update(_.updated(created.seekId, created.secret))
          case Left(error) => IO.println(s"[refbot] seek create failed (retrying next tick): $error")
        }
      }
    }

  /** A unary call that needs the response body, with the same short deadline as `fireUnary`; errors as values. */
  private def fetch[A: Decoder](request: Request[IO]): IO[Either[String, A]] =
    client
      .expect[A](request)(using org.http4s.circe.jsonOf[IO, A])
      .timeout(10.seconds)
      .attempt
      .map(_.leftMap(_.toString.take(120)))

  /** Stream one game to its terminal, submitting a move on each fresh dice roll for our turn. Contributes this bot's
    * dice seed first so the server's opening-roll gate can open promptly (otherwise it waits out the grace).
    */
  private def playGame(gameId: String): IO[Unit] =
    submitSeed(gameId) *>
      Ref
        .of[IO, GameMemory](GameMemory(handled = -1L, timeControl = None))
        .flatMap: mem =>
          ndjson[GameEvent](Request[IO](GET, config.baseUri / "bot" / "game" / "stream" / gameId).putHeaders(auth))
            .evalMap(event => onGameEvent(gameId, mem, event))
            .compile
            .drain

  /** Contribute this bot's post-commit dice entropy (provably-fair, #13) before the opening roll. Best-effort: if it
    * fails, the server force-starts after its grace and this seat falls back to its id, so the game still proceeds.
    */
  private def submitSeed(gameId: String): IO[Unit] =
    randomSeed.flatMap: seed =>
      IO.println(s"[refbot] game $gameId submitting dice seed") *>
        fireUnary(POST(BotSeed(seed), config.baseUri / "bot" / "game" / gameId / "seed").putHeaders(auth))
          .handleErrorWith(e => IO.println(s"[refbot] game $gameId seed submit failed (continuing): $e"))

  /** A fresh 16-byte (128-bit) client dice seed, hex-encoded. */
  private def randomSeed: IO[String] = IO:
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString

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
