# Networking

## IPv4 vs. IPv6

Today, all services inside the agency use IPv4. IPv6 is turned off
everywhere. However, we should write our software so it is IPv6 compatible
at some future date, because we expect to switch to using IPv6 eventually.

## Internal vs. External

All services internal to the agency contact one another on 10.x addresses,
not through externally visible endpoints. These 10.x addresses are part
of the same address space as the rest of Evernym's intranet. It will thus
be possible to "enter" the address space of an verity instance from within
workstations that are part of the intranet. (Is this a good idea from a
security perspective?)

Internal DNS is not especially vital to most agency services, but where
we us it, we use Route53. Route53 also provides resolution of non-internal
host names, from internal hosts.

We have an internal root CA.

External DNS of evernym.com hosts is provided through Gandi.