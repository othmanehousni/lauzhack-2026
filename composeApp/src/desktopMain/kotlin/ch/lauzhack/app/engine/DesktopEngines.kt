package ch.lauzhack.app.engine

private val home = System.getProperty("user.home")

fun desktopEngines() = Engines("$home/Library/Application Support/Moliere/models", "$home/Library/Caches/Moliere")
