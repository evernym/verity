package com.evernym.verity.util

import com.evernym.verity.fixture.TempDir
import com.evernym.verity.testkit.BasicAsyncSpec

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps


class SyncViaFileSpec extends BasicAsyncSpec with TempDir {
  implicit override def executionContext = scala.concurrent.ExecutionContext.Implicits.global

  "SyncViaFile" - {
    "should share data" in {
      val path = tempDir.resolve("sync.file")
      val data = "sdf"
      lazy val timeout = 10 seconds

      val f = Future {
        SyncViaFile.receiveViaFile(path, timeout)
      } map {d  => assert(data == d)}

      Future {
        SyncViaFile.sendViaFile(data, path, Option(timeout))
      }

      f
    }

    "should share data when receive looks first" in {
      val path = tempDir.resolve("sync.file")
      val data = "sdf"
      lazy val timeout = 1 seconds

      val rtn = Future {
        SyncViaFile.receiveViaFile(path, timeout)
      } map {d  => assert(data == d)}

      Future {
        SyncViaFile.sendViaFile(data, path, Option(timeout))
      }
      rtn
    }
  }
}
