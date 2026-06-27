ThisBuild / organization := "lv.id.jc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.3"

ThisBuild / description := "Reference bot for the Dice Chess Bot API (JVM, wraps the engine)."

// The engine artifact lives in GitHub Packages, which requires authentication even for public
// packages (read:packages scope). Same resolver/credentials trick as dicechess-play-api.
ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/rabestro/dicechess-engine-scala"

def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion = "1.6.1"
val CatsEffectVersion      = "3.6.3"
val Fs2Version             = "3.12.0"
val Http4sVersion          = "0.23.30"
val CirceVersion           = "0.14.10"
val LogbackVersion         = "1.5.18"
val MunitVersion           = "1.3.0"
val MunitCatsEffectVersion = "2.1.0"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name                := "dicechess-reference-bot",
    Compile / mainClass := Some("dicechess.refbot.Main"),
    libraryDependencies ++= Seq(
      // Game rules: the engine is the single source of truth (resolved from GitHub Packages)
      "lv.id.jc" %% "dicechess-engine-scala" % DiceChessEngineVersion,
      // Effect system + streaming
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"    % Fs2Version,
      // HTTP client + JSON (talks to the Bot API: ndjson streams + REST)
      "org.http4s" %% "http4s-ember-client" % Http4sVersion,
      "org.http4s" %% "http4s-dsl"          % Http4sVersion,
      "org.http4s" %% "http4s-circe"        % Http4sVersion,
      "io.circe"   %% "circe-core"          % CirceVersion,
      "io.circe"   %% "circe-generic"       % CirceVersion,
      "io.circe"   %% "circe-parser"        % CirceVersion,
      "ch.qos.logback" % "logback-classic"  % LogbackVersion % Runtime,
      // Testing
      "org.scalameta" %% "munit"             % MunitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test
    ),
    scalacOptions ++= Seq("-Werror", "-Wunused:all", "-deprecation", "-feature", "-explain"),
    coverageExcludedFiles := ".*Main\\.scala",
    coverageFailOnMinimum := false,
    Test / fork           := true
  )
