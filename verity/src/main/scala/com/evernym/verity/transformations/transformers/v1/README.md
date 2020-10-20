* This package contains v1 transformers used (or will be used) for 'event' and 'state' persistence. 
Any new transformers should be created outside of this 'v1' package.

* By any means, main logic of this v1 package transformers should NOT be updated once it is 
used in any environment except local dev environment. 

* If any logic needs to be changed, we should create a new version (v2) and then create corresponding transformers with new logic.