package com.evernym.verity.config

import java.io.{File, PrintWriter}
import scala.io.Source

trait ConfigUtilBaseSpec {

  def withChangedConfigFileContent[T](sourceFilePath: String, oldValue:String, newValue:String, f: => T): T = {
    withChangedConfigFileContent(Set(sourceFilePath), oldValue, newValue, f)
  }

  def withChangedConfigFileContent[T](sourceFilePaths: Set[String], oldValue:String, newValue:String, f: => T): T = {
    val changedFileOutputs = sourceFilePaths.map(changeFileContent(_, oldValue, newValue))
    try {
      f
    } finally {
      changedFileOutputs.foreach { cfo =>
        cfo.backupFile.renameTo(new File(cfo.sourceFile.getAbsolutePath))
        cfo.newTempFile.delete()
      }
    }
  }

  //updates given config file and key
  //assumes given 'key' is only found in one line
  private def changeFileContent(sourceFilePath: String, oldLine: String, newLine: String): ChangedFileOutput = {
    val sourceFile = new File(sourceFilePath)
    val backupFile = new File(s"${sourceFile.getParent}/${sourceFile.getName}_back")
    val tempFile = new File(s"${sourceFile.getParent}/${sourceFile.getName}_temp")

    val source = Source.fromFile(sourceFilePath)
    val allLines = source.getLines().toList

    if (allLines.count(_.contains(oldLine)) > 1) {
      throw new RuntimeException(s"given key '$oldLine' found more than once")
    }

    writeLines(allLines, backupFile)

    val modifiedConfigLines =
      allLines
        .map { x =>
          if (x.contains(oldLine)) x.replace(oldLine, newLine)  else x
        }
    writeLines(modifiedConfigLines, tempFile)

    tempFile.renameTo(new File(sourceFilePath))
    ChangedFileOutput(sourceFile, backupFile, tempFile)
  }

  def writeLines(lines: List[String], file: File): Unit = {
    val w = new PrintWriter(file)
    lines.foreach(x => w.println(x))
    w.close()
  }
}

/**
 *
 * @param sourceFile original file to be changed
 * @param backupFile backup of the 'sourceFile', this 'backupFile' should be used
 * @param newTempFile new temporary file with the given changes
 */
case class ChangedFileOutput(sourceFile: File, backupFile: File, newTempFile: File)