package pulsar;


import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import svc.crypto.KyberKEMCrypto;
import svc.model.KyberInitiator;
import svc.model.WatcherConfig;
import svc.model.WatcherIF;

import utils.WaitOnPulsarReady;


public class WatcherPulsarClient
{
  private static final Logger     LOGGER = LoggerFactory.getLogger( WatcherPulsarClient.class );
  private static KubernetesClient client = null;
 
  // Config Parameters
  private boolean pulsarUseTLS      = true;
  private String  pulsarUrl         = null;
  private String  pulsarTopic       = null;
  private String  exchangeTopic     = null;
  private String  pulsarTLSCertPath = null;

  // Publishing info
  private String  nameSpace   = null;
  private String  podName     = null;

  // Runtime vars
  private PulsarClient    pulsarClient = null;
  private boolean         pulsarReady  = false;

  private KyberInitiator  currentKey   = null;
  private KyberInitiator  priorKey     = null;
  private KyberInitiator  inProcessKey = null;
  
  
  public WatcherPulsarClient( KubernetesClient client, WatcherConfig config, String nameSpace, String podName ) 
   throws PulsarClientException, NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    WatcherPulsarClient.client = client;
    
    this.pulsarUseTLS      = Boolean.parseBoolean( config.getPulsarUseTLS() );
    this.pulsarUrl         = config.getPulsarURL();
    this.pulsarTLSCertPath = config.getTlsCertPath();
    this.nameSpace         = nameSpace;
    this.podName           = podName;

    LOGGER.info( "pulsarUseTLS = " + this.pulsarUseTLS + ": Config pulsarUseTLS = " + config.getPulsarUseTLS() );
    LOGGER.info( "pulsarUrl    = " + this.pulsarUrl + ": Config pulsarURL = " + config.getPulsarURL() );
    LOGGER.info( "WatcherPulsarClient configured." );

    pulsarClientInit();
    genKyberKeyPair();
  }
  
  private void pulsarClientInit()  
   throws PulsarClientException
  {
    LOGGER.info( "WatcherPulsarClient.pulsarClientInit() - starting." );

    pulsarReady = WaitOnPulsarReady.waitOnPulsar( client, nameSpace, podName );
    if( !pulsarReady )
    {
      String msg = "Pulsar Proxy Pods are not ready. Cancelling in - " + WatcherIF.KubeClusterName + " for " + WatcherIF.WatcherNameSpace;
      LOGGER.error( msg );
      pulsarReady = false;
      throw new PulsarClientException( msg );
    }

    LOGGER.info( "WatcherPulsarClient.pulsarClientInit() Pulsar proxy pods are ready." );
       
    try
    {
      if( pulsarUseTLS ) { buildTlsClient(); }
      else {               buildClient();    }
    } 
    catch( PulsarClientException e )
    {
      String msg = "Pulsar Proxy Pods are not ready. Cancelling in - " + WatcherIF.KubeClusterName + " for " + WatcherIF.WatcherNameSpace;
      LOGGER.error( msg );
      pulsarReady = false;
      throw e;
    }

    LOGGER.info( "WatcherPulsarClient.pulsarClientInit() - Pulsar client is ready." );
  }
  
  private void buildClient() 
   throws PulsarClientException
  {
    pulsarClient = PulsarClient.builder().serviceUrl( pulsarUrl ).build();
    LOGGER.info( "WatcherPulsarClient.buildClient() - Pulsar client is ready." );
  }
     
  private void buildTlsClient() 
   throws PulsarClientException
  {
    LOGGER.info( "WatcherPulsarClient.buildTLSClient() - Starting with tlsCertificateFilePath = " + pulsarTLSCertPath );
 
    pulsarClient = PulsarClient.builder()
                               .serviceUrl( pulsarUrl )
                               .tlsTrustCertsFilePath( pulsarTLSCertPath )
                               .enableTlsHostnameVerification( false ) // false by default, in any case
                               .allowTlsInsecureConnection(    false ) // false by default, in any case
                               .build();    
    LOGGER.info( "WatcherPulsarClient.buildTLSClient() - Pulsar client with TLS is ready." );
  } 

  
  private void genKyberKeyPair() 
  {
    try
    {
      LOGGER.info( "WatcherPulsarClient.genKyberKeyPair() - Start" );
      inProcessKey = new KyberInitiator( "watcher", KyberKEMCrypto.generateKeyPair() );
    
    }
    catch( NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e)
    {
      String errMsg = "WatcherPulsarClient.gehKyberKeyPair() - Error gnerating key pair. Error = " + e.getMessage();
      LOGGER.error( errMsg );
      throw new RuntimeException( errMsg );
    }
  }

  public void closePulsarClient()
  {
    try
    {
      pulsarClient.close();   
    } 
    catch( PulsarClientException e )
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return;
    }
    
    LOGGER.info( "WatcherPulsarClient.closePulsarClient() - Pulsar client closed." );
  }
  
  public PulsarClient   getPulsarClient()  { return pulsarClient;  }
  public String         getPulsarTopic()   { return pulsarTopic;   }
  public String         getExchangeTopic() { return exchangeTopic; }
  public String         getNameSpace()     { return nameSpace;     }

  public KyberInitiator getCurrentKey()    { return currentKey;    }
  public KyberInitiator getPriorKey()      { return priorKey;      }
  public KyberInitiator getInProcessKey()  { return inProcessKey;  }
  
  public void setCurrentKey( KyberInitiator key ) 
  {
    if( currentKey != null )
    {
      priorKey = currentKey;
    }
    
    this.currentKey = key; 
    
    if( inProcessKey != null )
    {
      inProcessKey = null;
    }
  }
  
}