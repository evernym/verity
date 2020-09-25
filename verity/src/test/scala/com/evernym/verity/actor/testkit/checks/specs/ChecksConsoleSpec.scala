package com.evernym.verity.actor.testkit.checks.specs

import com.evernym.verity.actor.testkit.checks.{CheckerRegistry, ConsoleChecker, FailedCheckException}
import com.evernym.verity.testkit.BasicSpec

import com.evernym.verity.testkit._

class ChecksConsoleSpec extends BasicSpec {

  "A ConsoleChecker" - {

    val cc = new ConsoleChecker
    val cr = new CheckerRegistry(cc)

    "can detect stdout" in {
      val e = intercept[FailedCheckException] {
        cr.run {
          println("THIS IS A FAKE MESSAGE FOR TESTING (1)")
        }
      }
      e.events shouldBe Vector("THIS IS A FAKE MESSAGE FOR TESTING (1)")
    }

    "can detect stderr" in {
      val e = intercept[FailedCheckException] {
        cr.run {
          Console.err.println("THIS IS A FAKE MESSAGE FOR TESTING (2)")
        }
      }
      e.events shouldBe Vector("THIS IS A FAKE MESSAGE FOR TESTING (2)")
    }

    "can detect stdout in different threads" in {
      intercept[FailedCheckException] {
        cr.run {
          runInAnotherThread(60) {
            println("THIS IS A FAKE MESSAGE FOR TESTING (3)")
          }
        }
      }
    }

    "can detect stderr in different threads" in {
      intercept[FailedCheckException] {
        cr.run {
          runInAnotherThread(10){
            Console.err.println("THIS IS A FAKE MESSAGE FOR TESTING (4)")
          }
        }
      }
    }

    "can catch WARN log messages" in {
      intercept[FailedCheckException] {
        cr.run {
          val warnLog = """[WARN ] [21:55:47.770] [t-dispatcher-35] [i.f.SDNotify.SDNotify:<init>:42] -- Environment variable "NOTIFY_SOCKET" not set. Ignoring calls to SDNotify. THIS IS A FAKE LOG MESSAGE FOR TESTING"""
          println(warnLog)
        }
      }
    }

    "can catch ERROR log messages" in {
      intercept[FailedCheckException] {
        cr.run {
          val errorLog = """[ERROR] [21:55:47.770] [t-dispatcher-35] [i.f.SDNotify.SDNotify:<init>:42] -- Environment variable "NOTIFY_SOCKET" not set. Ignoring calls to SDNotify. THIS IS A FAKE LOG MESSAGE FOR TESTING"""
          println(errorLog)
        }
      }
    }

    "can ignore INFO, DEBUG, and TRACE log messages" in {
      cr.run {
        println("""[INFO ] [21:55:47.768] [t-dispatcher-35] [AppStateManager:logTransitionMsg:193] -- transitioned to 'Initializing' (caused by: agent-service-init-started) THIS IS A FAKE LOG MESSAGE FOR TESTING""")
        println("""[TRACE] [21:55:47.768] [t-dispatcher-35] [AppStateManager:logTransitionMsg:193] -- transitioned to 'Initializing' (caused by: agent-service-init-started) THIS IS A FAKE LOG MESSAGE FOR TESTING""")
        println("""[DEBUG] [21:55:47.768] [t-dispatcher-35] [AppStateManager:logTransitionMsg:193] -- transitioned to 'Initializing' (caused by: agent-service-init-started) THIS IS A FAKE LOG MESSAGE FOR TESTING""")
      }
    }

    "can ignore color codes in log messages" in {
      println("""[[34mINFO [0;39m] [[35m21:55:49.398[0;39m] [[33m  Thread-2[0;39m] [[36mo.h.i.s.L.n.command_executor:callback:273[0;39m] -- src/commands/mod.rs:99 | THIS IS A FAKE LOG MESSAGE FOR TESTING""")
    }

  }
}
