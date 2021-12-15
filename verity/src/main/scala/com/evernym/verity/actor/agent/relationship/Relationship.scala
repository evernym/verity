package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.{ANYWISE_RELATIONSHIP, NO_RELATIONSHIP, NWISE_RELATIONSHIP, PAIRWISE_RELATIONSHIP, SELF_RELATIONSHIP}
import com.evernym.verity.actor.agent.relationship.RelationshipException._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import scalapb.lenses.Updatable


trait NoRelationshipType
trait SelfRelationshipType
trait PairwiseRelationshipType
trait NwiseRelationshipType
trait AnywiseRelationshipType


/**
 * There are five relationship variants, all of which extend the Relationship trait:
 * No Relationship
 * Self Relationship
 * Pairwise Relationship
 * Nwise Relationship
 * Anywise Relationship
 */

// ---------- NO ----------
// This is really the 'null' relationship. We could have used an Option of a
// Relationship, but that would require every use of a relationship to
// accommodate a None possibility. At least this way, we have an object that
// can be used generically with an expectation of a consistent relationship
// API.

object NoRelationship {
  def apply(): Relationship = Relationship(
    NO_RELATIONSHIP,
    "no relationship",
    None,
    Seq.empty
  )
}

// ---------- SELF ----------

object SelfRelationship {
  def apply(myDidDoc: Option[DidDoc] = None): Relationship = Relationship(
    SELF_RELATIONSHIP,
    "self",
    myDidDoc,
    Seq.empty
  )
  def apply(myDidDoc: DidDoc): Relationship = apply(Option(myDidDoc))
  def empty: Relationship = apply(None)
}

// ---------- PAIRWISE ----------

object PairwiseRelationship {
  def apply(name: RelationshipName, myDidDoc: Option[DidDoc] = None, theirDid: Option[DidDoc] = None): Relationship = Relationship(
    PAIRWISE_RELATIONSHIP,
    name,
    myDidDoc,
    theirDid.toSeq
  )

  def apply(name: RelationshipName, myDid: DidStr, theirDid: DidStr): Relationship = apply(
    name,
    Some(DidDoc(myDid)),
    Some(DidDoc(theirDid))
  )

  def apply(name: RelationshipName, myDidDoc: DidDoc, theirDidDoc: DidDoc): Relationship = apply(
    name,
    Option(myDidDoc),
    Option(theirDidDoc)
  )

  def empty: Relationship = apply("<empty>", None, None)

  def theirDidDoc(thoseDidDocs: Seq[DidDoc]): Option[DidDoc] = thoseDidDocs match {
    case s if s.isEmpty => None
    case s if s.length == 1 => s.headOption
    case _ => throw new MoreThanOneExistsException
  }
}

// ---------- NWISE ----------
/**
 * This relationship is not being used currently in the code base
 * but wanted to make sure the "relationship" code considers that type of scenario
 * and handles situations accordingly
 */

object NwiseRelationship {

  def apply(name: RelationshipName, myDidDoc: Option[DidDoc] = None, thoseDidDocs: Seq[DidDoc] = Seq.empty): Relationship =
    Relationship(
      NWISE_RELATIONSHIP,
      name,
      myDidDoc,
      thoseDidDocs
    )

  def theirDidDoc(thoseDidDocs: Seq[DidDoc]): Option[DidDoc] = thoseDidDocs.size match {
    case 0|1 => thoseDidDocs.headOption
    case _ => throw new MoreThanOneExistsException
  }
}

// ---------- ANYWISE ----------
// 'those' and 'their' did docs are empty because the assumption is that a
// public DID is primarily used to bootstrap pairwise or nwise relationships.

object AnywiseRelationship {
  def apply(myDidDoc: Option[DidDoc] = None): Relationship = Relationship(
    ANYWISE_RELATIONSHIP,
    "anywise",
    myDidDoc,
    Seq.empty
  )
  def apply(myDidDoc: DidDoc): Relationship = apply(Option(myDidDoc))
  def empty: Relationship = apply(None)
}



// ---------- general ----------

/**
  * General trait for relationships with helper methods
  */
trait RelationshipLike { this: Updatable[Relationship] =>
  type RelationshipName = String

  // Data members
  def name: RelationshipName
  def relationshipType: RelationshipTypeEnum
  def myDidDoc: Option[DidDoc]
  def thoseDidDocs: Seq[DidDoc]

  // derived members
  def theirDidDoc: Option[DidDoc] = relationshipType match {
    case PAIRWISE_RELATIONSHIP => PairwiseRelationship.theirDidDoc(thoseDidDocs)
    case NWISE_RELATIONSHIP    => NwiseRelationship.theirDidDoc(thoseDidDocs)
    case _                     => None
  }

  def myDid: Option[DidStr] = myDidDoc.map(_.did)
  def myDid_! : DidStr = myDidDoc_!.did
  def myDidDoc_! : DidDoc = myDidDoc getOrElse { throw new MyDidNotSetException }

