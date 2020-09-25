# Configuration Management

## Use of AWS

Currently, our machines are using shared instances on AWS. It is not
obvious whether this will be acceptable in the long run; dedicated
hosts seems to be a stronger story, cyber-security-wise.

Production verity systems on AWS are partitioned into a different context
from AWS deployments used by engineering.

## Cattle, Not Pets

The verity will be managed with Puppet. Puppet's configuration is checked
into gitlab, with pull requests approved by a limited group of Ops staff.

Generally, our goal is to treat
servers like cattle instead of pets--meaning we build machines in a
reproducible way, according to a recipe, and we don't interact with machines
as if they had a unique personality. Rather, we treat machines as fungible,
and we use Puppet to swap a new replacement for an old, troubled machine
rather than attempting deep remediation of problems.

No hand-crafted/custom configuration of a machine will persist, and we
will actively attempt to erase such anomalies.

## OS Support

All machines in an agency will be running a recent LTS release of Ubuntu.
Typically the distro will be no more than 2.5 years old, meaning we will
begin testing of new LTS releases as they become available, and convert
infrastructure fully to the new release within about 6 months.

Machines will be configured to apply patches automatically, and to reboot
during designated maintenance windows.

## Installed Software

All machines will be configured with locale = en_US and timezone = UTC.

No extra/unnecessary services will be installed on a given machine--just
what's vital for a machine to do its assigned job, plus maybe a few
diagnostic tools for sysadmins.

A few core services may be widely installed. For example:

* AppArmor
* NTP (machines will talk to the region-local version of pool.ntp.org)

## CI/CD

Software is promoted through a CI pipeline managed by Engineering,
introduced by them into a CD pipeline managed by Ops, and eventually
published using the methodology described [here](https://docs.google.com/presentation/d/1sivQ97bvJcLch-A_IjOgqjoF-HwnlN5SnQqnyBGjKMI/edit).

![cd pipeline](cd-pipeline.png)

![cd detail](cd-detail.png)
