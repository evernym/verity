# Secret Management

How keys are managed by the software and the staff running an verity
will drastically influence our cybersecurity posture.

## Types of Secrets

1. Keys used by customer cloud agents to open wallets
2. Keys used by Evernym's cloud agent to open its wallet
3. API keys used to call Twillio, Bandwidth, Firebase, and AWS services
4. Keys used to log in to gitlab to control puppet config
5. Keys used to log in to AWS to control AWS config
6. Keys used to log in to Gandi
7. Keys used to protect backups of customer wallets
8. Keys used to sign software built and deployed at the verity
9. Keys to ssh onto a machine (note that we want to eliminate this)
10. Sensitive customer data that is not a key. For example, a genome
    or a sensitive legal contract.

## Hashicorp

Ops prefers to manage most secrets in Hashicorp Vault. This technology
is robust and mature, and it provides ways to generate temporary keys,
to revoke access in event of a breach, and so forth.

It is not clear which of the above key types fit comfortably into the
workflows that Hashicorp enables. Probably not #1 or #7 or #10.

## Bastion

Key type #9 is configured via a [bastion host](https://blog.scottlowe.org/2015/11/21/using-ssh-bastion-host/). All Evernym employees submit
their own generated Ed25519 ssh keys. Bastion then places public keys
for designated employees on the machines where they should have access.