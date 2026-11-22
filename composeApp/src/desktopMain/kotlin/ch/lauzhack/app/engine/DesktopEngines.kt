package ch.lauzhack.app.engine

private val home = System.getProperty("user.home")

fun desktopEngines() = Engines("$home/Library/Application Support/The Mole/models", "$home/Library/Caches/The Mole")
