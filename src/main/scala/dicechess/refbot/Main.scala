package dicechess.refbot

import cats.effect.{IO, IOApp}
import cats.effect.std.Supervisor
import org.http4s.ember.client.EmberClientBuilder

/** Boots the reference bot: read config, open an HTTP client, and run the account loop forever. */
object Main extends IOApp.Simple:

  def run: IO[Unit] =
    Config.fromEnv.flatMap: config =>
      EmberClientBuilder
        .default[IO]
        .build
        .use: client =>
          Supervisor[IO].use: supervisor =>
            ReferenceBot(config, client, supervisor).run
