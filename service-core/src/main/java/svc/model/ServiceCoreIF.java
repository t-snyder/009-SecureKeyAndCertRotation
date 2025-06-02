package svc.model;

public interface ServiceCoreIF
{
  public static final String SUCCESS = "success";
  public static final String FAILURE = "failure";

  // Message Header attributes
  public static final String MsgHeaderServiceID     = "ServiceID";
  public static final String MsgHeaderEventType     = "EventType";
  public static final String MsgHeaderCorrelationId = "CorrelationId";
  public static final String MsgHeaderTimeStamp     = "TimeStamp";
  public static final String MsgHeaderVersion       = "Version";

  // Key Exchange
  public static final String KyberMsgKey     = "kyberExchange";      // Key exchange request msg key
  public static final String KyberPublicKey  = "KyberKeyRequest";    // Key exchange request msg Type
  public static final String KyberResponse   = "KyberKeyResponse";   // Key exchange response msg Type

  // Topics
  public static final String MetaDataClientRequestTopic      = "persistent://metadata/client/request";          // topic for metadata service receiving a client request client data
  public static final String MetaDataClientNotificationTopic = "persistent://metadata/client/notification";         // topic for metadata service sending data available to all subscribers

  public static final String MetaDataCertRequestTopic = "persistent://metadata/pulsar/tls-observer";     // topic for a client to request updates to pulsar tls certs
  public static final String MetaDataCertPublishTopic = "persistent://metadata/pulsar/tls-publish";      // topic for broaddcast sending tls cert updates

  public static final String MetaDataWatcherCertTopic = "persistent://metadata/watcher/cert-update";     // topic for receiving tls cert update from watcher 
  public static final String KeyExchangeRequestTopic  = "persistent://metadata/kyber/exchange-request";  // topic for metadata service to receive a key exchange request

  public static final String WatcherKeyRequestTopic   = "persistent://metadata/watcher/exchange-request";  // topic for metadata service to receive a key exchange request
  public static final String WatcherKeyResponseTopic  = "persistent://metadata/watcher/exchange-response"; // topic for watcher to receive key exchange result

  // Service configMap default env key
  public static final String ConfDefaultKey = "serviceConfig";

}
