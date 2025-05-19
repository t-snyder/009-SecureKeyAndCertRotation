package verticle;


import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import pulsar.WatcherPulsarClient;

import svc.model.ServiceCoreIF;
import svc.model.WatcherConfig;
import svc.pulsar.PulsarConsumerErrorHandler;

public class PulsarConsumerVert extends AbstractVerticle
{
  private static final Logger LOGGER            = LoggerFactory.getLogger(PulsarConsumerVert.class);
  private static final String SUBSCRIPTION_NAME = "watcher-key-response";

  private WatcherConfig       config        = null;
  private WatcherPulsarClient watcherClient = null;
  private PulsarClient        pulsarClient  = null;
  private String              nameSpace     = null;
  
  private WorkerExecutor   workerExecutor   = null;
  private Consumer<byte[]> consumer         = null;
  
  private PulsarConsumerErrorHandler errHandler = null;
 

  public PulsarConsumerVert( WatcherPulsarClient pulsarClient, WatcherConfig config, String nameSpace )
  {
    this.watcherClient = pulsarClient;
    this.pulsarClient  = watcherClient.getPulsarClient();
    this.config        = config;
    this.nameSpace     = nameSpace;
    this.errHandler    = new PulsarConsumerErrorHandler();
  }
  
  @Override
  public void start( Promise<Void> startPromise ) 
   throws Exception
  {
    LOGGER.info("PulsarConsumerVert.start() - Starting PulsarConsumerVert");
    workerExecutor = vertx.createSharedWorkerExecutor("msg-handler");
    
    try 
    {
      startKeyConsumer();
      startPromise.complete();
      LOGGER.info("PulsarConsumerVert started successfully");
    }
    catch( Exception e ) 
    {
      LOGGER.error("Error starting PulsarConsumerVert: {}", e.getMessage(), e);
      cleanup();
      startPromise.fail(e);
    }
  }  

  @Override
  public void stop(Promise<Void> stopPromise) 
   throws Exception
  {
    LOGGER.info("Stopping PulsarConsumerVert");
    cleanup();
    stopPromise.complete();
    LOGGER.info("PulsarConsumerVert stopped successfully");
  }

  private void closeConsumer() 
  {
    if( consumer != null ) 
    {
      try 
      {
        consumer.close();
        LOGGER.info("Key response consumer closed");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error closing key response consumer: {}", e.getMessage(), e);
      }
      consumer = null;
    }
  }

  private void closeWorkerExecutor() 
  {
    if( workerExecutor != null ) 
    {
      try 
      {
        workerExecutor.close();
        LOGGER.info("Worker executor closed");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error closing worker executor: {}", e.getMessage(), e);
      }
      workerExecutor = null;
    }
  }

  private void cleanup()
  {
    closeConsumer();
    closeWorkerExecutor();
    
    LOGGER.info("PulsarConsumerVert cleanup completed");
  }
  
  private void startKeyConsumer() 
   throws PulsarClientException
  {
    LOGGER.info("PulsarConsumerVert.startKeyConsumer() - Starting key exchange response consumer");
    try
    {
      MessageListener<byte[]> keyMsgListener = createKeyExchangeMessageListener();
      
      consumer = pulsarClient.newConsumer().topic( ServiceCoreIF.WatcherKeyResponseTopic ) 
                                           .subscriptionName( SUBSCRIPTION_NAME )
                                           .subscriptionType( SubscriptionType.Shared )
                                           .messageListener( keyMsgListener )
                                           .ackTimeout(30, TimeUnit.SECONDS)
                                           .subscribe();
      LOGGER.info("PulsarConsumerVert.startKeyConsumer() - Key exchange response consumer started successfully");
    } 
    catch( PulsarClientException e )
    {
      String errMsg = "PulsarConsumerVert.startKeyConsumer() - Key exchange response consumer creation failed: Error = " + e.getMessage();
      LOGGER.error( errMsg );

      closeConsumer();
      throw e;
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
          byte[] kyberMsgBytes = msg.getData();
          vertx.eventBus().publish( "watcher.keyExchange.response", kyberMsgBytes );
          consumer.acknowledge(msg);
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

  /**
   * Initiates recovery procedure when unrecoverable errors are detected
   */
  private void initiateRecovery() 
  {
    LOGGER.info("PulsarConsumerVert.initiateRecovery() - Initiating verticle recovery process");
    
    // Deploy a new instance of this verticle before undeploying the current one
    String verticleID = deploymentID();

    DeploymentOptions options = new DeploymentOptions();
    options.setConfig( new JsonObject().put( "worker", true ) );

    PulsarConsumerVert newVert = new PulsarConsumerVert( watcherClient, config, nameSpace);
    
    vertx.deployVerticle( newVert, options ).onComplete(ar -> 
    {
      if( ar.succeeded() )
      {
        String newDeploymentId = ar.result();
        LOGGER.info("PulsarConsumerVert.initiateRecovery() - Deployed replacement PulsarConsumerVert verticle: {}", newDeploymentId);
        
        // Undeploy this verticle after successful deployment of the replacement
        vertx.undeploy( verticleID ).onComplete(ur -> 
        {
          if( ur.succeeded() ) 
          {
            LOGGER.info("PulsarConsumerVert.initiateRecovery() - Current verticle undeployed successfully");
          } 
          else 
          {
            LOGGER.error("PulsarConsumerVert.initiateRecovery() - Failed to undeploy current verticle: {}", ur.cause().getMessage());
          }
        });
      } 
      else 
      {
        LOGGER.error("PulsarConsumerVert.initiateRecovery() - Failed to deploy replacement verticle: {}", ar.cause().getMessage());
      }
    });
  }
}
