package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7

import com.evernym.verity.util2.{Base64Encoded, ExecutionContextProvider}
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOneDomain
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{NoSponsor, _}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.State.{AgentCreated => AgentCreatedState, _}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, HasTestWalletAPI}
import com.evernym.verity.util.TimeUtil.{longToDateString, now}
import com.evernym.verity.util.Base64Util.getBase64Encoded
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.language.{implicitConversions, reflectiveCalls}


class AgentProvisioningSpec
  extends TestsProtocolsImpl(AgentProvisioningDefinition, Option(OneToOneDomain))
    with BasicFixtureSpec
    with HasTestWalletAPI {

  import TestingVars._

  private implicit def EnhancedScenario(s: Scenario) = new {
    val requester: TestEnvir = s(REQUESTER)
    val provisioner: TestEnvir = s(PROVISIONER)
  }

  override val defaultInitParams = Map(
    DATA_RETENTION_POLICY -> "30 day"
  )

  "AgentProvisioning 0.7 Protocol Definition" - {
    "should have two roles" in { _ =>
      AgentProvisioningDefinition.roles.size shouldBe 2
      AgentProvisioningDefinition.roles shouldBe Set(Requester, Provisioner)
    }
  }

  "A CreatingAgent msg" - {
    "should signal SponsorNeeded and change state to WaitingForSponsor" in {s =>
      interaction (s.requester, s.provisioner) {
        createCloudAgent(s)
      }
    }

    "should validate and signal ProvisioningNeeded if sponsor found" in {s =>
      interaction (s.requester, s.provisioner) {
        withDefaultWalletAccess(s, {
          createCloudAgent(s)

          giveSponsor(s)
        })
      }
    }

    "should send AgentCreated once provisioned" in {s =>
      interaction (s.requester, s.provisioner) {
        withDefaultWalletAccess(s, {
          createCloudAgent(s)
          giveSponsor(s)
          giveAgentDetails(s)
        })
      }
    }

    "should create agent when NoSponsorNeeded" in {s =>
      interaction (s.requester, s.provisioner) {
        withDefaultWalletAccess(s, {
          createCloudAgent(s)
          giveSponsor(s, NoSponsorNeeded())
          giveAgentDetails(s)
        })
      }
    }

    "should create edge agent" in {s =>
      interaction (s.requester, s.provisioner) {
        withDefaultWalletAccess(s, {
          createEdgeAgent(s, requesterVk=VK)
          s.provisioner ~ NoSponsorNeeded()
          s.provisioner expect signal [NeedsEdgeAgent]
          s.provisioner.state shouldBe a[Provisioning]
          giveAgentDetails(s)
        })
      }
    }
  }

  "AgentProvisioning sdk verification" - {
    "should fail if sponsor is inactive" in { s =>

      interaction (s.requester, s.provisioner) {
          createCloudAgent(s)

          s.provisioner ~ GiveSponsorDetails(sponsorDetails(active=false), cacheUsedTokens = false, SLIDING_TIME_WINDOW)
          assertFailedState(s.provisioner.state, SponsorInactive.err)
      }
    }

    "should fail if sponsor is not found" in { s =>

      interaction (s.requester, s.provisioner) {
        createCloudAgent(s)
        s.provisioner ~ GiveSponsorDetails(None, cacheUsedTokens = false, SLIDING_TIME_WINDOW)
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, NoSponsor.err)
      }
    }

    "should fail if requester information not provided" in { s =>

      interaction (s.requester, s.provisioner) {
        createCloudAgent(s, None)
        s.provisioner ~ InvalidToken()
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, MissingToken.err)
      }
    }

    "should fail if requester information provides incorrect sponsor vk" in { s =>

      interaction (s.requester, s.provisioner) {
        createCloudAgent(s, token(sponsorVerKey = "NOT HERE"))
        s.provisioner ~ InvalidToken()
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, MissingToken.err)
      }
    }

    "should fail verifying the signature when the nonce is changed" in { s =>
      interaction(s.requester, s.provisioner) {
        s.provisioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)
        createCloudAgent(s, token(nonce = "changed"))
        s.provisioner ~ GiveSponsorDetails(sponsorDetails(), cacheUsedTokens = false, SLIDING_TIME_WINDOW)
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, InvalidSignature.err)
      }
    }

    "should fail verifying the signature when the customerId is changed" in { s =>
      interaction(s.requester, s.provisioner) {
        s.provisioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)
        createCloudAgent(s, token(id = "changed"))
        s.provisioner ~ GiveSponsorDetails(sponsorDetails(), cacheUsedTokens = false, SLIDING_TIME_WINDOW)
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, InvalidSignature.err)
      }
    }

    "should fail verifying the signature when the timestamp is changed" in { s =>
      interaction(s.requester, s.provisioner) {
        s.provisioner walletAccess MockableWalletAccess.alwaysVerifyAs(false)
        createCloudAgent(s, token(timestamp = (now - 200)))
        s.provisioner ~ GiveSponsorDetails(sponsorDetails(), cacheUsedTokens = false, SLIDING_TIME_WINDOW)
        s.requester.state shouldBe a[FailedAgentCreation]
      }
    }

    "should fail verifying if the timestamp is expired" in { s =>
      interaction(s.requester, s.provisioner) {
        s.requester ~ ProvisionCloudAgent(REQUESTER_KEYS, token(diffTime = (now - TIME_WINDOW)))
        s.provisioner ~ GiveSponsorDetails(sponsorDetails(), cacheUsedTokens = false, SLIDING_TIME_WINDOW)
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, ProvisionTimeout.err)
      }
    }

    "should fail verifying if the timestamp is greater than limit" in { s =>
      interaction(s.requester, s.provisioner) {
        s.requester ~ ProvisionCloudAgent(REQUESTER_KEYS, token(diffTime = (now + TIME_WINDOW )))
        s.provisioner ~ GiveSponsorDetails(sponsorDetails(), cacheUsedTokens = true, SLIDING_TIME_WINDOW)
        s.requester.state shouldBe a[FailedAgentCreation]
        assertFailedState(s.provisioner.state, ProvisionTimeout.err)
      }
    }

    //TODO: This should eventually be handled in Jackson parser - work around to get release out
    "should fail if value protocol message contains null" in { s =>
      interaction(s.requester, s.provisioner) {
        s.requester ~ ProvisionCloudAgent(REQUESTER_KEYS, token(id=null))
        assertFailedState(s.provisioner.state, "missing argName: sponseeId")
      }
    }
  }

  def assertFailedState(state: AgentProvisioningState, err: String): Unit = {
    state.asInstanceOf[FailedAgentCreation].err shouldEqual err
  }

  def giveAgentDetails(s: Scenario): Unit = {
    s.provisioner ~ CompleteAgentProvisioning(SELF_DID, AGENT_VK)
    s.provisioner.state shouldBe a[AgentCreatedState]
    s.requester.state shouldBe a[AgentCreatedState]
  }

  def giveSponsor(s: Scenario, ctl: Ctl = GiveSponsorDetails(sponsorDetails(), cacheUsedTokens = true, SLIDING_TIME_WINDOW)): Unit = {
    s.provisioner ~ ctl
    s.provisioner expect signal [NeedsCloudAgent]
    s.provisioner.state shouldBe a[Provisioning]
  }

  def assertCreateAgent(s: Scenario): Unit = {
  }

  def createEdgeAgent(s: Scenario, details: Option[ProvisionToken]=token(), requesterVk: VerKey=VK): Unit = {
    s.requester ~ ProvisionEdgeAgent(requesterVk, details)
    s.requester.state shouldBe a[RequestedToProvision]
    s.requester.role shouldBe Requester
    s.provisioner.role shouldBe Provisioner
    s.provisioner.state shouldBe a[EdgeCreationWaitingOnSponsor]
    s.provisioner expect signal [IdentifySponsor]
  }

  def createCloudAgent(s: Scenario,
                       details: Option[ProvisionToken]=token(),
                       requesterKeys: RequesterKeys=REQUESTER_KEYS): Unit = {
    s.requester ~ ProvisionCloudAgent(requesterKeys, details)
    s.requester.state shouldBe a[RequestedToProvision]
    s.requester.role shouldBe Requester
    s.provisioner.role shouldBe Provisioner
    s.provisioner.state shouldBe a[CloudWaitingOnSponsor]
    s.provisioner expect signal [IdentifySponsor]
  }

  def withDefaultWalletAccess(s: Scenario, f: => Unit): Unit = {
    s.requester walletAccess MockableWalletAccess()
    s.provisioner walletAccess MockableWalletAccess()
    f
  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.REQUESTER, TestingVars.PROVISIONER)

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}

