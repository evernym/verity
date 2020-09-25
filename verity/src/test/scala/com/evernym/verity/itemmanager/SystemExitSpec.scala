package com.evernym.verity.itemmanager

import java.security.Permission

import akka.testkit.TestKitBase
import org.scalatest.{BeforeAndAfterAll, Suite}

sealed case class ExitException(status: Int) extends SecurityException("System.exit() is not allowed") {
}

sealed class ExitSecurityManager extends SecurityManager {

  var exitCallCount: Int = 0

  override def checkPermission(perm: Permission): Unit = {}

  override def checkPermission(perm: Permission, context: Object): Unit = {}

  override def checkExit(status: Int): Unit = {
    super.checkExit(status)
    val ex = ExitException(status)
    exitCallCount += 1
    throw ex
  }
}


trait SystemExitSpec extends BeforeAndAfterAll {  this: TestKitBase with Suite =>

  val exitSecurityManager = new ExitSecurityManager()
  val originalSecurityManager: SecurityManager = System.getSecurityManager

  override def beforeAll(): Unit = {
    System.setSecurityManager(exitSecurityManager)
  }

  override def afterAll(): Unit = {
    System.setSecurityManager(originalSecurityManager)
  }
}
