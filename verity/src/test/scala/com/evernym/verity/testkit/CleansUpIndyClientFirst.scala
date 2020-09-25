package com.evernym.verity.testkit

import com.evernym.verity.testkit.util.TestUtil
import org.scalatest.{BeforeAndAfterAll, Suite}

trait CleansUpIndyClientFirst extends BeforeAndAfterAll { this: Suite =>

  /**
    * default value is set to true in which case it will delete wallet and pool data from `.indy_client` directory
    * sometimes it may be desired not to do so (if you are trying to run multiple test cases parallelly etc)
    * which can be achieved by overriding and setting it to false
    */
  val deleteIndyClientContents: Boolean = true

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (deleteIndyClientContents)
      TestUtil.RISKY_deleteIndyClientContents()
  }

}
