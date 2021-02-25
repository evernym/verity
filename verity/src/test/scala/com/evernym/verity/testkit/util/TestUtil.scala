package com.evernym.verity.testkit.util

import com.evernym.verity.util.UtilBase
import org.abstractj.kalium.keys.SigningKey
import org.iq80.leveldb.util.FileUtils

object TestUtil extends UtilBase {

  def RISKY_deleteIndyClientContents(): Unit = {
    try {
      //we are using real lib-indy (not a mock version of it) and hence, each time tests run,
      //we need to clean existing data (in ~/.indy_client directory, specially wallet data)
      //still with this, the issue is, this function should be called only once and not in between of when other tests are running
      //need to find some better solution for this problem
      val userHome = System.getProperty("user.home")
      val indyClientHome = userHome + "/.indy_client"
      if (userHome != indyClientHome) {
        logger.info("about to delete indy client home directory: " + indyClientHome)
        FileUtils.deleteDirectoryContents(new java.io.File(indyClientHome))
      }
    } catch {
      case e: Exception =>
        logger.warn("error occurred during deleting indy client directory...: " + e.getMessage)
    }
  }

  def getSigningKey(seed: String): SigningKey = {
    val seedBytes = seed.getBytes.take(32)
    new SigningKey(seedBytes)
  }
}
