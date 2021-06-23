package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOne
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Offer, Request, Status}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, RequestCred}
import com.evernym.verity.protocol.testkit.DSL.state
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util

import scala.reflect.ClassTag
import scala.util.{Failure, Success}

abstract class IssueCredSpecBase
  extends TestsProtocolsImpl(IssueCredentialProtoDef, Option(OneToOne))
    with BasicFixtureSpec {

  lazy val config: AppConfig = new TestAppConfig()

  def createTest1CredDef: String = "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1"

  val orgName = "Acme Corp"
  val logoUrl = "https://robohash.org/234"
  val agencyVerkey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
  val agencyDidkey = "did:key:z2DXXwXqC5VKhhDVLCoZSX98Gr33w1TGfNnA3y192dsDjbv"
  val publicDid = "UmTXHz4Kf4p8XHh5MiA4PK"

  def assertStatus[T: ClassTag](from: TestEnvir): Unit = {
    from ~ Status()
    from expect state[T]
  }

  def assertProposalSent(proposalSent: State.ProposalSentLegacy): Unit = {
    proposalSent.credProposed.cred_def_id shouldBe createTest1CredDef
    proposalSent.credProposed.credential_proposal shouldBe Option(buildCredPreview())
  }

  def assertProposalSentState(env: TestEnvir): Unit = {
    val proposalSent = env expect state[State.ProposalSent]
    assertCredProposedSegment(env, proposalSent.credProposedRef)
  }

  def assertProposalReceived(proposalReceived: State.ProposalReceivedLegacy): Unit = {
    proposalReceived.credProposed.cred_def_id shouldBe createTest1CredDef
    proposalReceived.credProposed.credential_proposal shouldBe Option(buildCredPreview())
  }

  def assertProposalReceivedState(env: TestEnvir): Unit = {
    val proposalReceived = env expect state[State.ProposalReceived]
    println(s"proposalReceived: $proposalReceived")
    assertCredProposedSegment(env, proposalReceived.credProposedRef)
  }

  def assertCredProposedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[CredProposed](segmentKey) {
      case Success(Some(proposal)) =>
        proposal.credDefId shouldBe createTest1CredDef
        proposal.credentialProposal shouldBe Option(buildCredPreview().toCredPreviewObject)
      case Success(None) => throw new Exception("No item")
      case Failure(e) => throw e
    }
  }

  def assertOfferSent(offerSent: State.OfferSentLegacy): Unit = {
    assertOffer(offerSent.credOffer)
  }

  def assertOfferSentState(env: TestEnvir): Unit = {
    val offerSent = env expect state[State.OfferSent]
    println(s"offerSent: $offerSent")
    assertCredOfferedSegment(env, offerSent.credOfferRef)
  }

  def assertOfferReceived(offerReceived: State.OfferReceivedLegacy): Unit = {
    assertOffer(offerReceived.credOffer)
  }

  def assertOfferReceivedState(env: TestEnvir): Unit = {
    val offerReceived = env expect state[State.OfferReceived]
    assertCredOfferedSegment(env, offerReceived.credOfferRef)
  }

  def assertRequestSent(requestSent: State.RequestSentLegacy): Unit = {
    assertRequest(requestSent.credRequest)
  }

  def assertRequestSentState(env: TestEnvir): Unit = {
    val requestSent = env expect state[State.RequestSent]
    println(s"requestSent: $requestSent")
    assertCredRequestedSegment(env, requestSent.credRequestRef)
  }

  def assertRequestReceived(requestReceived: State.RequestReceivedLegacy): Unit = {
    assertRequest(requestReceived.credRequest)
  }

  def assertRequestReceivedState(env: TestEnvir): Unit = {
    val requestReceived = env expect state[State.RequestReceived]
    println(s"requestReceived: $requestReceived")
    assertCredRequestedSegment(env, requestReceived.credRequestRef)
  }

  def assertIssueSent(issueSent: State.CredSentLegacy): Unit = {
    assertIssuedCred(issueSent.credIssued)
  }

  def assertCredSentState(env: TestEnvir): Unit = {
    val credSent = env expect state[State.CredSent]
    println(s"credSent: $credSent")
    assertCredIssuedSegment(env, credSent.credIssuedRef)
  }

  def assertIssueReceived(issueReceived: State.CredReceivedLegacy): Unit = {
    assertIssuedCred(issueReceived.credIssued)
  }

  def assertCredReceivedState(env: TestEnvir): Unit = {
    val credReceived = env expect state[State.CredReceived]
    println(s"credReceived: $credReceived")
    assertCredReceivedSegment(env, credReceived.credIssuedRef)
  }

  def assertOffer(credOffer: OfferCred): Unit = {
    credOffer.`offers~attach`.size shouldBe 1
    credOffer.price.contains(price) shouldBe true
    val attachedOffer = credOffer.`offers~attach`.head
    attachedOffer.`@id`.value shouldBe "libindy-cred-offer-0"
    attachedOffer.`mime-type`.value shouldBe "application/json"
    attachedOffer.data.base64.nonEmpty shouldBe true
    val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedOffer.data.base64))
    dataBase64Decoded shouldBe expectedOfferAttachment
  }

  def assertCredOfferedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[CredOffered](segmentKey) {
      case Success(Some(credOffer)) =>
        credOffer.offersAttach.size shouldBe 1
        credOffer.price.contains(price) shouldBe true
        val attachedOffer = credOffer.offersAttach.head
        attachedOffer.id shouldBe "libindy-cred-offer-0"
        attachedOffer.mimeType shouldBe "application/json"
        attachedOffer.dataBase64.nonEmpty shouldBe true
        val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedOffer.dataBase64))
        dataBase64Decoded shouldBe expectedOfferAttachment
      case Success(None) => throw new Exception("No item")
      case Failure(e) => throw e
    }
  }

  def assertRequest(requestCred: RequestCred): Unit = {
    requestCred.`requests~attach`.size shouldBe 1
    val attachedRequest = requestCred.`requests~attach`.head
    attachedRequest.`@id`.value shouldBe "libindy-cred-req-0"
    attachedRequest.`mime-type`.value shouldBe "application/json"
    attachedRequest.data.base64.nonEmpty shouldBe true
    val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedRequest.data.base64))
    dataBase64Decoded shouldBe expectedReqAttachment
  }

  def assertCredRequestedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[CredRequested](segmentKey) {
      case Success(Some(requestCred)) =>
        requestCred.requestAttach.size shouldBe 1
        val attachedRequest = requestCred.requestAttach.head
        attachedRequest.id shouldBe "libindy-cred-req-0"
        attachedRequest.mimeType shouldBe "application/json"
        attachedRequest.dataBase64.nonEmpty shouldBe true
        val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedRequest.dataBase64))
        dataBase64Decoded shouldBe expectedReqAttachment
      case Success(None) => throw new Exception("No item")
      case Failure(e) => throw e
    }
  }

  def assertIssuedCred(issueCred: IssueCred): Unit = {
    issueCred.`credentials~attach`.size shouldBe 1
    val attachedCred = issueCred.`credentials~attach`.head
    attachedCred.`@id`.value shouldBe "libindy-cred-0"
    attachedCred.`mime-type`.value shouldBe "application/json"
    attachedCred.data.base64.nonEmpty shouldBe true
    val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedCred.data.base64))
  }

  def assertCredIssuedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[CredIssued](segmentKey) {
      case Success(Some(issueCred)) =>
        issueCred.credAttach.size shouldBe 1
        val attachedCred = issueCred.credAttach.head
        attachedCred.id shouldBe "libindy-cred-0"
        attachedCred.mimeType shouldBe "application/json"
        attachedCred.dataBase64.nonEmpty shouldBe true
        val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedCred.dataBase64))
      case Success(None) => throw new Exception("No item")
      case Failure(e) => throw e
    }
  }

  def assertCredReceivedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[CredIssued](segmentKey) {
      case Success(Some(issueCred)) =>
        issueCred.credAttach.size shouldBe 1
        val attachedCred = issueCred.credAttach.head
        attachedCred.id shouldBe "libindy-cred-0"
        attachedCred.mimeType shouldBe "application/json"
        attachedCred.dataBase64.nonEmpty shouldBe true
        val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedCred.dataBase64))
      case Success(None) => //nothing to do
      case Failure(e) => throw e
    }
  }

  lazy val price = "0"

  lazy val expectedOfferAttachment =
    s"""
        {
        	"schema_id": "<schema-id>",
        	"cred_def_id": "$createTest1CredDef",
        	"nonce": "nonce",
        	"key_correctness_proof" : "<key_correctness_proof>"
        }"""

  lazy val expectedReqAttachment =
    s"""
        {
          "prover_did" : <prover-DID>,
          "cred_def_id" : $createTest1CredDef,
          "blinded_ms" : <blinded_master_secret>,
          "blinded_ms_correctness_proof" : <blinded_ms_correctness_proof>,
          "nonce": <nonce>
        }"""

  def credValues: Map[String, String] = Map(
    "name" ->  "Joe",
    "age"  -> "41"
  )

  def buildCredPreview(): CredPreview = {
    IssueCredential.buildCredPreview(credValues)
  }

  def buildSendOffer(autoIssue: Option[Boolean] = None): Offer = {
    Offer(createTest1CredDef, credValues, Option(price), auto_issue=autoIssue)
  }

  def buildSendRequest(): Request = {
    Request(createTest1CredDef, Option("some-comment"))
  }
}
