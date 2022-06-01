package com.evernym.verity.testkit.mock.agent

import com.evernym.verity.actor.wallet.{StoreTheirKey, TheirKeyStored}
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.StandardWalletAPI

/**
 *
 * @param myPairwiseDidPair my pairwise DID detail for connection
 * @param wa wallet api which helps in wallet operations
 * @param wap wallet access param
 */
class MockPairwiseConnDetail(val myPairwiseDidPair: DidPair)
                            (implicit val wa: StandardWalletAPI, val wap: WalletAPIParam) {

  /**
   * their pairwise DID pair
   */
  var theirPairwiseDidPair: DidPair = _

  /**
   * this is the DID which is created for the user agent pairwise actor (which is current/wrong design)
   */
  var myCloudAgentPairwiseDidPair: DidPair = _

  /**
   * this is other entity's user agent pairwise actor's DID detail
   */
  var theirCloudAgentPairwiseDidPair: DidPair = _

  var lastSentInvite: InviteDetail = _

  private def checkIfDidPairNotYetSet(didDetail: DidPair, detailType: String): Unit = {
    if (Option(didDetail).isDefined)
      throw new RuntimeException(s"$detailType already defined")
  }

  def setMyCloudAgentPairwiseDidPair(did: DidStr, verKey: VerKeyStr): Unit = {
    checkIfDidPairNotYetSet(myCloudAgentPairwiseDidPair, "myCloudAgentPairwiseDidPair")
    wa.executeSync[TheirKeyStored](StoreTheirKey(did, verKey))
    myCloudAgentPairwiseDidPair = DidPair(did, verKey)
  }

  def setTheirPairwiseDidPair(did: DidStr, verKey: VerKeyStr): Unit = {
    checkIfDidPairNotYetSet(theirPairwiseDidPair, "theirPairwiseDidPair")
    wa.executeSync[TheirKeyStored](StoreTheirKey(did, verKey))
    theirPairwiseDidPair = DidPair(did, verKey)
  }

  private def checkTheirCloudAgentPairwiseDidPairNotYetSet(): Unit = {
    if (Option(theirCloudAgentPairwiseDidPair).isDefined)
      throw new RuntimeException("theirCloudAgentPairwiseDidPair already defined")
  }

  //TODO: this should go to specific class
  def setTheirCloudAgentPairwiseDidPair(did: DidStr, verKey: VerKeyStr): Unit = {
    checkTheirCloudAgentPairwiseDidPairNotYetSet()
    wa.executeSync[TheirKeyStored](StoreTheirKey(did, verKey))
    theirCloudAgentPairwiseDidPair = DidPair(did, verKey)
  }

  //TODO: this should go to specific class
  def getEncryptParamForOthersCloudAgent: EncryptParam = {
    EncryptParam(
      Set(KeyParam.fromDID(theirCloudAgentPairwiseDidPair.did)),
      Option(KeyParam.fromDID(myPairwiseDidPair.did))
    )
  }

  override def toString: String =
    "myPairwiseDidPair: " + myPairwiseDidPair +
      ", theirPairwiseDidPair: " + theirPairwiseDidPair
}