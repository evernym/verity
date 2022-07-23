package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.protocol.engine.Nonce
import com.evernym.vdrtools.anoncreds.Anoncreds

import scala.util.{Failure, Success, Try}

//***********************
// Proof Request Object
//
// used by: indy_prover_create_proof
//          indy_verifier_verify_proof
//          indy_prover_get_credentials_for_proof_req
//***********************
case class ProofRequest(nonce: Nonce,
                        name: String,
                        version: String,
                        requested_attributes: Map[String, ProofAttribute],
                        requested_predicates: Map[String, ProofPredicate],
                        non_revoked: Option[RevocationInterval],
                        ver: Option[String] = Some("1.0")
                       ) {
  def selfAttestOptions: List[String] =
    requested_attributes.filter(_._2.self_attest_allowed).keys.toList

  def allowsAllSelfAttested: Boolean =
    requested_attributes
      .forall(_._2.self_attest_allowed == true) && requested_predicates.isEmpty

}

case class ProofAttribute(name: Option[String],
                          names: Option[List[String]],
                          restrictions: Option[List[RestrictionsV1]],
                          non_revoked: Option[RevocationInterval],
                          self_attest_allowed: Boolean)

case class ProofPredicate(name: String,
                          p_type: String,
                          p_value: Int,
                          restrictions: Option[List[RestrictionsV1]],
                          non_revoked: Option[RevocationInterval])

case class RestrictionsV1(schema_id: Option[String],
                          schema_issuer_did: Option[String],
                          schema_name: Option[String],
                          schema_version: Option[String],
                          issuer_did: Option[String],
                          cred_def_id: Option[String])

case class RevocationInterval(from: Option[Int], to: Option[Int])

//***********************
// Available Credentials
// used by:
//          indy_prover_get_credentials_for_proof_req
//***********************

case class AvailableCredentials(attrs: Map[String, Seq[AnAttribute]],
                                predicates: Map[String, Seq[AnAttribute]])

case class AnAttribute(cred_info: CredentialInfo, interval: Option[RevocationInterval])

case class CredentialInfo(referent: String,
                          attrs: Map[String, String],
                          schema_id: String,
                          cred_def_id: String,
                          rev_reg_id: Option[Int],
                          cred_rev_id: Option[Int])

//***********************
// Credentials Used for Proof
// used by:
//          indy_prover_create_proof
//***********************

case class CredentialsUsed(self_attested_attributes: Map[String, String],
                           requested_attributes: Map[String, AttributeUsed],
                           requested_predicates: Map[String, PredicateUsed])
case class AttributeUsed(cred_id: String, revealed: Boolean, timestamp: Option[Long])
case class PredicateUsed(cred_id: String, timestamp: Option[Long])


//***********************
// Presentation of Proof
// used by:
//          indy_verifier_verify_proof
//***********************
case class ProofPresentation(requested_proof:RequestedProof2, identifiers: Seq[Identifier])
case class Identifier(schema_id: String, cred_def_id: String, rev_reg_id: Option[String], timestamp: Option[Int])
case class RequestedProof2(revealed_attrs: Map[String, RevealedAttr],
                           revealed_attr_groups: Option[Map[String, RevealedAttrGroup]],
                           self_attested_attrs: Map[String, String],
                           unrevealed_attrs: Map[String, UnrevealedAttr],
                           predicates: Map[String, Predicate])
case class RevealedAttr(sub_proof_index: Int, raw: String, encoded: String)
case class RevealedAttrGroup(sub_proof_index: Int, values: Map[String, RevealedAttrGroupValue])
case class RevealedAttrGroupValue(raw: String, encoded: String)
case class UnrevealedAttr(sub_proof_index: Int)
case class Predicate(sub_proof_index: Int)

object ProofRequestUtil {
  def requestToProofRequest(request: Ctl.Request,
                            vdrMultiLedgerSupportEnabled: Boolean): Try[ProofRequest] = {
    val nonce = Anoncreds.generateNonce().get()
    val requestedAttrs = Try(
      request.proof_attrs
      .map ( attrReferents )
      .getOrElse(Map.empty)
    )

    val requestedPred = Try(request.proof_predicates
      .map (predReferents)
      .getOrElse(Map.empty)
    )

    val requested = requestedAttrs match {
      case Success(attrs) => requestedPred.map((attrs, _))
      case Failure(exception) => Failure(exception)
    }

    requested match {
      case Success((attrs, preds)) =>
        Success(
          ProofRequest(
            nonce,
            request.name,
            "1.0",
            attrs,
            preds,
            request.revocation_interval,
            Some(calcCLProofReqVersion(vdrMultiLedgerSupportEnabled))
          )
        )
      case Failure(exception) => Failure(exception)
    }
  }

