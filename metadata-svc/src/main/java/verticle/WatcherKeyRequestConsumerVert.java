package verticle;


import java.security.PublicKey;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;

import service.MetadataService;
import svc.crypto.KyberKEMCrypto;
import svc.handler.SharedSecretManager;

import svc.model.KyberExchangeMessage;
import svc.model.ServiceCoreIF;
import svc.model.WatcherIF;

import svc.pulsar.PulsarConsumerErrorHandler;


public class WatcherKeyRequestConsumerVert extends AbstractVerticle
{
  private static final Logger LOGGER                   = LoggerFactory.getLogger( WatcherKeyRequestConsumerVert.class );
  private static final String WATCHER_KEY_SUBSCRIPTION = "watcher-key-subscription";

  private static Vertx vertx;
  
  private MetadataService  metaDataSvc    = null;
  private PulsarClient     pulsarClient   = null;
  private WorkerExecutor   workerExecutor = null;
  private Consumer<byte[]> keyConsumer    = null;

  private PulsarConsumerErrorHandler errHandler    = new PulsarConsumerErrorHandler();
  private SharedSecretManager        encKeyManager = null;
  
  public WatcherKeyRequestConsumerVert( Vertx vertx, PulsarClient pulsarClient, MetadataService svc )
  {
    this.vertx         = vertx;
    this.pulsarClient  = pulsarClient;
    this.metaDataSvc   = svc;
    this.encKeyManager = new SharedSecretManager( vertx );
  }

  @Override
  public void start( Promise<Void> startPromise ) throws Exception
  {
    workerExecutor = vertx.createSharedWorkerExecutor( "msg-handler" );

    try
    {
      startKeyConsumer();
      startPromise.complete();
      LOGGER.info( "Verticle started successfully" );
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

      // close key consumer
      if( keyConsumer != null )
      {
        keyConsumer.close();
        LOGGER.info( "Key consumer closed" );
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
  private MessageListener<byte[]> createKeyExchangeMessageListener() 
  {
    return (consumer, msg) -> 
    {
      workerExecutor.executeBlocking(() -> 
      {
        try 
        {
            handleKeyExchangeRequest( msg );
            consumer.acknowledge( msg );
            LOGGER.info( "keyConsumer - Message Received and Ack'd - " );
            return ServiceCoreIF.SUCCESS;
        } 
        catch( Throwable t ) 
        {
          LOGGER.error("Error processing key exchange response: {}", t.getMessage(), t);
          
          if( errHandler.isUnrecoverableError(t) ) 
          {
            LOGGER.error("Unrecoverable error detected. Deploying recovery procedure.");
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
  }

  private String startKeyConsumer() 
   throws PulsarClientException
  {
    LOGGER.info("WatcherKeyRequestConsumerVert.startKeyConsumer() - Starting key exchange response consumer");

    workerExecutor.executeBlocking(() -> 
    {
      try
      {
        MessageListener<byte[]> keyMsgListener = createKeyExchangeMessageListener();
       
        keyConsumer = pulsarClient.newConsumer().topic( ServiceCoreIF.KeyExchangeRequestTopic )
                                                .subscriptionName( WATCHER_KEY_SUBSCRIPTION )
                                                .subscriptionType( SubscriptionType.Shared )
                                                .messageListener( keyMsgListener )
                                                .ackTimeout( 10, TimeUnit.SECONDS ) // Automatic
                                                .subscribe();
        LOGGER.info("Metadata request consumer created and subscribed to topic: {}", 
                     ServiceCoreIF.KeyExchangeRequestTopic );
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

  private void handleKeyExchangeRequest( Message<byte[]> msg ) 
   throws Exception
  {
    LOGGER.info( "========================================================================" );
    LOGGER.info( "WatcherKeyRequestConsumer.handleKeyExchangeRequest received request msg." );
	  EventBus eventBus = vertx.eventBus();
    String eventType  = WatcherIF.KyberKeyRequest;

    KyberExchangeMessage kyberMsg = KyberExchangeMessage.deSerialize( msg.getData() );
    if( kyberMsg == null )
    {
      String errMsg = "WatcherKeyRequestConsumer.handleKeyExchangeRequest Could not deserialize msg.";
      LOGGER.error( errMsg );
      throw new RuntimeException( errMsg );
    }

    eventType = kyberMsg.getEventType();
    if( eventType == null )
    {
      Map<String, String> props = msg.getProperties();
      if( props.containsKey( WatcherIF.WatcherHeaderEventType ) )
        eventType = props.get( WatcherIF.WatcherHeaderEventType );
    }
 
    LOGGER.info( "WatcherKeyRequestConsumer.handleKeyExchangeRequest eventType received is " + eventType );

    if( eventType == null )
    {
      String errMsg = "Message event type not found.";
      LOGGER.error( errMsg );
      throw new RuntimeException( errMsg );
    }
    
    if( eventType.compareTo( WatcherIF.KyberKeyRequest ) == 0  ||
        eventType.compareTo( WatcherIF.KyberRotateRequest ) == 0 )
    {
      LOGGER.info( "WatcherKeyRequestConsumer.handleKeyExchangeRequest generating the Secret with encapsulation.");
      PublicKey                  publicKey     = KyberKEMCrypto.decodePublicKey( kyberMsg.getPublicKey() );
      SecretKeyWithEncapsulation encapsulation = KyberKEMCrypto.processKyberExchangeRequest( KyberKEMCrypto.encodePublicKey( publicKey ) );
//      KyberResponder             kyberKey      = new KyberResponder( kyberMsg.getSvcId(), publicKey, encapsulation );

      encKeyManager.putSecretBytes( kyberMsg.getSvcId(), encapsulation.getEncoded() );
      
      LOGGER.info( "WatcherKeyRequestConsumer.handleKeyExchangeRequest new key with secret key encapsulation set.");

      String responseType = null;;
      if( eventType.compareTo( WatcherIF.KyberKeyRequest ) == 0 )
           responseType = WatcherIF.KyberKeyResponse;
      else responseType = WatcherIF.KyberRotateResponse;
      
      KyberExchangeMessage responseMsg = new KyberExchangeMessage( kyberMsg.getSvcId(), 
                                                                   responseType, 
                                                                   kyberMsg.getPublicKey(), 
                                                                   encapsulation.getEncapsulation() );

      LOGGER.info( "WatcherKeyRequestConsumer.handleKeyExchangeRequest sending the key request response to the eventbus 'watcher.keyExchange.response'");
      LOGGER.info( "WatcherKeyRequestConsumer.handleKeyExchangeRequest encapsulation = " + encapsulation.getEncapsulation() );
      // watcher.keyExchange.response is consumed in WatcherKeyResponseProducer
      Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "watcher.keyExchange.response", 
                                                                                  KyberExchangeMessage.serialize( responseMsg ) );
      response.onComplete( this::handleResponse );
    }
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

    WatcherKeyRequestConsumerVert cVert = new WatcherKeyRequestConsumerVert( vertx, pulsarClient, metaDataSvc );
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
