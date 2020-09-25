# Threat Model

## Actors

See the [list of people who have legitimate interactions with the verity](
people.md). In addition, we have:

* <font color="red">External hacker</font>

## Categories of Attack

We expect that attacks on the agency will fall into one of the following
categories:

1. Pounding (attempting to overwhelm, e.g., via DDoS)
2. Poking (attempting to bypass defenses from the outside, e.g., by
   cracking a password or social engineering)
3. Insider threats (abusing elevated access or privileged info)
4. Acts of god (hurricane, earthquake, major AWS outage)
5. Legal disasters (cease-and-desist from a particular government, a
   National Security Letter or similar insistence on privileged access)

## Specific Threats

* External hacker calls an API that doesn't authenticate, but that consumes
  resources, until resources are exhausted.
    * Protection 1: no APIs that fail to authenticate (see [ticket CO-1294](https://evernym.atlassian.net/browse/CO-1294))
    * Protection 2: make sure firewalls are locked down so calling
      externally is hard
* SYN flooding
* Internal process (possibly under exernal control) alters behavior of
  system (e.g., using DynamoDB or Twillio/Bandwidth more than normal)
  in a way that runs up costs dramatically.
* MitM of Agency verkey on ledger (make someone think they're talking to
  agency but they're not)


