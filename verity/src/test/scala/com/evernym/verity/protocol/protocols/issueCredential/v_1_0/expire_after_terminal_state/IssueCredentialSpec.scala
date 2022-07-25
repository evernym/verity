package com.evernym.verity.protocol.protocols.issueCredential.v_1_0.expire_after_terminal_state

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.decorators.PleaseAck
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.OfferCred
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0._
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableUrlShorteningAccess, MockableWalletAccess}
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

import scala.util.{Failure, Success}


class IssueCredentialSpec
  extends IssueCredSpecBase {

  override val defaultInitParams = Map(
    MY_PAIRWISE_DID -> "8XFh8yBzrpJQmNyZzgoTqB",
    THEIR_PAIRWISE_DID -> "8XFh8yBzrpJQmNyZzgoTqB",
    NAME -> orgName,
    LOGO_URL -> logoUrl,
    AGENCY_DID_VER_KEY -> agencyVerkey,
    MY_PUBLIC_DID -> publicDid,
    DATA_RETENTION_POLICY -> s"""{"expire-after-days":"360 day", "expire-after-terminal-state": true}"""
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
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder.role shouldBe Role.Holder()
        holder expect signal[Sig.Sent]
        assertProposalSentState(holder)
        assertStatus[State.ProposalSent](holder)

        issuer.role shouldBe Role.Issuer()
        issuer expect signal[Sig.AcceptProposal]
        assertProposalReceivedState(issuer)
        assertStatus[State.ProposalReceived](issuer)

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
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptProposal]

        issuer walletAccess MockableWalletAccess()

        issuer ~ buildSendOffer()
        f.checkTotalSegments(4)
        issuer expect signal[Sig.Sent]
        assertOfferSentState(issuer)
        assertStatus[State.OfferSent](issuer)

        holder expect signal[Sig.AcceptOffer]
        assertOfferReceivedState(holder)
        assertStatus[State.OfferReceived](holder)
      }
    }

    "when Holder sends Request control message" - {
      "holder and issuer should transition to RequestSent and RequestReceived state respectively" in { f =>
        //https://github.com/hyperledger/aries-rfcs/tree/bb42a6c35e0d5543718fb36dd099551ab192f7b0/features/0036-issue-credential#request-credential
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptProposal]

        issuer walletAccess MockableWalletAccess()

        issuer ~ buildSendOffer()
        f.checkTotalSegments(4)
        issuer expect signal[Sig.Sent]
        holder expect signal[Sig.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()

        holder ~ buildSendRequest()
        f.checkTotalSegments(6)
        holder expect signal[Sig.Sent]
        assertRequestSentState(holder)
        assertStatus[State.RequestSent](holder)

        issuer expect signal[Sig.AcceptRequest]
        assertRequestReceivedState(issuer)
        assertStatus[State.RequestReceived](issuer)
      }

      "if the credOffer segment on holders side has expired, error should be reported" in { f =>
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptProposal]

        issuer walletAccess MockableWalletAccess()

        issuer ~ buildSendOffer()
        f.checkTotalSegments(4)
        issuer expect signal[Sig.Sent]
        holder expect signal[Sig.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()

        // delete the stored segment (simulation of expire)
        val offerReceived = holder expect state[State.OfferReceived]
        holder.container_!.removeSegment(offerReceived.credOfferRef)
        f.checkTotalSegments(3)

        holder ~ buildSendRequest()
        f.checkTotalSegments(3)
        val pr = holder expect signal[Sig.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.expiredDataRetention
        // state is unchanged
        assertStatus[State.OfferReceived](holder)

        assertStatus[State.OfferSent](issuer)
      }
    }

    "when Issuer sends Issue control message" - {
      "issuer and holder should transition to IssueCredSent and IssueCredReceived state respectively" in { f =>
        //https://github.com/hyperledger/aries-rfcs/tree/bb42a6c35e0d5543718fb36dd099551ab192f7b0/features/0036-issue-credential#issue-credential
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptProposal]

        issuer walletAccess MockableWalletAccess()
        issuer ~ buildSendOffer()
        f.checkTotalSegments(4)
        issuer expect signal[Sig.Sent]
        holder expect signal[Sig.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()
        holder ~ buildSendRequest()
        f.checkTotalSegments(6)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptRequest]

        issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
        issuer expect signal[Sig.Sent]
        assertCredSentState(issuer)
        assertStatus[State.CredSent](issuer)

        holder expect signal[Sig.Received]
        holder expect state[State.CredReceived]
        assertStatus[State.CredReceived](holder)

        issuer expect signal[Sig.Ack]
        f.checkTotalSegments(0)
      }

      "if the credOffer segment on issuer side has expired, error should be reported" in { f =>
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptProposal]

        issuer walletAccess MockableWalletAccess()
        issuer ~ buildSendOffer()
        f.checkTotalSegments(4)
        issuer expect signal[Sig.Sent]
        holder expect signal[Sig.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()
        holder ~ buildSendRequest()
        f.checkTotalSegments(6)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptRequest]

        // delete the stored segment (simulation of expire)
        val requestReceived = issuer expect state[State.RequestReceived]
        issuer.container_!.removeSegment(requestReceived.credOfferRef)
        f.checkTotalSegments(5)

        issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
        val pr = issuer expect signal[Sig.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.expiredDataRetention

        // state is unchanged
        assertStatus[State.RequestReceived](issuer)
        assertStatus[State.RequestSent](holder)
        f.checkTotalSegments(5)
      }

      "if the credRequest segment on issuer side has expired, error should be reported" in { f =>
        f.checkTotalSegments(0)
        val (issuer, holder) = (f.alice, f.bob)

        (holder engage issuer) ~ Propose(createTest1CredDef, credValues)
        f.checkTotalSegments(2)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptProposal]

        issuer walletAccess MockableWalletAccess()
        issuer ~ buildSendOffer()
        f.checkTotalSegments(4)
        issuer expect signal[Sig.Sent]
        holder expect signal[Sig.AcceptOffer]

        holder walletAccess MockableWalletAccess()
        holder ledgerAccess MockableLedgerAccess()
        holder ~ buildSendRequest()
        f.checkTotalSegments(6)
        holder expect signal[Sig.Sent]
        issuer expect signal[Sig.AcceptRequest]

        // delete the stored segment (simulation of expire)
        val requestReceived = issuer expect state[State.RequestReceived]
        issuer.container_!.removeSegment(requestReceived.credRequestRef)
        f.checkTotalSegments(5)

        issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
        val pr = issuer expect signal[Sig.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.expiredDataRetention

        // state is unchanged
        assertStatus[State.RequestReceived](issuer)
        assertStatus[State.RequestSent](holder)
        f.checkTotalSegments(5)
      }
    }
  }

  "when Issuer do not set auto_issue in offer" - {
    "it should follow two-step issuance flow" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(None)
      f.checkTotalSegments(2)
      issuer expect signal[Sig.Sent]
      holder expect signal[Sig.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      f.checkTotalSegments(4)
      holder expect signal[Sig.Sent]
      issuer expect signal[Sig.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      f.checkTotalSegments(0)
      issuer expect signal[Sig.Sent]
      assertCredSentState(issuer)
      assertStatus[State.CredSent](issuer)

      holder expect signal[Sig.Received]
      assertCredReceivedState(holder)
      assertStatus[State.CredReceived](holder)

      issuer expect signal[Sig.Ack]
    }
  }

  "when Issuer set auto_issue in offer to FALSE" - {
    "it should follow two-step issuance flow" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(Option(false))
      f.checkTotalSegments(2)
      issuer expect signal[Sig.Sent]
      holder expect signal[Sig.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      f.checkTotalSegments(4)
      holder expect signal[Sig.Sent]
      issuer expect signal[Sig.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      f.checkTotalSegments(0)
      issuer expect signal[Sig.Sent]
      assertCredSentState(issuer)
      assertStatus[State.CredSent](issuer)

      holder expect signal[Sig.Received]
      assertCredReceivedState(holder)
      assertStatus[State.CredReceived](holder)

      issuer expect signal[Sig.Ack]
    }
  }

  "when Issuer set auto_issue in offer to TRUE" - {
    "it should follow one-step issuance flow" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(Option(true))
      f.checkTotalSegments(2)
      issuer expect signal[Sig.Sent]
      holder expect signal[Sig.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      f.checkTotalSegments(0)
      holder expect signal[Sig.Sent]

      issuer expect signal[Sig.Sent]
      assertCredSentState(issuer)
      assertStatus[State.CredSent](issuer)

      holder expect signal[Sig.Received]
      assertCredReceivedState(holder)
      assertStatus[State.CredReceived](holder)
    }
  }

  "when Issuer sends wrong message for the current state" - {
    "it should return problem-report but not change state" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      (issuer engage holder) ~ buildSendOffer(Option(false))
      f.checkTotalSegments(2)
      issuer expect signal[Sig.Sent]
      holder expect signal[Sig.AcceptOffer]

      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      holder ~ buildSendRequest()
      f.checkTotalSegments(4)
      holder expect signal[Sig.Sent]

      issuer expect signal[Sig.AcceptRequest]
      // if offer is sent in this state, problem-report is generated
      issuer ~ buildSendOffer(Option(false))
      val pr = issuer expect signal[Sig.ProblemReport]
      pr.description.code shouldBe ProblemReportCodes.unexpectedMessage
      issuer expect state[State.RequestReceived]

      // protocol continues to work normally afterwards.
      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      f.checkTotalSegments(0)
      issuer expect signal[Sig.Sent]
      assertCredSentState(issuer)
      assertStatus[State.CredSent](issuer)

      holder expect signal[Sig.Received]
      assertCredReceivedState(holder)
      assertStatus[State.CredReceived](holder)

      issuer expect signal[Sig.Ack]
    }
  }

  "when Issuer offers credential via Out-Of-Band Invitation" - {
    "it should work as expected" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      issuer ledgerAccess MockableLedgerAccess()
      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()

      issuer urlShortening MockableUrlShorteningAccess.shortened
      (issuer engage holder) ~ Offer(createTest1CredDef, credValues, Option(price), by_invitation = Some(true))
      f.checkTotalSegments(1)

      // successful shortening
      val invitation = issuer expect signal[Sig.Invitation]
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
      threadedInviteId.protoRefStr shouldBe protoDef.protoRef.toString
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
      f.checkTotalSegments(2)
      holder.expectAs(signal[Sig.AcceptOffer]) { s =>
        s.offer.credential_preview.attributes.size should not be 0
        s.offer.credential_preview.attributes.head.value shouldBe "Joe"
      }

      holder.backState.roster.selfRole_! shouldBe Role.Holder()

      holder ~ buildSendRequest()
      f.checkTotalSegments(4)
      holder expect signal[Sig.Sent]
      issuer expect signal[Sig.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      f.checkTotalSegments(0)
      issuer expect signal[Sig.Sent]
      assertCredSentState(issuer)
      assertStatus[State.CredSent](issuer)

      holder expect signal[Sig.Received]
      assertCredReceivedState(holder)
      assertStatus[State.CredReceived](holder)

      issuer expect signal[Sig.Ack]
    }
  }

  "when Issuer offers credential via Out-Of-Band Invitation but doesn't have public did" - {
    "it should work as expected" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer.initParams(defaultInitParams.updated(MY_PUBLIC_DID, ""))

      issuer walletAccess MockableWalletAccess()
      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()

      issuer urlShortening MockableUrlShorteningAccess.shortened
      (issuer engage holder) ~ Offer(createTest1CredDef, credValues, Option(price), by_invitation = Some(true))
      f.checkTotalSegments(1)

      // successful shortening
      val invitation = issuer expect signal[Sig.Invitation]
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
      threadedInviteId.protoRefStr shouldBe protoDef.protoRef.toString
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
      f.checkTotalSegments(2)
      holder.expectAs(signal[Sig.AcceptOffer]) { s =>
        s.offer.credential_preview.attributes.size should not be 0
        s.offer.credential_preview.attributes.head.value shouldBe "Joe"
      }

      holder.backState.roster.selfRole_! shouldBe Role.Holder()

      holder ~ buildSendRequest()
      f.checkTotalSegments(4)
      holder expect signal[Sig.Sent]
      issuer expect signal[Sig.AcceptRequest]

      issuer ~ Issue(`~please_ack` = Option(PleaseAck()))
      f.checkTotalSegments(0)
      issuer expect signal[Sig.Sent]
      assertCredSentState(issuer)
      assertStatus[State.CredSent](issuer)

      holder expect signal[Sig.Received]
      assertCredReceivedState(holder)
      assertStatus[State.CredReceived](holder)

      issuer expect signal[Sig.Ack]
    }
  }

  "when Issuer offers credential via Out-Of-Band Invitation and shortening fails" - {
    "it should return problem report" in { f =>
      f.checkTotalSegments(0)
      val (issuer, holder) = (f.alice, f.bob)

      issuer walletAccess MockableWalletAccess()
      issuer ledgerAccess MockableLedgerAccess()
      holder walletAccess MockableWalletAccess()
      holder ledgerAccess MockableLedgerAccess()
      issuer urlShortening MockableUrlShorteningAccess.shorteningFailed

      (issuer engage holder) ~ Offer(createTest1CredDef, credValues, Option(price), by_invitation = Some(true))
      f.checkTotalSegments(1)
      issuer.backState.roster.selfRole_! shouldBe Role.Issuer()

      // failed shortening
      val problemReport = issuer expect signal[Sig.ProblemReport]
      problemReport.description.code shouldBe ProblemReportCodes.shorteningFailed

      issuer expect state[State.ProblemReported]
    }
  }

  override def assertCredIssuedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[CredIssued](segmentKey) {
      case Success(None) => //expected because of expire-after-terminal-state config
      case Success(Some(issueCred)) => throw new Exception("was not expecting item to be found in segmented state")
      case Failure(e) => throw e
    }
  }
  override def appConfig: AppConfig = new TestAppConfig()
}
