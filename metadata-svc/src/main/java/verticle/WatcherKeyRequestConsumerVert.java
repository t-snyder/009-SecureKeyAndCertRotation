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
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;

import service.MetadataService;

import svc.model.KyberExchangeMessage;
import svc.model.KyberKey;
import svc.model.ServiceCoreIF;
import svc.model.WatcherIF;
import svc.pulsar.PulsarConsumerErrorHandler;

import svc.utils.MLKEMUtils;


public class WatcherKeyRequestConsumerVert extends AbstractVerticle
{
  private static final Logger LOGGER                   = LoggerFactory.getLogger( WatcherKeyRequestConsumerVert.class );
  private static final String WATCHER_KEY_SUBSCRIPTION = "watcher-key-subscription";

  private MetadataService  metaDataSvc    = null;
  private PulsarClient     pulsarClient   = null;
  private WorkerExecutor   workerExecutor = null;
  private Consumer<byte[]> keyConsumer    = null;

  private PulsarConsumerErrorHandler errHandler = new PulsarConsumerErrorHandler();
  
  public WatcherKeyRequestConsumerVert( PulsarClient pulsarClient, MetadataService svc )
  {
    this.pulsarClient = pulsarClient;
    this.metaDataSvc  = svc;
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


  private void startKeyConsumer() throws PulsarClientException
  {
    MessageListener<byte[]> keyMsgListener = ( consumer, msg ) -> 
    {
      Future<String> result = workerExecutor.executeBlocking( () -> 
      {
        try
        {
          handleKeyExchangeRequest( msg );
          consumer.acknowledge( msg );
          LOGGER.info( "keyConsumer - Message Received and Ack'd - " + new String( msg.getData() ) );
          return ServiceCoreIF.SUCCESS;
        } 
        catch( Throwable t )
        {
          LOGGER.error( "Error processing message. Error = " + t.getMessage() );
 
          // Handle unrecoverable errors
          if( errHandler.isUnrecoverableError(t) ) 
          {
            LOGGER.error( "Unrecoverable error detected. Deploying recovery procedure.");
            consumer.negativeAcknowledge(msg);
            initiateRecovery();
          }
          throw t;
        }
      } );
      
      if( result.failed() )
      {
        LOGGER.error( "Worker execution failed: " + result.cause().getMessage() );
        errHandler.handleMessageProcessingFailure( pulsarClient, keyConsumer, msg, result.cause());
      }
    };

    try
    {
      keyConsumer = pulsarClient.newConsumer().topic( WatcherIF.KeyExchangeRequestTopic )
                                              .subscriptionName( WATCHER_KEY_SUBSCRIPTION )
                                              .subscriptionType( SubscriptionType.Shared )
                                              .messageListener( keyMsgListener )
                                              .ackTimeout( 10, TimeUnit.SECONDS ) // Automatic
                                              .subscribe();
      LOGGER.info("Metadata request consumer created and subscribed to topic: {}", 
                   WatcherIF.KeyExchangeRequestTopic );
    } 
    catch( PulsarClientException e )
    {
      LOGGER.error( "Consumer creation exception. Error = - " + e.getMessage() );

      cleanup();
      throw e;
    }
  }

  private void handleKeyExchangeRequest( Message<byte[]> msg ) 
   throws Exception
  {
    EventBus eventBus = vertx.eventBus();
    String eventType  = WatcherIF.KyberKeyRequest;

    KyberExchangeMessage kyberMsg = KyberExchangeMessage.deserialize( msg.getData() );
    if( kyberMsg == null )
    {
      String errMsg = "Could not deserialize msg.";
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
    
    if( eventType == null )
    {
      String errMsg = "Message event type not found.";
      LOGGER.error( errMsg );
      throw new RuntimeException( errMsg );
    }
    
    if( eventType.compareTo( WatcherIF.KyberKeyRequest ) == 0  ||
        eventType.compareTo( WatcherIF.KyberRotateRequest ) == 0 )
    {
      SecretKeyWithEncapsulation encapsulation = MLKEMUtils.processKyberExchangeRequest( kyberMsg.getPublicKey() );
      PublicKey publicKey = MLKEMUtils.decodePublicKey( kyberMsg.getPublicKey() );
      KyberKey  kyberKey  = new KyberKey( kyberMsg.getSvcId(), publicKey, encapsulation );
      
      metaDataSvc.setWatcherKey( kyberKey );    

      String responseType = null;;
      if( eventType.compareTo( WatcherIF.KyberKeyRequest ) == 0 )
           responseType = WatcherIF.KyberKeyResponse;
      else responseType = WatcherIF.KyberRotateResponse;
      
      KyberExchangeMessage responseMsg = new KyberExchangeMessage( kyberMsg.getSvcId(), 
                                                                   responseType, 
                                                                   kyberMsg.getPublicKey(), 
                                                                   kyberKey.getEncapsulatedSecretKey().getEncapsulation() );
      Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "kyber.keyResponse", 
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

    WatcherKeyRequestConsumerVert cVert = new WatcherKeyRequestConsumerVert( pulsarClient, metaDataSvc );
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
