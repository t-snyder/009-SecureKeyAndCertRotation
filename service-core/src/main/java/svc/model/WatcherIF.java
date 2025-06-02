package svc.model;


public interface WatcherIF
{
  // Kube Watcher Conf Keys
  public static final String KubeClusterName     = "KUBE_CLUSTER_NAME";
  public static final String WatcherNameSpace    = "WATCHER_NAMESPACE";
  public static final String WatcherNames        = "watcherNames";
  public static final String WatcherLogLevel     = "WATCHER_LOG_LEVEL";

  // Pulsar Watcher Secret conf Keys
  public static final String CaCertSecretName    = "ca-cert";
  public static final String ProxyCertSecretName = "tls-cert";
  public static final String PulsarUseTLS        = "pulsarUseTLS";
  public static final String PulsarProxyURL      = "pulsarUrl";
//  public static final String PulsarMetaDataTopic = "metaDataTopic";
//  public static final String PulsarExchangeTopic = "exchangeTopic";

  // Watcher Key Exchange
  public static final String KyberMsgKey      = "kyberExchange";      // Key exchange request msg key
  public static final String KyberKeyRequest  = "KyberKeyRequest";    // Key exchange request event msg Type
  public static final String KyberKeyResponse = "KyberKeyResponse";   // Key exchange response event msg Type

  // Watcher Key Rotation
  public static final String KyberRotateKey      = "kyberRotateMsgKey";   // Key rotate request msg key
  public static final String KyberRotateRequest  = "KyberRotateRequest";  // Key rotate request event msg Type
  public static final String KyberRotateResponse = "KyberRotateResponse"; // Key rotate response event msg Type
  
  // Certificate path
  public static final String CertPath            = "CertPath";

  // Watcher certificate message - event types and msg keys
  public static final String CertInitial  = "CertInitial";
  public static final String CertAdded    = "CertAdded";
  public static final String CertModified = "CertModified";
  public static final String CertDeleted  = "CertDeleted";
 
  // Watcher Msg Header fields
  public static final String WatcherHeaderGenID         = "GenID";
  public static final String WatcherHeaderEventType     = "EventType";
  public static final String WatcherHeaderCorrelationId = "CorrelationId";
  public static final String WatcherHeaderTimeStamp     = "TimeStamp";
  public static final String WatcherHeaderVersion       = "Version";

}
