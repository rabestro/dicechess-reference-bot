package dicechess.refbot

class EngineSuite extends munit.FunSuite:

  test("the greedy algorithm resolves from the engine registry"):
    // Throws (sys.error) if the engine artifact or the named algorithm is missing — proves the
    // engine dependency links and BotRegistry works.
    Engine.algorithm("greedy")
    ()

  test("an unknown algorithm fails clearly"):
    interceptMessage[RuntimeException]("unknown algorithm: nope")(Engine.algorithm("nope"))

  test("algorithm applies opening book if provided"):
    val tsv  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1 e\t1.e2e4"
    val path = java.nio.file.Files.createTempFile("test_book", ".tsv")
    java.nio.file.Files.write(path, tsv.getBytes)
    try
      val alg = Engine.algorithm("greedy", Some(path.toString))
      // It should be decorated
      assert(alg.isInstanceOf[dicechess.engine.search.OpeningBookBot])
    finally java.nio.file.Files.delete(path)

  test("algorithm fails on malformed opening book"):
    val path = java.nio.file.Files.createTempFile("test_book", ".tsv")
    java.nio.file.Files.write(path, "malformed".getBytes)
    try
      val e = intercept[RuntimeException](Engine.algorithm("greedy", Some(path.toString)))
      assert(e.getMessage.startsWith(s"Failed to parse opening book at $path"))
    finally java.nio.file.Files.delete(path)

  test("algorithm fails on non-existent opening book"):
    intercept[java.io.FileNotFoundException](Engine.algorithm("greedy", Some("/does/not/exist.tsv")))
