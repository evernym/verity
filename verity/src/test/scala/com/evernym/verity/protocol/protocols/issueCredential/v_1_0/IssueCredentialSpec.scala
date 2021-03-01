package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.didcomm.decorators.PleaseAck
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, RequestCred}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableUrlShorteningAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

import scala.reflect.ClassTag


class IssueCredentialSpec
  extends TestsProtocolsImpl(IssueCredentialProtoDef)
  with BasicFixtureSpec {

  lazy val config: AppConfig = new TestAppConfig()

  def createTest1CredDef: String = "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1"

  val orgName = "Acme Corp"
  val logoUrl = "https://robohash.org/234"
  val agencyVerkey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
  val publicDid = "UmTXHz4Kf4p8XHh5MiA4PK"

  override val defaultInitParams = Map(
    MY_PAIRWISE_DID -> "8XFh8yBzrpJQmNyZzgoTqB",
    THEIR_PAIRWISE_DID -> "8XFh8yBzrpJQmNyZzgoTqB",
    NAME -> orgName,
    LOGO_URL -> logoUrl,
    AGENCY_DID_VER_KEY -> agencyVerkey,
    MY_PUBLIC_DID -> publicDid
  )

  "Credential Protocol Definition" - {
    "should have two roles" in { _ =>
      IssueCredentialProtoDef.roles.size shouldBe 2
      IssueCredentialProtoDef.roles shouldBe Set(Role.Issuer(), Role.Holder())
    }
  }

  "CredentialProtocol" - {

    "when Holder sends Propose control message" - {
      "holder and issuer should transition to ProposalSent and ProposalReceived state respectively" in { f =>
        //https://github.com/hyperledger/aries-rfcs/tree/bb42a6c35e0d5543718fb36dd099551ab192f7b0/features/0036-issue-credential#propose-credential

        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        holder.role shouldBe Role.Holder()
        holder expect signal[SignalMsg.Sent]
        val proposalSent = holder expect state[State.ProposalSent]
        assertStatus[State.ProposalSent](holder)
        assertProposalSent(proposalSent)

        issuer.role shouldBe Role.Issuer()
        issuer expect signal[SignalMsg.AcceptProposal]
        val proposalReceived = issuer expect state[State.ProposalReceived]
        assertStatus[State.ProposalReceived](issuer)
        assertProposalReceived(proposalReceived)

        issuer ~ Reject(Option("rejected received proposal"))
        issuer expect state[State.Rejected]
        assertStatus[State.Rejected](issuer)

        holder expect state[State.ProblemReported]
        assertStatus[State.ProblemReported](holder)
      }
    }

    "when Issuer sends Offer control message" - {
      "issuer and holder should transition to OfferSent and OfferReceived state respectively" in { f =>
        //https://github.com/hyperledger/aries-rfcs/tree/bb42a6c35e0d5543718fb36dd099551ab192f7b0/features/0036-issue-credential#offer-credential

        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        holder expect signal[SignalMsg.Sent]
        issuer expect signal[SignalMsg.AcceptProposal]

        issuer walletAccess MockableWalletAccess()

        issuer ~ buildSendOffer()
        issuer expect signal[SignalMsg.Sent]
        val offerSent = issuer expect state[State.OfferSent]
        assertStatus[State.OfferSent](issuer)
        assertOfferSent(offerSent)

        holder expect signal[SignalMsg.AcceptOffer]
        val offerReceived = holder expect state[State.OfferReceived]
        assertStatus[State.OfferReceived](holder)
        assertOfferReceived(offerReceived)
      }
    }

    "when Holder sends Request control message" - {
      "holder and issuer should transition to RequestSent and RequestReceived state respectively" in { f =>
        //https://github.com/hyperledger/aries-rfcs/tree/bb42a6c35e0d5543718fb36dd099551ab192f7b0/features/0036-issue-credential#request-credential

        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        holder expect signal[SignalMsg.Sent]
        issuer expect signal[SignalMsg.AcceptProposal]

        issuer walletAccess MockableWalletAccess()

        issuer ~ buildSendOffer()
        issuer expect signal[SignalMsg.Sent]
        holder expect signal[SignalMsg.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()

        holder ~ buildSendRequest()
        holder expect signal[SignalMsg.Sent]
        val reqSent = holder expect state[State.RequestSent]
        assertStatus[State.RequestSent](holder)
        assertRequestSent(reqSent)

        issuer expect signal[SignalMsg.AcceptRequest]
        val reqReceived = issuer expect state[State.RequestReceived]
        assertStatus[State.RequestReceived](issuer)
        assertRequestReceived(reqReceived)

      }
    }

    "when Issuer sends Issue control message" - {
      "issuer and holder should transition to IssueCredSent and IssueCredReceived state respectively" in { f =>
        //https://github.com/hyperledger/aries-rfcs/tree/bb42a6c35e0d5543718fb36dd099551ab192f7b0/features/0036-issue-credential#issue-credential

        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        holder expect signal[SignalMsg.Sent]
        issuer expect signal[SignalMsg.AcceptProposal]

        issuer walletAccess MockableWalletAccess()
        issuer ~ buildSendOffer()
        issuer expect signal[SignalMsg.Sent]
        holder expect signal[SignalMsg.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()
        holder ~ buildSendRequest()
        holder expect signal[SignalMsg.Sent]
        issuer expect signal[SignalMsg.AcceptRequest]

        issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
        issuer expect signal[SignalMsg.Sent]
        val issueCredSent = issuer expect state[State.IssueCredSent]
        assertStatus[State.IssueCredSent](issuer)
        assertIssueSent(issueCredSent)

        holder expect signal[SignalMsg.Received]
        val issueCredReceived = holder expect state[State.IssueCredReceived]
        assertStatus[State.IssueCredReceived](holder)
        assertIssueReceived(issueCredReceived)

        issuer expect signal[SignalMsg.Ack]
      }
    }
  }

  "when Issuer do not use auto_issue in offer (legacy)" - {
    "it should follow two-step issuance flow" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(None)
      issuer expect signal[SignalMsg.Sent]
      holder expect signal[SignalMsg.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      holder expect signal[SignalMsg.Sent]
      issuer expect signal[SignalMsg.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      issuer expect signal[SignalMsg.Sent]
      val issueCredSent = issuer expect state[State.IssueCredSent]
      assertStatus[State.IssueCredSent](issuer)
      assertIssueSent(issueCredSent)

      holder expect signal[SignalMsg.Received]
      val issueCredReceived = holder expect state[State.IssueCredReceived]
      assertStatus[State.IssueCredReceived](holder)
      assertIssueReceived(issueCredReceived)

      issuer expect signal[SignalMsg.Ack]
    }
  }

  "when Issuer set auto_issue in offer to FALSE" - {
    "it should follow two-step issuance flow" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(Option(false))
      issuer expect signal[SignalMsg.Sent]
      holder expect signal[SignalMsg.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      holder expect signal[SignalMsg.Sent]
      issuer expect signal[SignalMsg.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      issuer expect signal[SignalMsg.Sent]
      val issueCredSent = issuer expect state[State.IssueCredSent]
      assertStatus[State.IssueCredSent](issuer)
      assertIssueSent(issueCredSent)

      holder expect signal[SignalMsg.Received]
      val issueCredReceived = holder expect state[State.IssueCredReceived]
      assertStatus[State.IssueCredReceived](holder)
      assertIssueReceived(issueCredReceived)

      issuer expect signal[SignalMsg.Ack]
    }
  }

  "when Issuer set auto_issue in offer to TRUE" - {
    "it should follow one-step issuance flow" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(Option(true))
      issuer expect signal[SignalMsg.Sent]
      holder expect signal[SignalMsg.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      holder expect signal[SignalMsg.Sent]

      issuer expect signal[SignalMsg.Sent]
      val issueCredSent = issuer expect state[State.IssueCredSent]
      assertStatus[State.IssueCredSent](issuer)
      assertIssueSent(issueCredSent)

      holder expect signal[SignalMsg.Received]
      val issueCredReceived = holder expect state[State.IssueCredReceived]
      assertStatus[State.IssueCredReceived](holder)
      assertIssueReceived(issueCredReceived)
    }
  }

  "when Issuer sends wrong message for the current state" - {
    "it should return problem-report but not change state" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(Option(false))
      issuer expect signal[SignalMsg.Sent]
      holder expect signal[SignalMsg.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      holder expect signal[SignalMsg.Sent]

      issuer expect signal[SignalMsg.AcceptRequest]
      // if offer is sent in this state, problem-report is generated
      issuer ~ buildSendOffer(Option(false))
      val pr = issuer expect signal[SignalMsg.ProblemReport]
      pr.description.code shouldBe ProblemReportCodes.unexpectedMessage
      issuer expect state[State.RequestReceived]

      // protocol continues to work normally afterwards.
      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      issuer expect signal[SignalMsg.Sent]
      val issueCredSent = issuer expect state[State.IssueCredSent]
      assertStatus[State.IssueCredSent](issuer)
      assertIssueSent(issueCredSent)

      holder expect signal[SignalMsg.Received]
      val issueCredReceived = holder expect state[State.IssueCredReceived]
      assertStatus[State.IssueCredReceived](holder)
      assertIssueReceived(issueCredReceived)

      issuer expect signal[SignalMsg.Ack]
    }
  }

  "when Issuer offers credential via Out-Of-Band Invitation" - {
    "it should work as expected" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()

      issuer urlShortening MockableUrlShorteningAccess.shortened
      (issuer engage holder) ~ Offer(createTest1CredDef, credValues, Option(price), by_invitation = Some(true))

      // successful shortening
      val invitation = issuer expect signal[SignalMsg.Invitation]
      invitation.shortInviteURL shouldBe Some("http://short.url")

      invitation.inviteURL should not be empty
      val base64 = invitation.inviteURL.split("oob=")(1)
      val invite = new String(Base64Util.getBase64UrlDecoded(base64))
      val inviteObj = new JSONObject(invite)

      inviteObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation") or be ("https://didcomm.org/out-of-band/1.0/invitation"))
      inviteObj.has("@id") shouldBe true

      inviteObj.has("@id") shouldBe true
      InviteUtil.isThreadedInviteId(inviteObj.getString("@id"))
      val threadedInviteId = InviteUtil.parseThreadedInviteId(
        inviteObj.getString("@id")
      ).get
      threadedInviteId.protoRefStr shouldBe protoDef.msgFamily.protoRef.toString
      threadedInviteId.relationshipId shouldBe issuer.did_!
      threadedInviteId.threadId shouldBe issuer.currentInteraction.get.threadId.get


      inviteObj.getString("profileUrl") shouldBe logoUrl
      inviteObj.getString("label") shouldBe orgName
      inviteObj.getString("public_did") should endWith(publicDid)

      inviteObj.getJSONArray("service")
        .getJSONObject(0)
        .getJSONArray("routingKeys")
        .getString(1) shouldBe agencyVerkey

      val attachmentBase64 = inviteObj
        .getJSONArray("request~attach")
        .getJSONObject(0)
        .getJSONObject("data")
        .getString("base64")

      val attachment = new String(Base64Util.getBase64Decoded(attachmentBase64))
      val attachmentObj = new JSONObject(attachment)

      attachmentObj.getString("@id") should not be empty
      attachmentObj.getString("@type") should (be  ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer-credential") or be ("https://didcomm.org/issue-credential/1.0/offer-credential"))
      attachmentObj.getJSONObject("~thread").getString("thid") should not be empty

      val attachedOffer: OfferCred = DefaultMsgCodec.fromJson[OfferCred](attachment)

      issuer.backState.roster.selfRole_! shouldBe Role.Issuer()

      holder ~ Ctl.AttachedOffer(attachedOffer)
      holder.expectAs(signal[SignalMsg.AcceptOffer]) { s =>
        s.offer.credential_preview.attributes.size should not be 0
        s.offer.credential_preview.attributes.head.value shouldBe "Joe"
      }

      holder.backState.roster.selfRole_! shouldBe Role.Holder()

      holder ~ buildSendRequest()
      holder expect signal[SignalMsg.Sent]
      issuer expect signal[SignalMsg.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      issuer expect signal[SignalMsg.Sent]
      val issueCredSent = issuer expect state[State.IssueCredSent]
      assertStatus[State.IssueCredSent](issuer)
      assertIssueSent(issueCredSent)

      holder expect signal[SignalMsg.Received]
      val issueCredReceived = holder expect state[State.IssueCredReceived]
      assertStatus[State.IssueCredReceived](holder)
      assertIssueReceived(issueCredReceived)

      issuer expect signal[SignalMsg.Ack]
    }
  }

  "when Issuer offers credential via Out-Of-Band Invitation but doesn't have public did" - {
    "it should work as expected" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer.initParams(defaultInitParams.updated(MY_PUBLIC_DID, ""))

      issuer walletAccess MockableWalletAccess()
      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()

      issuer urlShortening MockableUrlShorteningAccess.shortened
      (issuer engage holder) ~ Offer(createTest1CredDef, credValues, Option(price), by_invitation = Some(true))

      // successful shortening
      val invitation = issuer expect signal[SignalMsg.Invitation]
      invitation.shortInviteURL shouldBe Some("http://short.url")

      invitation.inviteURL should not be empty
      val base64 = invitation.inviteURL.split("oob=")(1)
      val invite = new String(Base64Util.getBase64UrlDecoded(base64))
      val inviteObj = new JSONObject(invite)

      inviteObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation") or be ("https://didcomm.org/out-of-band/1.0/invitation"))
      inviteObj.has("@id") shouldBe true

      inviteObj.has("@id") shouldBe true
      InviteUtil.isThreadedInviteId(inviteObj.getString("@id"))
      val threadedInviteId = InviteUtil.parseThreadedInviteId(
        inviteObj.getString("@id")
      ).get
      threadedInviteId.protoRefStr shouldBe protoDef.msgFamily.protoRef.toString
      threadedInviteId.relationshipId shouldBe issuer.did_!
      threadedInviteId.threadId shouldBe issuer.currentInteraction.get.threadId.get


      inviteObj.getString("profileUrl") shouldBe logoUrl
      inviteObj.getString("label") shouldBe orgName
      inviteObj.has("public_did") shouldBe false

      inviteObj.getJSONArray("service")
        .getJSONObject(0)
        .getJSONArray("routingKeys")
        .getString(1) shouldBe agencyVerkey

      val attachmentBase64 = inviteObj
        .getJSONArray("request~attach")
        .getJSONObject(0)
        .getJSONObject("data")
        .getString("base64")

      val attachment = new String(Base64Util.getBase64Decoded(attachmentBase64))
      val attachmentObj = new JSONObject(attachment)

      attachmentObj.getString("@id") should not be empty
      attachmentObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer-credential")or be ("https://didcomm.org/issue-credential/1.0/offer-credential"))
      attachmentObj.getJSONObject("~thread").getString("thid") should not be empty

      val attachedOffer: OfferCred = DefaultMsgCodec.fromJson[OfferCred](attachment)

      issuer.backState.roster.selfRole_! shouldBe Role.Issuer()

      holder ~ Ctl.AttachedOffer(attachedOffer)
      holder.expectAs(signal[SignalMsg.AcceptOffer]) { s =>
        s.offer.credential_preview.attributes.size should not be 0
        s.offer.credential_preview.attributes.head.value shouldBe "Joe"
      }

      holder.backState.roster.selfRole_! shouldBe Role.Holder()

      holder ~ buildSendRequest()
      holder expect signal[SignalMsg.Sent]
      issuer expect signal[SignalMsg.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      issuer expect signal[SignalMsg.Sent]
      val issueCredSent = issuer expect state[State.IssueCredSent]
      assertStatus[State.IssueCredSent](issuer)
      assertIssueSent(issueCredSent)

      holder expect signal[SignalMsg.Received]
      val issueCredReceived = holder expect state[State.IssueCredReceived]
      assertStatus[State.IssueCredReceived](holder)
      assertIssueReceived(issueCredReceived)

      issuer expect signal[SignalMsg.Ack]
    }
  }

  "when Issuer offers credential via Out-Of-Band Invitation and shortening fails" - {
    "it should return problem report" in { f =>
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      issuer urlShortening MockableUrlShorteningAccess.shorteningFailed

      (issuer engage holder) ~ Offer(createTest1CredDef, credValues, Option(price), by_invitation = Some(true))
      issuer.backState.roster.selfRole_! shouldBe Role.Issuer()

      // failed shortening
      val problemReport = issuer expect signal[SignalMsg.ProblemReport]
      problemReport.description.code shouldBe ProblemReportCodes.shorteningFailed

      issuer expect state[State.ProblemReported]
    }
  }

  def assertStatus[T: ClassTag](from: TestEnvir): Unit = {
    from ~ Status()
    from expect state[T]
  }

  def assertProposalSent(proposalSent: State.ProposalSent): Unit = {
    proposalSent.credProposed.cred_def_id shouldBe createTest1CredDef
    proposalSent.credProposed.credential_proposal shouldBe Option(buildCredPreview())
  }

  def assertProposalReceived(proposalReceived: State.ProposalReceived): Unit = {
    proposalReceived.credProposed.cred_def_id shouldBe createTest1CredDef
    proposalReceived.credProposed.credential_proposal shouldBe Option(buildCredPreview())
  }

  def assertOfferSent(offerSent: State.OfferSent): Unit = {
    assertOffer(offerSent.credOffer)
  }

  def assertOfferReceived(offerReceived: State.OfferReceived): Unit = {
    assertOffer(offerReceived.credOffer)
  }

  def assertRequestSent(requestSent: State.RequestSent): Unit = {
    assertRequest(requestSent.credRequest)
  }

  def assertRequestReceived(requestReceived: State.RequestReceived): Unit = {
    assertRequest(requestReceived.credRequest)
  }

  def assertIssueSent(issueSent: State.IssueCredSent): Unit = {
    assertIssuedCred(issueSent.credIssued)
  }

  def assertIssueReceived(issueReceived: State.IssueCredReceived): Unit = {
    assertIssuedCred(issueReceived.credIssued)
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

  def assertRequest(requestCred: RequestCred): Unit = {
    requestCred.`requests~attach`.size shouldBe 1
    val attachedRequest = requestCred.`requests~attach`.head
    attachedRequest.`@id`.value shouldBe "libindy-cred-req-0"
    attachedRequest.`mime-type`.value shouldBe "application/json"
    attachedRequest.data.base64.nonEmpty shouldBe true
    val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedRequest.data.base64))
    dataBase64Decoded shouldBe expectedReqAttachment
  }

  def assertIssuedCred(issueCred: IssueCred): Unit = {
    issueCred.`credentials~attach`.size shouldBe 1
    val attachedCred = issueCred.`credentials~attach`.head
    attachedCred.`@id`.value shouldBe "libindy-cred-0"
    attachedCred.`mime-type`.value shouldBe "application/json"
    attachedCred.data.base64.nonEmpty shouldBe true
    val dataBase64Decoded = new String(Base64Util.getBase64Decoded(attachedCred.data.base64))
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
