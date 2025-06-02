package verticle;


import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
//import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import service.MetadataService;
import svc.crypto.AesGcmHkdfCrypto;
import svc.crypto.EncryptedData;
import svc.handler.SharedSecretManager;
import svc.model.CertificateMessage;
import svc.model.CertificateMessageFactory;
import svc.model.ServiceCoreIF;
import svc.pulsar.PulsarConsumerErrorHandler;

public class WatcherCertConsumerVert extends AbstractVerticle
{
  private static final Logger LOGGER                    = LoggerFactory.getLogger( WatcherCertConsumerVert.class );
  private static final String WATCHER_CERT_SUBSCRIPTION = "watcher-cert-subscription";
 
  private MetadataService  metaDataSvc      = null;
  private PulsarClient     pulsarClient     = null;
  private WorkerExecutor   workerExecutor   = null;
  private Consumer<byte[]> certConsumer     = null;
  private AesGcmHkdfCrypto aesGcmHkdfCrypto = new AesGcmHkdfCrypto();
  
  private PulsarConsumerErrorHandler errHandler    = new PulsarConsumerErrorHandler();
  private SharedSecretManager        encKeyManager = null;
  
  public WatcherCertConsumerVert( Vertx vertx, PulsarClient pulsarClient, MetadataService svc )
  {
    this.pulsarClient  = pulsarClient;
    this.metaDataSvc   = svc;
    this.encKeyManager = new SharedSecretManager( vertx );
//    this.msgFactory   = new CertificateMessageFactory( metaDataSvc.getWatcherKey().getEncodedSecretKey() );
  }

  @Override
  public void start( Promise<Void> startPromise ) throws Exception
  {
    workerExecutor = vertx.createSharedWorkerExecutor( "msg-handler" );

    try
    {
      startCertConsumer();

      LOGGER.info( "WatcherCertConsumerVert Verticle started successfully" );
      startPromise.complete();
    } 
    catch( Exception e )
    {
      String errMsg = "Error encountered in WatcherConsumerVert verticle: " + e.getMessage();
      LOGGER.error( errMsg );
      cleanup();
      startPromise.fail( errMsg );
    }
  }

  /**
   * Handles graceful shutdown of the verticle
   */
  @Override
  public void stop( Promise<Void> stopPromise ) throws Exception
  {
    LOGGER.info( "Stopping WatcherConsumerVert verticle..." );
    cleanup();
    stopPromise.complete();
    LOGGER.info( "WatcherConsumerVert Verticle stopped successfully" );
  }

  /**
   * Cleans up resources used by this verticle
   */
  private void cleanup()
  {
    try
    {
      // Close the worker executor
      if( workerExecutor != null )
      {
        workerExecutor.close();
        LOGGER.info( "Worker executor closed" );
      }

      // Close Pulsar consumer
      if( certConsumer != null )
      {
        certConsumer.close();
        LOGGER.info( "TLS consumer closed" );
      }
    } 
    catch( Exception e )
    {
      LOGGER.error( "Error during cleanup: " + e.getMessage(), e );
    }
  }

  /**
   * Message listener for key exchange responses
   */
  private MessageListener<byte[]> createCertMessageListener() 
  {
    return (consumer, msg) -> 
    {
      workerExecutor.executeBlocking(() -> 
      {
        try 
        {
          CertificateMessage certMsg = handleCertMessage( msg );
          certConsumer.acknowledge( msg );
          LOGGER.info( "WatcherCertConsumerVert.createMessageListener - Message Received and Ack'd. Msg serviceId = " + 
                        certMsg.getServiceId() + "; Msg eventType = " + certMsg.getEventType() + "; caCert = " + certMsg.getCaCert() );
          return ServiceCoreIF.SUCCESS;
        } 
        catch( Throwable t )
        {
          LOGGER.error( "Error processing message. Error = " + t.getMessage() );

          // Handle unrecoverable errors
          if( errHandler.isUnrecoverableError(t) ) 
          {
            LOGGER.error( "Unrecoverable error detected. Deploying recovery procedure.");
            initiateRecovery();
          }
          throw t;
        }
      }).onComplete(ar -> 
         {
           if( ar.failed() ) 
           {
             LOGGER.error("Worker execution failed: {}", ar.cause().getMessage());
             errHandler.handleMessageProcessingFailure(pulsarClient, consumer, msg, ar.cause());
           }
         });
    };
  };


