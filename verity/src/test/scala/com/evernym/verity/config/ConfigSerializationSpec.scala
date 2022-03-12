package com.evernym.verity.config

import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}

class ConfigSerializationSpec
  extends BasicSpec {

  "TypeSafe config" - {
    "serialization and deserialization should be successful" in {
      val config = ConfigFactory.parseString(
        """
          | object1: {
          |  key1: "value"1
          |  object2: {
          |    key_2.0_name: value2
          |  }
          |}
          |""".stripMargin)
      config.getString("object1.key1") shouldBe "value1"
      config.getString("object1.object2.key_2.0_name") shouldBe "value2"

      val deserialized = getDeserializedConfig(config, Option("object1.object2"))
      deserialized.getString("key_2.0_name") shouldBe "value2"
    }
  }

  private def getDeserializedConfig(config: Config, path: Option[String]=None): Config = {
    val serializedConfig  = getSerializedConfig(config, path)
    ConfigFactory.parseString(serializedConfig)
  }

  private def getSerializedConfig(config: Config, path: Option[String]=None): String = {
    val targetConfig = path match {
      case Some(p) => config.getConfig(p)
      case _       => config
    }
    targetConfig.root().render(ConfigRenderOptions.concise())
  }
}