  def theirDid: Option[DidStr] = theirDidDoc.map(_.did)
  def theirDid_! : DidStr = theirDidDoc_!.did
  def theirDidDoc_! : DidDoc = theirDidDoc getOrElse {
    throw new TheirDidNotSetException
  }

  def isEmpty: Boolean = myDidDoc.isEmpty && thoseDidDocs.isEmpty
  def nonEmpty: Boolean = !isEmpty
  def checkNotEmpty(): Unit = if (isEmpty) throw new RelationshipNotEmptyException

  private def authKeysByTags(didDoc: Option[DidDoc], tag: Tags): Vector[AuthorizedKeyLike] =
    didDoc.map(_.authorizedKeys_!.keys.filter(_.tags.contains(tag)).toVector).getOrElse(Vector.empty)

  def authKeyByTag(didDoc: Option[DidDoc], tag: Tags): Option[AuthorizedKeyLike] = {
    val authKeys = authKeysByTags(didDoc, tag)
    if (authKeys.isEmpty) None
    else if (authKeys.size == 1) authKeys.headOption
    else throw new RuntimeException("more than 1 auth key found with tag: " + tag)
  }

  def myDidDocAuthKeysByTag(tag: Tags): Vector[AuthorizedKeyLike] = {
    authKeysByTags(myDidDoc, tag)
  }

  def myDidDocAuthKeyByTag(tag: Tags): Option[AuthorizedKeyLike] = {
    authKeyByTag(myDidDoc, tag)
  }

  def theirDidDocAuthKeyByTag(tag: Tags): Option[AuthorizedKeyLike] = {
    authKeyByTag(theirDidDoc, tag)
  }

  def myDidDocAuthKeyById(keyId: KeyId): Option[AuthorizedKeyLike] = {
    authKey(keyId, myDidDoc)
  }

  def theirDidDocAuthKeyById(keyId: KeyId): Option[AuthorizedKeyLike] = {
    authKey(keyId, theirDidDoc)
  }

  private def authKey(keyId: KeyId, didDoc: Option[DidDoc]): Option[AuthorizedKeyLike] =
    didDoc.flatMap(_.authorizedKeys_!.keys.find(_.keyId == keyId))

  def initParams(initParamNames: Set[ParameterName]): Parameters = Parameters {
    initParamNames map {
      case SELF_ID => Parameter(SELF_ID, myDid_!)
      case OTHER_ID => Parameter(OTHER_ID, theirDid_!)
      case pn => throw new RuntimeException(s"invalid parameter name for Relationship: $pn")
    }
  }

  def updatedWithNewMyDidDoc[T <: Relationship](didDoc: DidDoc): Relationship = {
    relationshipType match {
      case NO_RELATIONSHIP => throw new RuntimeException("not supported")
      case _ => update(_.myDidDoc := didDoc)
    }
  }
}

// ---------- exceptions ----------

sealed abstract class RelationshipException(descr: String) extends RuntimeException(descr)
object RelationshipException {
  class MyDidNotSetException extends RelationshipException("DID for this relationship not yet established")
  class TheirDidNotSetException extends RelationshipException("DID for other party in this relationship not yet established")
  class MoreThanOneExistsException extends RelationshipException("more than one exists")
  class RelationshipNotEmptyException extends RelationshipException("relationship not empty")
}


trait HasRelationship {

  def relationship: Relationship

  type RelationshipExceptionMap = RelationshipException ?=> Exception

  /**
    * Override this method to add custom mapping of Exceptions.
    * This is useful when the RelationshipException thrown might be too low
    * level, and upstream code is expecting a more context-specific exception.
    * For example, myDidDoc_! might throw MyDidNotSetException, and the
    * code upstream from the caller might be expecting something like
    * AgentNotYetProvisionedException instead. Rather than making the class
    * implementing HasRelationship create wrappers for calls to Relationship
    * methods with custom exception handling, one could simply override this
    * method with a custom exception mapping.
    *
    * Example:
    * override def relationshipExceptionMap = {
    *   case MyDidNotSetException => AgentNotYetProvisionedException
    *   case TheirDidNotSetException => ConnectionNotCompleteException
    * }
    *
    */
  def relationshipExceptionMap: RelationshipExceptionMap = PartialFunction.empty

  // identity partial function for exceptions
  private def excIdent: RelationshipException ?=> RelationshipException = { case x => x }

  // Exception handler
  private lazy val excHandler: Throwable ?=> Nothing = {
    relationshipExceptionMap orElse excIdent andThen { e => throw e}
    }.asInstanceOf[PartialFunction[Throwable,Nothing]]

  private def exmap[A](f: => A): A = try f catch excHandler

  def checkRelationshipSet(): Unit = exmap { relationship.checkNotEmpty() }

}
