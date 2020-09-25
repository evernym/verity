package com.evernym.verity.util

import java.io.FileWriter
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import akka.testkit.TestKit

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source

object SyncViaFile {
  val checkInterval = FiniteDuration(5, TimeUnit.MILLISECONDS)

  def clearFile(path: Path): Unit = {
    val file = path.toFile
    if(path.toFile.exists) {
      file.delete()
    }
  }

  def sendViaFile(data: String, path: Path, maxDur: Option[Duration]): Unit = {
    clearFile(path)

    val writePath = Paths.get(path.toString+".writing")

    val writer = new FileWriter(writePath.toFile)
    writer.write(data)
    writer.flush()
    writer.close()

    Files.move(writePath, path)

    maxDur match {
      case Some(timeout) => TestKit.awaitCond({!path.toFile.exists()}, timeout, checkInterval)
      case None =>
    }
  }

  def receiveViaFile(path: Path, maxDur: Duration = FiniteDuration(100, TimeUnit.MILLISECONDS)): String = {
    TestKit.awaitCond({path.toFile.exists()}, maxDur, checkInterval)

    val readSource = Source.fromFile(path.toFile)
    val rtn = readSource.mkString

    clearFile(path)
    rtn
  }
}