  def proposalToProofRequest(proposal: PresentationPreview,
                             name: String,
                             revocationInterval: Option[RevocationInterval],
                             vdrMultiLedgerSupportEnabled: Boolean): Try[ProofRequest] = {
    val nonce = Anoncreds.generateNonce().get()
    val requestedAttrs = Try(attrReferentsForPreview(proposal.attributes))
    val requestedPred = Try(predReferentsForPreview(proposal.predicates))

    val requested = requestedAttrs match {
      case Success(attrs) => requestedPred.map((attrs, _))
      case Failure(exception) => Failure(exception)
    }

    requested match {
      case Success((attrs, preds)) =>
        Success(
          ProofRequest(
            nonce,
            name,
            "1.0",
            attrs,
            preds,
            revocationInterval,
            Some(calcCLProofReqVersion(vdrMultiLedgerSupportEnabled))
          )
        )
      case Failure(exception) => Failure(exception)
    }
  }

  def attrReferents(list: Seq[ProofAttribute]): Map[String, ProofAttribute] = {
    val names = list
      .map{
        case ProofAttribute( Some(name), None, _, _, _) => name
        case ProofAttribute( None, Some(names), _, _, _) => names.mkString(":")
        case ProofAttribute( None, None, _, _, _) => throw new Exception("Either name or names must be defined")
        case _ => throw new Exception("Both name and names cannot both be defined")
      }

    val dups = names
      .groupBy(identity)
      .map(x=> x._1 -> x._2.size)
      .collect{case (x, y) if y > 1 => x -> y}
      .map(x => x._1 -> (1, x._2))

    names
      .foldLeft((dups, Seq[String]())){ (accum, name) =>
        val (dupsMap, nameSeq) = accum
        dupsMap.get(name) match {
          case Some((count, total)) =>
            (dupsMap + (name -> (count + 1, total))
              , nameSeq :+ s"$name[$count]")
          case None => (dupsMap, nameSeq :+ name)
        }
      }
      ._2
      .zip(list)
      .toMap
  }

  private def attrReferentsForPreview(list: Seq[PresentationPreviewAttribute]): Map[String, ProofAttribute] = {
    val names = list.map(_.name)

    val proofAttrList = list.map(attr => {
      val restrictions = attr.cred_def_id.map(credDefId =>
        List(RestrictionsV1(
          schema_id = None,
          schema_issuer_did = None,
          schema_name = None,
          schema_version = None,
          issuer_did = None,
          cred_def_id = Some(credDefId)
        ))
      )

      ProofAttribute(
        Some(attr.name),
        None,
        restrictions,
        None,
        restrictions.isEmpty
      )
    })

    val dups = names
      .groupBy(identity)
      .map(x=> x._1 -> x._2.size)
      .collect{case (x, y) if y > 1 => x -> y}
      .map(x => x._1 -> (1, x._2))

    names
      .foldLeft((dups, Seq[String]())){ (accum, name) =>
        val (dupsMap, nameSeq) = accum
        dupsMap.get(name) match {
          case Some((count, total)) =>
            (dupsMap + (name -> (count + 1, total))
              , nameSeq :+ s"$name[$count]")
          case None => (dupsMap, nameSeq :+ name)
        }
      }
      ._2
      .zip(proofAttrList)
      .toMap
  }

  private def predReferents(list: Seq[ProofPredicate]): Map[String, ProofPredicate] = {
    list
      .map (_.name)
      .zip(list)
      .toMap
  }

  private def predReferentsForPreview(list: Seq[PresentationPreviewPredicate]): Map[String, ProofPredicate] = {
    list
      .map (_.name)
      .zip{
        list.map(pred =>
          ProofPredicate(
            pred.name,
            pred.predicate,
            pred.threshold,
            Some(List(RestrictionsV1(
              schema_id = None,
              schema_issuer_did = None,
              schema_name = None,
              schema_version = None,
              issuer_did = None,
              cred_def_id = Some(pred.cred_def_id)
            ))),
            None
          )
        )
      }.toMap
  }

  private def calcCLProofReqVersion(vdrMultiLedgerSupportEnabled: Boolean): String =
    if (vdrMultiLedgerSupportEnabled) CL_PROOF_REQ_VERSION_2 else CL_PROOF_REQ_VERSION_1

  /**
   * if this is set to:
   *  "1.0": Then holder will use unqualified identifiers into the proof
   *  "2.0": Then holder will use fully-qualified identifiers into the proof
   */
  val CL_PROOF_REQ_VERSION_1 = "1.0"
  val CL_PROOF_REQ_VERSION_2 = "2.0"
}