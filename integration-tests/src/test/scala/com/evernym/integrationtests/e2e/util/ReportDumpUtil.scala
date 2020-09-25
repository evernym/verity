package com.evernym.integrationtests.e2e.util

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Path, Paths}

import com.evernym.integrationtests.e2e.scenario.ApplicationAdminExt

object ReportDumpUtil {

  def dumpFilePath(aae: ApplicationAdminExt, context: String): Path = {
    Paths.get(aae.scenario.testDir.toString + "/" + context + "/" + aae.name)
  }

  def dumpData(context: String, data: String, fileName: String, aae: ApplicationAdminExt, printDumpDetail: Boolean=true): Unit = {
    val path = dumpFilePath(aae, context)
    if (! Files.exists(path)) {
      Files.createDirectories(path)
    }
    val filePath = path.toString + "/" + fileName
    dumpDataAt(context, data, filePath, printDumpDetail)
  }

  def dumpDataAt(context: String, data: String, filePath: String, printDumpDetail: Boolean=true): Unit = {
    val fileObject = new File(filePath)
    val printWriter = new PrintWriter(fileObject)
    printWriter.write(data)
    printWriter.close()
    if (printDumpDetail) {
      println(s"\n$context dump can be found here: " + filePath)
      println("\nCopy and paste below url into browser to see it there\n\n" + s"file://$filePath")
    }
  }
}
