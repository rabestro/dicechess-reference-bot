package dicechess.refbot

import cats.effect.IO
import dicechess.refbot.Protocol.TimeControl
import org.http4s.Uri

/** Runtime configuration, from the environment.
  *
  *   - `PLAY_API_BASE_URL` — the play-api base (default `http://localhost:8080`)
  *   - `BOT_TOKEN` — the bot's Bearer token (must match a `PLAY_BOT_TOKENS` entry on the server)
  *   - `BOT_ALGORITHM` — engine search algorithm (default `greedy`)
  *   - `BOT_CHALLENGE` — optional `team|name` to challenge on startup (for bot-vs-bot demos)
  *   - `BOT_OPEN_SEEKS` — standing lobby seeks to hold open so humans always find this bot (default `0` = none)
  *   - `BOT_SEEK_TIME_CONTROL` — optional seek time control, e.g. `10+10` (Fischer) or `10` (Sudden Death)
  *   - `OPENING_BOOK_PATH` — optional path to a TSV opening book file
  */
final case class Config(
    baseUri: Uri,
    token: String,
    algorithm: String,
    openingBookPath: Option[String],
    challenge: Option[(String, String)],
    openSeeks: Int,
    seekTimeControl: Option[TimeControl]
)

object Config:

  def fromMap(env: Map[String, String]): Config =
    val base      = env.getOrElse("PLAY_API_BASE_URL", "http://localhost:8080")
    val token     = env.getOrElse("BOT_TOKEN", "")
    val algorithm = env.getOrElse("BOT_ALGORITHM", "greedy")
    val openingBookPath = env.get("OPENING_BOOK_PATH").filter(_.nonEmpty)
    val challenge = env.get("BOT_CHALLENGE").filter(_.nonEmpty).flatMap { spec =>
      spec.split('|') match
        case Array(team, name) if team.nonEmpty && name.nonEmpty => Some(team -> name)
        case _                                                   => None
    }
    val openSeeks       = env.get("BOT_OPEN_SEEKS").flatMap(_.toIntOption).filter(_ > 0).getOrElse(0)
    val seekTimeControl = env.get("BOT_SEEK_TIME_CONTROL").filter(_.nonEmpty).flatMap { spec =>
      spec.trim.split('+').map(_.trim) match
        case Array(initialMin, incSec) =>
          for
            initSec <- initialMin.toIntOption.map(_ * 60)
            inc     <- incSec.toIntOption
          yield TimeControl.Fischer(initSec, inc)
        case _ =>
          spec.trim.toIntOption.map(min => TimeControl.SuddenDeath(min * 60))
    }
    Config(Uri.unsafeFromString(base), token, algorithm, openingBookPath, challenge, openSeeks, seekTimeControl)

  def fromEnv: IO[Config] = IO(fromMap(sys.env))
