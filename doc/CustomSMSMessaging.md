### Custom SMS Messaging 
 - Based on the work done in https://evernym.atlassian.net/browse/VE-1621. 
 - A customer, within their domain, can configure custom SMS features. The connection offer message content and the deeplink url are customizable. \
 -  This configuration is done out-of-band by contacting Evernym's Technical Enablement team. \
 - No runtime changes can be made but the configuration is hot-loadable. 
### Configuration - Message Template 
 - Exmple Config
 
       msg-template {
           sms-msg-template-invite-url = "#{baseUrl}/agency/invite?t=#{token}" 
       
           sms-msg-template-offer-conn-msg = "#{requesterName} would like you to install Connect-Me for greater identity verification: #{appUrlLink}" 
       
           sms-offer-template-deeplink-url = "https://connectme.app.link?t=#{token}" 
       
           agent-specific {
       
             # example:
             # <domain-id-1>: {
             #   sms-msg-template-offer-conn-msg = "#{requesterName} would like to connect with you. #{appUrlLink}"
             #   sms-offer-template-deeplink-url = "https://masterpass.app.link?t=#{token}"
             # }
             8kLWtRSbRthozq4kTM6dge = {
                sms-offer-template-deeplink-url = "https://masterlink.app.link?t=#{token}"
             }
           }
       
       }
- `sms-msg-template-invite-url`: 
    - NOT CUSTOMIZABLE - Not sure why it's a template.
    - This is the url used to fetch the invitation details.
- `sms-msg-template-offer-conn-msg`: 
    - `requesterName` - Name of the connection inviter. 
    - `appUrlLink` - The deeplink used to download the app and retrieve the connection details.
    - This template is used to customize the sms connection offer message content sent to the invitee. 
- `sms-offer-template-deeplink-url`: 
    - `token`
    - This is a deeplink url sent in the sms message used by the invitee to fetch the invitation details. \
      The link will take the invitee to the proper place to download the app if not downloaded already or will open the already downloaded app. \
      The app will use this deeplink to communicate with its cloud agent and get the invitation details.
- `agent-specific`: 
    - Each domain can provide specific overrides for its agent. These entries are categorized by the domainDid. \
      If not explicitly defined here, the default connection messages will be used.
    - The connection offer and the deeplink url can be overridden for a domain. 
