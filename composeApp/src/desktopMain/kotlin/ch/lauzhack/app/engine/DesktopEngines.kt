package ch.lauzhack.app.engine

private val home = System.getProperty("user.home")

/** Another folder of models keeps its own cache: the engines' caches go by a model's file name, not its content. */
fun desktopEngines(models: String? = null) =
    if (models != null) Engines(models, "$models/cache") else Engines("$home/Library/Application Support/Moliere/models", "$home/Library/Caches/Moliere")
