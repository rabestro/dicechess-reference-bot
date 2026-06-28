package dicechess.refbot

import cats.effect.{IO, IOApp}
import cats.effect.std.Supervisor
import org.http4s.ember.client.EmberClientBuilder

import scala.concurrent.duration.Duration

/** Boots the reference bot: read config, open an HTTP client, and run the account loop forever. */
object Main extends IOApp.Simple:

  def run: IO[Unit] =
    Config.fromEnv.flatMap: config =>
      EmberClientBuilder
        .default[IO]
        // No request timeout: the account/game feeds are long-lived ndjson streams, and ember's default
        // 60s timeout would kill an idle stream. The server's keep-alive (play-api) covers the read-idle;
        // this disables the client-side request timeout so the stream can stay open between events.
        .withTimeout(Duration.Inf)
        .build
        .use: client =>
          Supervisor[IO].use: supervisor =>
            // The reference strategy is the engine; a fork swaps in its own `Strategy` here.
            ReferenceBot(config, client, supervisor, EngineStrategy(config.algorithm)).run
