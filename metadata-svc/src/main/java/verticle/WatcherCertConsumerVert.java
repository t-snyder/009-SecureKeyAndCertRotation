package verticle;


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
import io.vertx.core.WorkerExecutor;
//import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import service.MetadataService;
import svc.model.CertificateMessage;
import svc.model.CertificateMessage.CertEventType;
import svc.model.CertificateMessageFactory;
import svc.model.ServiceCoreIF;
import svc.pulsar.PulsarConsumerErrorHandler;

public class WatcherCertConsumerVert extends AbstractVerticle
{
  private static final Logger LOGGER                    = LoggerFactory.getLogger( WatcherCertConsumerVert.class );
  private static final String WATCHER_CERT_SUBSCRIPTION = "watcher-cert-subscription";
 
  private MetadataService  metaDataSvc    = null;
  private PulsarClient     pulsarClient   = null;
  private WorkerExecutor   workerExecutor = null;
  private Consumer<byte[]> certConsumer   = null;

  private PulsarConsumerErrorHandler errHandler = new PulsarConsumerErrorHandler();
//  private CertificateMessageFactory  msgFactory = null;
  
  public WatcherCertConsumerVert( PulsarClient pulsarClient, MetadataService svc )
  {
    this.pulsarClient = pulsarClient;
    this.metaDataSvc  = svc;
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

  private void startCertConsumer() throws PulsarClientException
  {
    MessageListener<byte[]> certMsgListener = ( certConsumer, msg ) -> 
    {
      Future<String> result = workerExecutor.executeBlocking( () -> 
      {
        try 
        {
          handleCertMessage( msg );
          certConsumer.acknowledge( msg );
          LOGGER.info( "Cert Consumer - Message Received and Ack'd - " + new String( msg.getData() ) );
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
      } );
      
      if( result.failed() )
      {
        LOGGER.error( "Worker execution failed: " + result.cause().getMessage() );
        errHandler.handleMessageProcessingFailure( pulsarClient, certConsumer, msg, result.cause());
      }
    };

    try
    {
      certConsumer = pulsarClient.newConsumer().topic( ServiceCoreIF.MetaDataWatcherCertTopic )
                                .subscriptionName( WATCHER_CERT_SUBSCRIPTION )
                                .subscriptionType( SubscriptionType.Shared )
                                .messageListener( certMsgListener )
                                .ackTimeout( 10, TimeUnit.SECONDS ) // Automatic
                                .subscribe();
    } 
    catch( PulsarClientException e )
    {
      LOGGER.error( "Consumer creation exception. Error = - " + e.getMessage() );
      cleanup();
      throw e;
    }
  }

  private void handleCertMessage( Message<?> msg )
  {
    CertificateMessageFactory msgFactory =  new CertificateMessageFactory( metaDataSvc.getWatcherKey().getEncodedSecretKey() );
    CertificateMessage certMsg = msgFactory.readMessage( msg.getData() );

    switch( certMsg.getEventType() )
    {
      case CertEventType.ADDED:
      
        break;

      case CertEventType.MODIFIED:
        
        break;

      case CertEventType.DELETED:
        String errMsg = "TLS Certificate Deleted. Closing";
        LOGGER.error( errMsg );
        cleanup();
        throw new RuntimeException( errMsg );

      case CertEventType.INITIAL:  
        String errMsgInitial = "Initial TLS Certificate Not supported.";
        LOGGER.error( errMsgInitial );
        break;
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

    WatcherCertConsumerVert cVert = new WatcherCertConsumerVert( pulsarClient, metaDataSvc );
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