object TestingVars {

  val REQUESTER = "requester"
  val PROVISIONER = "provisioner"
  val EXPIRATION_TIME = "2118-12-13T17:29:06+0000"
  val DID = "123"
  val VK = "56789"
  val REQUESTER_KEYS: RequesterKeys = RequesterKeys(DID, VK)
  val SELF_DID = "adgkljldjklj"
  val AGENT_VK = "kdjfldjiiiajljdfa"
  val SPONSOR_VK = "3456789abc"
  val ID = "abc"
  val SDK_OWNER = "Faber"
  val NONCE = "abc123efg"
  val TIME_WINDOW: Long = 20
  val SLIDING_TIME_WINDOW: Duration = Duration(TIME_WINDOW + "hour")
  val SIG: Base64Encoded = getBase64Encoded((NONCE + EXPIRATION_TIME + ID).getBytes)
  val ENDPOINT: String = "www.espn.com"

  def sponsorDetails(name: String=PROVISIONER,
                     persistentId: String=ID,
                     verKey: List[Keys]=List(Keys(VK)),
                     endpoint: String=ENDPOINT,
                     active: Boolean=true): Option[SponsorDetails] =
    Some(SponsorDetails(name, persistentId, verKey, endpoint, active))

  def token(diffTime: Long=0,
            id: String=ID,
            sponsorId: String=SDK_OWNER,
            nonce: String=NONCE,
            timestamp: Long=now,
            sponsorVerKey: VerKey=VK): Option[ProvisionToken] =
    Some(ProvisionToken(id, sponsorId, nonce, longToDateString(timestamp + diffTime), SIG, sponsorVerKey))
}
