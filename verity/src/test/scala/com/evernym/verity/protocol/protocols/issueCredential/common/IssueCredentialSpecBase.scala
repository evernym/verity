package com.evernym.verity.protocol.protocols.issueCredential.common

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig

trait IssueCredentialSpecBase {

  lazy val config: AppConfig = new TestAppConfig()

  def createTest1CredDef: String = "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1"

}
