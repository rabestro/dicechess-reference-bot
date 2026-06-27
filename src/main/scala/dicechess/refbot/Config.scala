package dicechess.refbot

import cats.effect.IO
import org.http4s.Uri

/** Runtime configuration, from the environment.
  *
  *   - `PLAY_API_BASE_URL` — the play-api base (default `http://localhost:8080`)
  *   - `BOT_TOKEN` — the bot's Bearer token (must match a `PLAY_BOT_TOKENS` entry on the server)
  *   - `BOT_ALGORITHM` — engine search algorithm (default `greedy`)
  *   - `BOT_CHALLENGE` — optional `team|name` to challenge on startup (for bot-vs-bot demos)
  */
final case class Config(baseUri: Uri, token: String, algorithm: String, challenge: Option[(String, String)])

object Config:

  def fromEnv: IO[Config] = IO:
    val base      = sys.env.getOrElse("PLAY_API_BASE_URL", "http://localhost:8080")
    val token     = sys.env.getOrElse("BOT_TOKEN", "")
    val algorithm = sys.env.getOrElse("BOT_ALGORITHM", "greedy")
    val challenge = sys.env.get("BOT_CHALLENGE").filter(_.nonEmpty).flatMap { spec =>
      spec.split('|') match
        case Array(team, name) if team.nonEmpty && name.nonEmpty => Some(team -> name)
        case _                                                   => None
    }
    Config(Uri.unsafeFromString(base), token, algorithm, challenge)
