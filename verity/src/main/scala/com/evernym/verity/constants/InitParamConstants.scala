package com.evernym.verity.constants

import com.evernym.verity.protocol.engine.ParameterName

object InitParamConstants {

  /**
   * Constants for initialization parameters for protocols
   */

  //TODO: we are NOT supposed to use SELF_ID and OTHER_ID parameters in any agent actors
  // we did it to make some progress and test vcx flow spec related to question answer protocol testing
  // as soon as we do required changes in protocol container to initialize participants from
  // control message or protocol message, we should remove these from here.
  val SELF_ID: ParameterName = "self-id"
  val OTHER_ID: ParameterName = "other-id"

  val AGENCY_DID = "AGENCY_DID"
  val AGENCY_DID_VER_KEY = "AGENCY_DID_VER_KEY"

  val NAME = "name"
  val LOGO_URL = "logoUrl"

  val MY_PAIRWISE_DID = "userPairwiseDID"
  val MY_PAIRWISE_DID_VER_KEY = "userPairwiseDIDVerKey"
  val MY_PUBLIC_DID = "userPublicDID"
  val MY_ISSUER_DID = "issuerDid"
  val MY_SELF_REL_DID = "ownerDid"
  val THEIR_PAIRWISE_DID = "remoteEdgePairwiseDid"

  val THIS_AGENT_VER_KEY = "agentActorVerKey"
  val THIS_AGENT_WALLET_SEED = "currentAgentWalletSeed"
  val NEW_AGENT_WALLET_SEED = "newAgentWalletSeed"

  val AGENT_PROVISIONER_PARTICIPANT_ID = "agentProvisionerPartiId"
  val CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON = "createAgentEndpointSetupDetailJson"

  val CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON = "createKeyEndpointSetupDetailJson"
}
