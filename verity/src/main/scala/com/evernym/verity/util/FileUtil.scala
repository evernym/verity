package com.evernym.verity.util

import java.io.IOException
import java.nio.file.{FileAlreadyExistsException, Files, Path}

object FileUtil {
  def dirNotEmpty(dir: Path): Boolean = !dirIsEmpty(dir)
  def dirIsEmpty(dir: Path): Boolean = {
    if(Files.isDirectory(dir)) {
      dir.toFile.list().isEmpty
    }
    else false
  }

  def tryCreateSymlink(symLinkDir: Path)(symLinkName: String, target: Path): Unit = {
    val link = symLinkDir.resolve(symLinkName)
    try {
      Files.deleteIfExists(link)
      Files.createSymbolicLink(link, target)
    }
    catch {
      case e @ (_ : FileAlreadyExistsException | _ : IOException) => // Ignore
    }
  }
}
