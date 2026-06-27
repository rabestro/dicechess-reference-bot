package dicechess.refbot

class EngineSuite extends munit.FunSuite:

  test("the greedy algorithm resolves from the engine registry"):
    // Throws (sys.error) if the engine artifact or the named algorithm is missing — proves the
    // engine dependency links and BotRegistry works.
    Engine.algorithm("greedy")
    ()

  test("an unknown algorithm fails clearly"):
    interceptMessage[RuntimeException]("unknown algorithm: nope")(Engine.algorithm("nope"))
