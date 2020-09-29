package com.evernym.verity.actor.testkit

import java.util.UUID

import akka.actor.ActorRef
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.constants.Constants.CLIENT_IP_ADDRESS
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.Version
import com.evernym.verity.actor.agent.agency.{CreateKey, SetEndpoint}
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.{AgencyPublicDid, EndpointSet, agentRegion}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.Constants.MFV_0_6
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.testkit.BasicSpecBase
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.cloud_agent.{MockConsumerCloudAgent, MockEntCloudAgent}
import com.evernym.verity.testkit.mock.edge_agent.{MockConsumerEdgeAgent, MockEdgeAgent, MockEntEdgeAgent}
import com.evernym.verity.testkit.mock.remotemsgsendingsvc.MockRemoteMsgSendingSvcListener
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.UrlDetail
import org.scalatest.concurrent.Eventually

trait AgentSpecHelper
  extends MockRemoteMsgSendingSvcListener
    with ImplicitSender {
  this: BasicSpecBase
    with TestKitBase
    with Eventually =>

  def agencyAgentRegion: ActorRef
  def userAgentRegionActor: ActorRef
  def userAgentPairwiseRegionActor: ActorRef
  def singletonParentProxy: ActorRef

  def walletName: String = UUID.randomUUID.toString

  def mockAgencyAdmin: MockAgencyAdmin
  def mockEdgeAgent: MockEdgeAgent

  val agencyAgentEntityId: String = UUID.randomUUID().toString
  var agencyAgentPairwiseEntityId: String = _
  val userAgentEntityId: String = UUID.randomUUID().toString
  var userAgentPairwiseEntityId: String = _

  val unsupportedVersion: Version = "X.1"

  val connId1 = "1"
  val connId2 = "2"
  val connId3 = "3"

  def wrapAsPackedMsgParam(packedMsg: PackedMsg) = PackedMsgParam(packedMsg, reqMsgContext)

  def buildMockConsumerEdgeAgent(config: AppConfig, mockAgencyAdmin: MockAgencyAdmin): MockConsumerEdgeAgent = {
    val mcea = new MockConsumerEdgeAgent(UrlDetail("localhost:9001/agency/msg"), config)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockConsumerCloudAgent(config: AppConfig, mockAgencyAdmin: MockAgencyAdmin): MockConsumerCloudAgent = {
    val mcea = new MockConsumerCloudAgent(system, config)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockEntCloudAgent(config: AppConfig, mockAgencyAdmin: MockAgencyAdmin): MockEntCloudAgent = {
    val mcea = new MockEntCloudAgent(system, config)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockEnterpriseEdgeAgent(config: AppConfig, mockAgencyAdmin: MockAgencyAdmin): MockEntEdgeAgent = {
    val mcea = new MockEntEdgeAgent(UrlDetail("localhost:9002/agency/msg"), config)
    mcea.agencyPublicDid = Option(mockAgencyAdmin.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def reqMsgContext: ReqMsgContext = {
    val rmi = ReqMsgContext()
    rmi.append(Map(CLIENT_IP_ADDRESS -> "1.2.3.4"))
    rmi
  }

  private def expectedUnsupportedVersionMsg(typ: String,
                                            unsupportedVersion: String,
                                            supportedFromVersion: Option[String]=None,
                                            supportedToVersion: Option[String]=None) = {
    (supportedFromVersion, supportedToVersion) match {
      case (Some(fv), Some(tv)) if fv != tv =>
        s"unsupported version $unsupportedVersion for msg $typ, supported versions are $fv to $tv"
      case (fv, _) =>
        s"unsupported version $unsupportedVersion for msg $typ, supported version is ${fv.getOrElse(MFV_0_6)}"
    }
  }

  def expectUnsupportedVersion(typ: String,
                               supportedFromVersion: Option[String]=None,
                               supportedToVersion: Option[String]=None)
                              (implicit unsupportedVersion: Version) = {
    val expected = expectedUnsupportedVersionMsg(typ, unsupportedVersion, supportedFromVersion, supportedToVersion)

    expectMsgPF() {
      case HandledErrorException(_, _, Some(`expected`), _) =>
    }
  }

  def expectInviteDetail(connReqId: String): InviteDetail = expectMsgPF() {
    case inv @ InviteDetail(`connReqId`, _, _, _, _, _, _) => inv
  }

  lazy val aa: agentRegion = agentRegion(agencyAgentEntityId, agencyAgentRegion)
  lazy val ua: agentRegion = agentRegion(userAgentEntityId, userAgentRegionActor)

  def setupAgency(): AgencyPublicDid = {
    val ad = setupAgencyKey()
    setupAgencyEndPoint()
    ad
  }

  def setupAgencyKey(): AgencyPublicDid = {

    aa ! CreateKey()

    val ad = expectMsgType[AgencyPublicDid]
    mockAgencyAdmin.handleFetchAgencyKey(ad)
    ad
  }

  def setupAgencyEndPoint(): Unit = {
    aa ! SetEndpoint
    expectMsgType[EndpointSet]
  }

}
