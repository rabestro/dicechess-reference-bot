package dicechess.refbot

import cats.effect.std.Supervisor
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import dicechess.refbot.Protocol.*
import dicechess.refbot.Protocol.given
import fs2.Stream
import io.circe.syntax.*
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.{Response, Status, Uri}

import scala.concurrent.duration.*

class ReferenceBotSuite extends munit.CatsEffectSuite:

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

  // ── withRetries (#47 review: bounded retries for the resumeGames lookup) ────

  private val notFound = UnexpectedStatus(Status.NotFound, GET, Uri.unsafeFromString("http://localhost/x"))

  test("withRetries returns success on the first attempt without retrying"):
    for
      attempts <- Ref.of[IO, Int](0)
      result   <- ReferenceBot.withRetries[Int](maxRetries = 3, initialDelay = 1.millis):
        attempts.updateAndGet(_ + 1).map(Right(_))
      count <- attempts.get
    yield
      assertEquals(result, Right(1))
      assertEquals(count, 1)

  test("withRetries retries a transient failure and succeeds once it clears"):
    for
      attempts <- Ref.of[IO, Int](0)
      result   <- ReferenceBot.withRetries[Int](maxRetries = 3, initialDelay = 1.millis):
        attempts.updateAndGet(_ + 1).map { n =>
          if n < 3 then Left(new java.io.IOException("connection reset")) else Right(n)
        }
      count <- attempts.get
    yield
      assertEquals(result, Right(3))
      assertEquals(count, 3)

  test("withRetries gives up without retrying a definitive (4xx) failure"):
    for
      attempts <- Ref.of[IO, Int](0)
      result   <- ReferenceBot.withRetries[Int](maxRetries = 3, initialDelay = 1.millis):
        attempts.updateAndGet(_ + 1).as(Left(notFound))
      count <- attempts.get
    yield
      assertEquals(result, Left(notFound))
      assertEquals(count, 1)

  test("withRetries stops after exhausting the retry budget on a persistent transient failure"):
    for
      attempts <- Ref.of[IO, Int](0)
      result   <- ReferenceBot.withRetries[Int](maxRetries = 2, initialDelay = 1.millis):
        attempts.updateAndGet(_ + 1).as(Left(new java.io.IOException("still down")))
      count <- attempts.get
    yield
      assert(result.isLeft)
      assertEquals(count, 3) // the initial attempt plus 2 retries

  // ── keepAlive (#53) ─────────────────────────────────────────────────────────

  test("keepAlive reconnects indefinitely on transient errors and normal completions"):
    for
      attempts <- Ref.of[IO, Int](0)
      done     <- IO.deferred[Unit]
      stream = attempts.updateAndGet(_ + 1).flatMap { n =>
        if n == 1 then IO.raiseError(new java.io.IOException("connection reset"))
        else if n == 2 then IO.pure(true) // normal completion
        else done.complete(()).as(false)  // third attempt, stop
      }
      fiber <- ReferenceBot.keepAlive("test")(stream).start
      _     <- done.get
      _     <- fiber.cancel
      count <- attempts.get
    yield assertEquals(count, 3)

  // ── claim (#47 review: dedup registry backing resumeGames vs GameStart) ─────

  test("claim admits the first caller for a game and rejects a concurrent second one"):
    for
      inFlight <- Ref.of[IO, Set[String]](Set.empty)
      first    <- ReferenceBot.claim(inFlight, "g1")
      second   <- ReferenceBot.claim(inFlight, "g1")
    yield
      assert(first)
      assert(!second)

  test("claim admits a game again once it has been released"):
    for
      inFlight  <- Ref.of[IO, Set[String]](Set.empty)
      _         <- ReferenceBot.claim(inFlight, "g1")
      _         <- inFlight.update(_ - "g1")
      reclaimed <- ReferenceBot.claim(inFlight, "g1")
    yield assert(reclaimed)

  // ── run wiring (#47 review: account stream must be subscribed before the recovery lookup) ──

  private val testConfig = Config(
    baseUri = Uri.unsafeFromString("http://localhost"),
    token = "test-token",
    algorithm = "greedy",
    openingBookPath = None,
    challenge = None,
    openSeeks = 0,
    seekTimeControl = None
  )

  private object NoOpStrategy extends Strategy:
    def chooseMoves(ctx: MoveContext): Option[List[String]] = None

  /** A minimal fake `Client` that records, in call order, which endpoint each request hit. `accountBody` is the raw
    * ndjson body served for the account-event stream; `games` is what `/bot/games` reports.
    */
  private def fakeClient(
      order: Ref[IO, List[String]],
      accountBody: Stream[IO, Byte],
      games: BotGames,
      gameBody: String => Stream[IO, Byte] = _ => Stream.never[IO]
  ): Client[IO] =
    val seedPath = """/bot/game/([^/]+)/seed""".r
    Client[IO] { req =>
      val path = req.uri.path.toString
      (req.method, path) match
        case (GET, p) if p.endsWith("/bot/stream/event") =>
          Resource.eval(order.update(_ :+ "account-connected")).as(Response[IO](body = accountBody))
        case (GET, p) if p.endsWith("/bot/games") =>
          Resource.eval(order.update(_ :+ "bot-games-called")).as(Response[IO](Status.Ok).withEntity(games))
        case (POST, seedPath(gameId)) =>
          Resource.eval(order.update(_ :+ s"seed:$gameId")).as(Response[IO](Status.Ok))
        case (GET, p) if p.contains("/bot/game/stream/") =>
          val gameId = p.split("/").last
          Resource.eval(order.update(_ :+ s"game-stream:$gameId")).as(Response[IO](body = gameBody(gameId)))
        case (POST, p) if p.endsWith("/bot/seeks") =>
          Resource
            .eval(order.update(_ :+ "post-seek"))
            .as(Response[IO](Status.Created).withEntity(CreatedSeek("seek1", "secret1")))
        case (GET, p) if p.startsWith("/lobby/seeks/") =>
          val seekId = p.split("/").last
          Resource
            .eval(order.update(_ :+ s"get-seek:$seekId"))
            .as(Response[IO](Status.Ok).withEntity(SeekState(matched = false, None, None)))
        case _ =>
          Resource.pure(Response[IO](Status.NotFound))
    }

  test("opens the account-stream connection before the post-restart /bot/games lookup"):
    for
      order <- Ref.of[IO, List[String]](Nil)
      client = fakeClient(order, accountBody = Stream.never[IO], games = BotGames(Nil))
      _ <- Supervisor[IO].use: supervisor =>
        val bot = ReferenceBot(testConfig, client, supervisor, NoOpStrategy)
        bot.run.start.flatMap(fiber => IO.sleep(1500.millis) *> fiber.cancel)
      recorded <- order.get
    yield assertEquals(recorded, List("account-connected", "bot-games-called"))

  test("supervises playGame at most once when resumeGames and a live GameStart name the same game"):
    val gameStartLine = Stream.emit((BotEvent.GameStart("g1"): BotEvent).asJson.noSpaces + "\n")
    val accountBody   = gameStartLine.through(fs2.text.utf8.encode) ++ Stream.never[IO]
    for
      order <- Ref.of[IO, List[String]](Nil)
      client = fakeClient(order, accountBody, games = BotGames(List(BotActiveGame("g1", Seat.White))))
      _ <- Supervisor[IO].use: supervisor =>
        val bot = ReferenceBot(testConfig, client, supervisor, NoOpStrategy)
        bot.run.start.flatMap(fiber => IO.sleep(1500.millis) *> fiber.cancel)
      recorded <- order.get
    yield assertEquals(recorded.count(_ == "seed:g1"), 1)

  test("run reconnects the account stream on a transient drop without killing in-flight games (#53)"):
    val gameStartLine = Stream.emit((BotEvent.GameStart("g1"): BotEvent).asJson.noSpaces + "\n")
    val accountBody   = gameStartLine.through(fs2.text.utf8.encode)
      ++ Stream.sleep_[IO](50.millis)
      ++ Stream.raiseError[IO](new java.io.IOException("connection reset"))

    for
      order <- Ref.of[IO, List[String]](Nil)
      client = fakeClient(order, accountBody, games = BotGames(Nil))
      _ <- Supervisor[IO].use: supervisor =>
        val bot = ReferenceBot(testConfig, client, supervisor, NoOpStrategy)
        bot.run.start.flatMap(fiber => IO.sleep(1500.millis) *> fiber.cancel)
      recorded <- order.get
    yield
      val expected = List(
        "account-connected",
        "bot-games-called",
        "seed:g1",
        "bot-games-called",
        "game-stream:g1",
        "account-connected",
        "bot-games-called"
      )
      assertEquals(recorded, expected)

  test("playGame reconnects its per-game stream on a transient drop without re-seeding (#53)"):
    val gameStartLine = Stream.emit((BotEvent.GameStart("g1"): BotEvent).asJson.noSpaces + "\n")
    val accountBody   = gameStartLine.through(fs2.text.utf8.encode) ++ Stream.never[IO]
    val gameBody = Stream.sleep_[IO](50.millis) ++ Stream.raiseError[IO](new java.io.IOException("connection reset"))

    for
      order <- Ref.of[IO, List[String]](Nil)
      client = fakeClient(order, accountBody, BotGames(Nil), _ => gameBody)
      _ <- Supervisor[IO].use: supervisor =>
        val bot = ReferenceBot(testConfig, client, supervisor, NoOpStrategy)
        bot.run.start.flatMap(fiber => IO.sleep(1500.millis) *> fiber.cancel)
      recorded <- order.get
    yield
      val expected = List(
        "account-connected",
        "bot-games-called",
        "seed:g1",
        "bot-games-called",
        "game-stream:g1",
        "game-stream:g1"
      )
      assertEquals(recorded, expected)

  test("playGame terminates the stream and does not reconnect on GameEnded (#54)"):
    val gameStartLine = Stream.emit((BotEvent.GameStart("g1"): BotEvent).asJson.noSpaces + "\n")
    val accountBody   = gameStartLine.through(fs2.text.utf8.encode) ++ Stream.never[IO]

    val gameEnded = GameEvent.GameEnded(1L, GameOver(GameResult.Win(Side.Black), Termination.Resign))
    val gameBody  = Stream.emit(gameEnded.asJson.noSpaces + "\n").through(fs2.text.utf8.encode) ++ Stream.never[IO]

    for
      order <- Ref.of[IO, List[String]](Nil)
      client = fakeClient(order, accountBody, BotGames(Nil), _ => gameBody)
      _ <- Supervisor[IO].use: supervisor =>
        val bot = ReferenceBot(testConfig, client, supervisor, NoOpStrategy)
        bot.run.start.flatMap(fiber => IO.sleep(1500.millis) *> fiber.cancel)
      recorded <- order.get
    yield
      val expected = List(
        "account-connected",
        "bot-games-called",
        "seed:g1",
        "bot-games-called",
        "game-stream:g1"
      )
      assertEquals(recorded, expected)

  test("seekKeeper posts a seek when open pool is empty, and polls it on subsequent ticks"):
    val accountBody = Stream.never[IO]
    for
      order <- Ref.of[IO, List[String]](Nil)
      client = fakeClient(order, accountBody, BotGames(Nil))
      _ <- Supervisor[IO].use: supervisor =>
        val bot = ReferenceBot(testConfig.copy(openSeeks = 1), client, supervisor, NoOpStrategy)
        bot.run.start.flatMap(fiber => IO.sleep(3000.millis) *> fiber.cancel)
      recorded <- order.get
    yield
      // The first tick topUpSeeks creates a seek ("post-seek").
      // Since IO.sleep(2.seconds) happens between ticks, 3000.millis allows at least 1 tick.
      // We don't wait 45 seconds for the second tick to avoid a slow test.
      assert(recorded.contains("post-seek"))
