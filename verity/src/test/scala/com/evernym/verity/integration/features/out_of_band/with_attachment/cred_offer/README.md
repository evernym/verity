This package contains OOB invitation related specs with cred-offer attachment
So, there are below scenarios to be tested:

1. ReuseInvitationSpec: Different holder trying to re-use already accepted OOB invitation.
2. ReuseConnectionSpec: Same holder trying to re-use existing connection (when received OOB invitation from same issuer) and responds to the attachment.
3. ReuseAttachmentSpec: Same holder trying to re-use existing connection (when received OOB invitation from same issuer) and re-responds to the attachment.