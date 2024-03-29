package com.evernym.integrationtests.e2e.sdk.vcx

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedProvision_0_7
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.WalletConfigKey
import com.evernym.sdk.vcx.VcxException
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.sdk.vcx.wallet.WalletApi
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7
import com.evernym.verity.sdk.utils.Context
import com.evernym.verity.sdk.wallet.DefaultWalletConfig
import com.evernym.verity.util.ExceptionUtil
import com.evernym.verity.vdr.Namespace
import com.typesafe.scalalogging.Logger
import org.json.{JSONArray, JSONObject}

import java.util.UUID

protected trait VcxProvision {
  def logger: Logger

  def sdkConfig: SdkConfig

  def provisionImpl(context: Context): Context = {

    context.closeWallet()

    val walletVal = context.walletConfig() match {
      case c: DefaultWalletConfig => (c.id, c.key)
      case _ => throw new UnsupportedOperationException("Only DefaultWalletConfig is support for VCX provider")
    }

    //NOTE: currently `builder` and `staging` (in below code) is pointing to the same genesis transactions,
    // but can be adjusted to point to different ones
    val poolNetworks =
      IndyPoolNetworkBuilder()
        .withNetwork("indy:sovrin", sdkConfig.verityInstance.ledgerConfig.genesisFilePath)
        .withNetwork("indy:sovrin:builder", sdkConfig.verityInstance.ledgerConfig.genesisFilePath)
        .withNetwork("indy:sovrin:staging", sdkConfig.verityInstance.ledgerConfig.genesisFilePath)
        .poolNetworks

    val provisionConfig: JSONObject = new JSONObject()
      .put("agency_url", context.verityUrl())
      .put("agency_did", context.verityPublicDID())
      .put("agency_verkey", context.verityPublicVerKey())
      .put("wallet_name", walletVal._1)
      .put("wallet_key", walletVal._2)
      .put("pool_name", UUID.randomUUID().toString)
      .put("pool_networks", poolNetworks)

    logger.debug("provisionConfig: " + provisionConfig.toString())
    val config = new JSONObject(
      UtilsApi.vcxAgentProvisionAsync(provisionConfig.toString()).get
    )

    config.put("institution_logo_url", s"https://robohash.org/${UUID.randomUUID()}.png")
    config.put("institution_name", s"Bob")
    config.put("protocol_type", "3.0")

    VcxApi.vcxInitWithConfig(config.toString).get

    ExceptionUtil.allowCauseConditionally[VcxException](_.getSdkErrorCode == 1073){
      WalletApi.deleteRecordWallet(WalletConfigKey, WalletConfigKey).get()
    }

    WalletApi.addRecordWallet(WalletConfigKey, WalletConfigKey, config.toString).get()

    context

  }

  def provision_0_7: ProvisionV0_7 = new UndefinedProvision_0_7 {
    override def provision(context: Context): Context = provisionImpl(context)
  }

  def provision_0_7(token: String): ProvisionV0_7 = new UndefinedProvision_0_7 {
    override def provision(context: Context): Context = provisionImpl(context)
  }
}

case class IndyPoolNetworkBuilder(poolNetworks: JSONArray = new JSONArray()) {

  def withNetwork(namespace: Namespace, genesisPath: String): IndyPoolNetworkBuilder = {
    withNetwork(List(namespace), genesisPath)
  }

  def withNetwork(namespaces: List[Namespace], genesisPath: String): IndyPoolNetworkBuilder = {
    val jsonObject = new JSONObject()
    val namespaceList = new JSONArray()
    namespaces.foreach(n => namespaceList.put(n))
    jsonObject.put("genesis_path", genesisPath)
    jsonObject.put("namespace_list", namespaceList)
    poolNetworks.put(jsonObject)
    this
  }
}
