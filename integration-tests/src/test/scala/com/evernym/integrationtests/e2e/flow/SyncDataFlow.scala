package com.evernym.integrationtests.e2e.flow

import java.nio.file.Path

import com.evernym.verity.fixture.TempDir
import com.evernym.verity.testkit.BasicAsyncSpec
import com.evernym.verity.util.SyncViaFile

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait SyncDataFlow extends BasicAsyncSpec { this: TempDir =>

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
          println("dataReceived: " + dataReceived)
          assert(d == dataReceived)
        }
      }
    }
  }

}
