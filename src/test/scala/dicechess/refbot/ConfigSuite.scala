package dicechess.refbot

import dicechess.refbot.Protocol.TimeControl

class ConfigSuite extends munit.FunSuite:

  test("parses BOT_SEEK_TIME_CONTROL correctly"):
    val cases = List(
      ("10+10", Some(TimeControl.Fischer(600, 10))),
      ("5+3", Some(TimeControl.Fischer(300, 3))),
      ("10", Some(TimeControl.SuddenDeath(600))),
      ("5", Some(TimeControl.SuddenDeath(300))),
      ("", None),
      ("   ", None)
    )

    cases.foreach { case (envValue, expected) =>
      val config = Config.fromMap(Map("BOT_SEEK_TIME_CONTROL" -> envValue))
      assertEquals(config.seekTimeControl, expected)
    }

  test("defaults to None if BOT_SEEK_TIME_CONTROL is absent"):
    val config = Config.fromMap(Map.empty)
    assertEquals(config.seekTimeControl, None)
