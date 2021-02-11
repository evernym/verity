package com.evernym.integrationtests.e2e.flow

import com.evernym.verity.fixture.TempDir
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.testkit.BasicAsyncSpec
import com.evernym.verity.util.SyncViaFile
import com.typesafe.scalalogging.Logger

import java.nio.file.Path
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait SyncDataFlow extends BasicAsyncSpec { this: TempDir =>

  val logger: Logger = getLoggerByName(getClass.getName)

  var dataReceived: String = _
  lazy val timeout = 10 seconds

  def sendData(data: String, path: Path): Unit = {
    "when tried to send data" - {
      "should be able to send it successfully" in {
        Future {
          SyncViaFile.sendViaFile(data, path, Option(timeout))
        } map { _ =>
          assert(1!=2)  //TODO: temporary code
        }
      }
    }
  }

  def receiveData(path: Path): Unit = {
    "when tried to receive data" - {
      "should be able to receive it successfully" in {
        Future {
          SyncViaFile.receiveViaFile(path, timeout)
        } map { d =>
          dataReceived = d
          logger.debug("dataReceived: " + dataReceived)
          assert(d == dataReceived)
        }
      }
    }
  }

}
