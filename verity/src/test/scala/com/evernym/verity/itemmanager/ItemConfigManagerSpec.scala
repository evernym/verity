package com.evernym.verity.itemmanager

import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemManagerEntityId}
import com.evernym.verity.actor.itemmanager.{ItemConfigManager, ItemContainerMapper}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigFactory


class ItemConfigManagerSpec
  extends BasicSpec {

  "ItemConfigManager" - {
    "when tried to build manager and container entity id" - {
      "should respond it as expected" in {
        checkEntityIds("watcher", "v1")
        checkEntityIds("mock", "v2")
      }
    }
  }

  private def checkEntityIds(managerIdPrefix: String, version: String): Unit = {
    val watcherConfig = buildAppConfig(managerIdPrefix, version)
    val itemManagerEntityId = ItemConfigManager.versionedItemManagerEntityId(managerIdPrefix, watcherConfig)
    itemManagerEntityId shouldBe s"$managerIdPrefix-$version"
  }

  private def buildAppConfig(forManagerId: ItemManagerEntityId, withVersion: String): AppConfig = {
    val config = ConfigFactory.parseString(
      s"""
        |verity.item-manager.$forManagerId.version = $withVersion
        |verity.item-container.mapper.class = "com.evernym.verity.itemmanager.MockItemContainerMapper"
        |""".stripMargin
    )
    new TestAppConfig(Option(config), clearValidators = true)
  }
}

class MockItemContainerMapper extends ItemContainerMapper {
  def getItemContainerId(itemId: ItemId): ItemContainerEntityId = {
    itemId
  }
}