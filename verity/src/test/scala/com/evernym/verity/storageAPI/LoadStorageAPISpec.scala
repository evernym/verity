package com.evernym.verity.storageAPI

import com.evernym.verity.actor.testkit.{ActorSpec, TestAppConfig}
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.storage_services.leveldb.LeveldbAPI
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigValueFactory}


class LoadStorageAPISpec extends ActorSpec with BasicSpec {

  private def unknownStorageApiConfig(): Config = (new TestAppConfig)
    .config
    .withValue("verity.blob-store.storage-service", ConfigValueFactory.fromAnyRef("cannot find"))

  private def storageApiConfig(): Config = (new TestAppConfig)
    .config
    .withValue("verity.blob-store.storage-service", ConfigValueFactory.fromAnyRef("com.evernym.verity.storage_services.leveldb.LeveldbAPI"))
    .withValue("verity.blob-store.local-store-path", ConfigValueFactory.fromAnyRef("/tmp/verity/leveldb"))


  "StorageAPI ClassLoader" - {

    "when loading a storage API " - {
      "should succeed in loading" in {
        StorageAPI.loadFromConfig(new TestAppConfig(Some(storageApiConfig()))).asInstanceOf[LeveldbAPI]
      }

      "should fail loading unknown API" in {
        intercept[ClassNotFoundException] {
          StorageAPI.loadFromConfig(new TestAppConfig(Some(unknownStorageApiConfig())))
        }
      }
    }
  }
}
