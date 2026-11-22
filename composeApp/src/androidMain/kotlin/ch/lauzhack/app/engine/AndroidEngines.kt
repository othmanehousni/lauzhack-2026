package ch.lauzhack.app.engine

import android.content.Context
import java.io.File

fun androidEngines(context: Context) = Engines(File(context.noBackupFilesDir, "models").path, context.cacheDir.path)
