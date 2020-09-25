

### General

* never persist any event within Future.
* if region actor type name or implementation of persistence id changes, old actors will become stale, 
  so any changes to these should not be done without knowing its consequences.