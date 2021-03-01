#Segmented State

A protocol's state can be Segmented when it contains a collection of similar items. 
Candidates of segmentation are those protocols that have a large state, 
but generally don't need to operate on the entire state at the same time. 
E.g., **a phone book**.
 
A **Segment** is a portion of the state, that is, one of the items in the collection. 
E.g., **PhoneBookEntry**(firstname, lastname, phonenumber)

Segments are logically divided by some key called a **Segment Key**. 
A segment key can always be extracted from a Segment. E.g., (firstname + lastname): String

##Segment Storage Strategies
Protocol designer should only provide **segment key** and strategy should 
calculate SegmentId based on it and then SegmentAddress based on SegmentId. 

**SegmentId**: A unique identifier for a segmented state to be used to 
calculate SegmentAddress

**SegmentAddress**: A SegmentAddress is derived from pinst id and SegmentId to make it unique
                    This is address where segment will be stored/looked-up

Below are different/possible **strategies**:

**Bucket_2_Legacy**: 

    SegmentKey      = firstname + lastname (just an example)
    SegmentId       = (SegmentKey.hashCode % 100).toString
    SegmentAddress  = PinstId + "-" + SegmentId

**Bucket_4**: 

    SegmentKey      = firstname + lastname  (just an example)
    SegmentId       = SafeMultiHash(SegmentKey) % 10,000
    SegmentAddress  = PinstId + "-" + SegmentId

**OneToOne**: 

    SegmentKey      = firstname + lastname  (just an example)
    SegmentId       = SafeMultiHash(SegmentKey).hex
    SegmentAddress  = PinstId + "-" + SegmentId

##Notes

A protocol should never change it's storage strategy once deployed. It is the responsibility of the system to ensure this.
For an Akka Persistence implementation, we ensure this by (TBD) 

In the future, we could support the migration of a protocol from one Storage Strategy to another; however, 
re-versioning the protocol itself is probably the better answer.

In an Akka Persistence implementation of Segmented State, SegmentAddress is used as the EntityId.
