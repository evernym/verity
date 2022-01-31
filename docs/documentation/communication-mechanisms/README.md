# Communication method types
After an agent gets provisioned, it also needs to register a communication method to let the edge agent (mobile app, verity sdk etc) know or receive below types of incoming messages:
* **signal message**: messages received from the other agent (eg: cloud agent) in the same domain.
* **protocol message**: messages received from the other side of the connection.

There are various types of communication methods, and they behave differently. Here are a high level description of them:


## type = 1 (Push notification)
* applicable for
  * Connect.me Mobile app
* pre-requisite
  * connect.me is supposed to register its push notification token (like firebase push notification token). The value should be in this format:
    * FCM: \<token\>
  * Evernym is considered a sponsor for all Connect.me agents and its (Evernym's)
    * push notification account information should have been already configured.
    * the default push notification body template should have been already configured.
* verity notification/message processing
  * In this case verity will use a corresponding push notification service and send a notification about the message (like message type etc). 
  * Once connect.me receives a notification, it is supposed to download the message from its agent by calling the relevant api.


## type = 2 (Webhook)
* applicable for
  * Verity SDK/Rest API
* pre-requisite
  * Verity SDK/Rest API is supposed to register a http url where it wants to receive the incoming messages.
  * No configuration is required for this one.
* verity message/notification processing
  * In this case when verity has to send a message (signal or protocol) to the agent, it will be sent to the registered http webhook.


## type = 3 (Forward Push)
* applicable for
  * Non Evernym Mobile apps using Evernym mobile-sdk (where the sponsor wants to handle sending of actual push notifications)
* pre-requisite
  * Mobile app is supposed to register its own push notification details (we call it sponsee details).
  * associated sponsor’s configuration (received from the customer) should have been already configured (on CAS) with an endpoint
* verity message/notification processing
  * In this case verity will use the associated sponsor’s configured endpoint and send the meta data (msg type, sponsee detail, recipient DID etc) to the associated sponsor’s endpoint and then the sponsor is supposed to notify the mobile app about the message and then the mobile app is supposed to download the message from its agent by calling the relevant api.
  * example sponsor configuration on CAS
    ```
    {
        "active": true,
        "id": "sponsor-id-1",
        "name": "SponsorName"    
        "endpoint": "http://example.sponsor.com",
        "keys": [
            { 
                "verKey": "<sponsor's ver-key>"
            }
        ]     
    }
    ```

## type = 4 (Sponsor Push)
* applicable for
  * Non Evernym Mobile apps using Evernym mobile-sdk (where verity will handle sending of actual push notifications)
* pre-requisite
  * associated sponsor’s configuration (received from the customer) should have been already configured (on CAS) with appropriate push notification account details
* verity message/notification processing
  * Same as type = 1, the only difference is that during sending the push notification, verity will use the associated sponsor’s configured push notification account detail instead of Evernym’s push notification account detail.
  * example sponsor configuration on CAS  
    ```
    {
        "active": true,
        "id": "sponsor-id-1",
        "name": "SponsorName",    
        "endpoint": "",
        "keys": [
            { 
                "verKey": "<sponsor's ver-key>"
            }
        ],
        "push-service": {
            "host": "fcm.googleapis.com",
            "key": "<sponsor's firebase key>",
            "path": "/fcm/send",
            "service": "fcm"
        }
    }
    ```