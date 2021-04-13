package com.evernym.verity.vault

import com.evernym.verity.constants.Constants.{WALLET_KEY_DERIVATION_METHOD, WALLET_TYPE_DEFAULT, WALLET_TYPE_MYSQL}

/**
 * interface for providing wallet configurations required during wallet creation/opening.
 */
trait WalletConfig {
  /**
   * should generate wallet configuration json (storage etc)
   *
   * @param id wallet identifier
   * @return
   */
  def buildConfig(id: String): String

  /**
   * should generate wallet credential json
   * @param encKey encryption key
   * @return
   */
  def buildCredentials(encKey: String): String
}

class DefaultWalletConfig extends WalletConfig {

  override def buildConfig(id: String): String =
    s"""{
       |"id": "$id",
       |"storage_type": "$WALLET_TYPE_DEFAULT"
       |}""".stripMargin

  override def buildCredentials(encKey: String): String = {
    s"""{
       |"key": "$encKey",
       |"storage_credentials": {},
       |"key_derivation_method":"$WALLET_KEY_DERIVATION_METHOD"
       |}""".stripMargin
  }
}

class MySqlWalletConfig(readHost: String, writeHost: String, port: Int,
                        userName: String, password: String, dbName: String,
                        connectionLimit: Option[Int]) extends WalletConfig {

  val DEFAULT_CONNECTION_LIMIT = 30

  List(readHost, writeHost, port, userName, password, dbName).foreach { i =>
    require(i != null)
  }

  override def buildConfig(id: String): String = {
    val storageConfigs =
      s"""{
         |"db_name":"$dbName",
         |"read_host":"$readHost",
         |"write_host":"$writeHost",
         |"port": $port,
         |"connection_limit": ${connectionLimit.getOrElse(DEFAULT_CONNECTION_LIMIT)}
         }""".stripMargin

    s"""{
       |"id": "$id",
       |"storage_type": "$WALLET_TYPE_MYSQL",
       |"storage_config": $storageConfigs
       }""".stripMargin
  }


  override def buildCredentials(encKey: String): String = {
    s"""{
       |"key": "$encKey",
       |"storage_credentials": {"user": "$userName", "pass": "$password"},
       |"key_derivation_method":"$WALLET_KEY_DERIVATION_METHOD"
       |}""".stripMargin
  }
}

