* This package contains legacy transformers used for 'event' and 'state' persistence. 
Any new transformers should be created outside of this 'legacy' package.

* By any means, main logic of this legacy package transformers should NOT be updated else they won't be backward compatible.

* As soon as we finalize new v1 transformers (which is more optimized than this legacy transformation),
we should start using it.