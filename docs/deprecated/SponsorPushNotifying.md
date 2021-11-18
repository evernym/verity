### Registering Sponsor with Push Notification Key
- more info on `Sponsor` registration and `Sponsee` provisioning can be found here: https://github.com/evernym/mobile-sdk/blob/master/2.%20Initialization.md 
- In order to register as a `Sponsor`, contact `support@evernym.com`.
- Registration requires the `Sponsor` to provide these attributes to Evernym’s TE department: 
    ```json
      {
        "name": "String",
        "id": "String",
        "keys": [{"verKey":"VK"}],
        "endpoint": "String",
        "active": boolean,
        "push-service": { 
           "service": "fcm", 
           "host": "fcm.googleapis.com", 
           "path": "/fcm/send",
           "key": "hKZAMN87AxeQVmmUMCuG5XSjsA0PUv8wzBsx3zwcPgZDekSqBDkaCU1R-Di1f3rQP3U8"
        } 
      }
    ```
    `name`: The name of the `Sponsor` who endorses the provisioning . \
    `id`: An ID which will not change. Keys cannot be used because of rotation possibilities. (May be assigned by TE) \
    `keys`: VerKey associated with the signing of the token and used to verify a `Sponsee's` provision token. 
     - The signing keys (including the verkey shared with Evernym's Cloud Service) can be generated using this simple tool: `https://github.com/sovrin-foundation/launch/raw/master/sovrin-keygen.zip` 
     - The Private keys need to be stored in a safe place under control of the `Sponsor` 
     - The public verkey will be shared with Evernym for signature validation.
     
    `endpoint`: `Sponsor's` URL that Evernym Cloud Service should forward `Sponsee` messages to. \
    `active`: If the `Sponsor` has an active status with Evernym's Cloud Service  \
    `push-service`: Object that defines the Sponsor's push service information such as `service`, `host`, `path`, and `key`
    
### Using the Sponsor's push notification key to notify a Sponsee
1. Sponsee Provisioning 
   - Provisioning for 3rd party apps: https://github.com/evernym/mobile-sdk/blob/master/2.%20Initialization.md#mobile-sdk---3rd-party-apps-sponsee, a `Sponsee` will need to provision with Evernym's Cloud Service using a token signed by the `Sponsor`.
   - Provisioning for Connect.me or Evernym controlled apps: https://github.com/evernym/mobile-sdk/blob/master/2.%20Initialization.md#cme-sponsee
2. Update Communication Method 
   - After provisioning is complete, the `Sponsee` needs to register a communication preference (`update_com_method`). \
   The `Sponsee` will provide 3 values `id`, `type`, and `value`. \
          `id`: is whatever the `Sponsor` uses to identify this specific `Sponsee`. \
          `type`: Int - Com Method Type (ALWAYS `4` when using the Sponsor's push notification configuration), \
          `value`: String // this is the actual push notification address
      
3. Message Alerts
   - After the Communication Method on the Cloud Agent is set to `4`, the cloud agent will alert the `Sponsee` using the `Sponsor's` \
   `push-service` configuration.
   - The push notification will contain the ids of the new messages that can be downloaded.