  private String startCertConsumer() throws PulsarClientException
  {
    LOGGER.info("WatcherCertConsumerVert.startCertConsumer() - Starting key exchange response consumer");

    MessageListener<byte[]> certMsgListener = createCertMessageListener();

    workerExecutor.executeBlocking(() -> 
    {
      try
      {
        certConsumer = pulsarClient.newConsumer().topic( ServiceCoreIF.MetaDataWatcherCertTopic )
                                  .subscriptionName( WATCHER_CERT_SUBSCRIPTION )
                                  .subscriptionType( SubscriptionType.Shared )
                                  .messageListener( certMsgListener )
                                  .ackTimeout( 10, TimeUnit.SECONDS ) // Automatic
                                  .subscribe();
        return ServiceCoreIF.SUCCESS;
      } 
      catch( PulsarClientException e )
      {
        LOGGER.error( "Consumer creation exception. Error = - " + e.getMessage() );
        cleanup();
        throw e;
      }
    }).onComplete(ar -> 
    {
      if( ar.failed() ) 
      {
        LOGGER.error("Worker execution failed: {}", ar.cause().getMessage());
        throw new RuntimeException( ar.cause() );
      }
    });
    
	return ServiceCoreIF.SUCCESS;
  };

  private CertificateMessage handleCertMessage( Message<?> msg )
   throws Exception
  {
    EncryptedData      encData   = null;
    byte[]             msgBytes  = null;
    CertificateMessage certMsg   = null;
    byte[]             sharedKey = null;

    try
    {
      sharedKey = encKeyManager.getSecretBytes( "watcher" );
      encData   = EncryptedData.deserialize( msg.getData() );
      msgBytes  = aesGcmHkdfCrypto.decrypt( encData, sharedKey );
      certMsg   = CertificateMessage.deSerialize( msgBytes );
    } 
    catch( Exception e )
    {
      String errMsg = "WatcherCertConsumerVert.handleCertMessage - Error = " + e.getMessage();
      LOGGER.error( errMsg );
      throw new Exception( errMsg );
    }
 
    switch( certMsg.getEventType() )
    {
      case "ADDED":
        LOGGER.info( "WatcherCertConsumerVert.handleCertMessage - handling ecentType = ADDED" );
             
        break;

      case "MODIFIED":
        LOGGER.info( "WatcherCertConsumerVert.handleCertMessage - handling ecentType = MODIFIED" );
        
        break;

      case "DELETED":
        LOGGER.info( "WatcherCertConsumerVert.handleCertMessage - handling ecentType = DELETED" );
        String errMsg = "TLS Certificate Deleted. Closing";
        LOGGER.error( errMsg );
        cleanup();
        throw new RuntimeException( errMsg );

      case "INITIAL":  
        String errMsgInitial = "WatcherCertConsumerVert.handleCertMessage - Initial TLS Certificate Not supported.";
        LOGGER.error( errMsgInitial );
        break;
    }
    
    return certMsg;
  }

  private String handleResponse( AsyncResult<io.vertx.core.eventbus.Message<byte[]>> ar )
  {
    if( ar.succeeded() )
    {
      return ServiceCoreIF.SUCCESS;
    } 
    else
    {
      return( ServiceCoreIF.FAILURE + " Error: " + ar.cause() );
    }
  }

  /**
   * Initiates recovery procedure when unrecoverable errors are detected
   */
  private void initiateRecovery() 
  {
    LOGGER.info("Initiating verticle recovery process");
    
    // Deploy a new instance of this verticle before undeploying the current one
    String verticleID = deploymentID();

    DeploymentOptions pulsarOptions = new DeploymentOptions();
    pulsarOptions.setConfig( new JsonObject().put( "worker", true ) );

    WatcherCertConsumerVert cVert = new WatcherCertConsumerVert( vertx, pulsarClient, metaDataSvc );
    Future<String> result = vertx.deployVerticle( cVert, pulsarOptions );

    if( result.succeeded() )
    {
      LOGGER.info( "Deployed replacement WatcherConsumerVert verticle: " + result.result() );
 
      Future<Void> undeployResult = vertx.undeploy( verticleID );
      if( undeployResult.succeeded() )
      {
        LOGGER.info("Current verticle undeployed successfully");
      } 
      else 
      {
        LOGGER.error( "Failed to undeploy WatcherConsumerVert verticle. Error = " + undeployResult.cause() );
      }
    }
  }
}
