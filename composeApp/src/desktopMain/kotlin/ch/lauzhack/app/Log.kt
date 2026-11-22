package ch.lauzhack.app

import ch.lauzhack.app.stage.HOME
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val LOGS = File(HOME, "Library/Logs/Moliere")

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** One timestamped line on standard output, and so in the log. */
fun log(line: String) = println("${LocalTime.now().format(CLOCK)}  $line")

/** Everything the app prints goes to moliere.log as well, the last run's kept as moliere.1.log. */
fun teeToLog() {
    LOGS.mkdirs()
    val file = File(LOGS, "moliere.log")
    if (file.exists()) file.renameTo(File(LOGS, "moliere.1.log"))
    val out = FileOutputStream(file)
    System.setOut(tee(System.out, out))
    System.setErr(tee(System.err, out))
}

private fun tee(a: PrintStream, b: OutputStream) = PrintStream(object : OutputStream() {
    override fun write(x: Int) { a.write(x); b.write(x) }
    override fun write(buf: ByteArray, off: Int, len: Int) { a.write(buf, off, len); b.write(buf, off, len) }
    override fun flush() { a.flush(); b.flush() }
}, true, Charsets.UTF_8)
