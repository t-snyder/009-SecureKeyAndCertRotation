package verticle;


import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;

import io.vertx.core.eventbus.EventBus;

import org.apache.pulsar.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import svc.model.ServiceCoreIF;


public class MetadataClientConsumerVert extends AbstractVerticle
{
  private static final Logger LOGGER            = LoggerFactory.getLogger( MetadataClientConsumerVert.class );
  private static final String SUBSCRIPTION_NAME = "metadata-request-subscription";

  private PulsarClient        pulsarClient  = null;
  
  private WorkerExecutor   workerExecutor   = null;
  private Consumer<byte[]> consumer         = null;

  public MetadataClientConsumerVert( PulsarClient pulsarClient )
  {
    this.pulsarClient = pulsarClient;
  }

  
  @Override
  public void start( Promise<Void> startPromise )
  {
    workerExecutor = vertx.createSharedWorkerExecutor("msg-handler");
    
    try
    {
      startRequestConsumer();
      startPromise.complete();
      LOGGER.info("MetadataConsumerVert started successfully");
    } 
    catch( PulsarClientException e )
    {
      String msg = "Failed to initialize MetadataConsumerVert: " + e.getMessage();
      LOGGER.error(msg, e);
      cleanup();
      startPromise.fail(msg);
    }
    
    startPromise.complete();
  }

  private void startRequestConsumer() 
   throws PulsarClientException
  {
    MessageListener<byte[]> requestMsgListener = (consumer, msg) -> 
    {
      workerExecutor.executeBlocking( () -> 
      {
        try 
        {
          handleRequestMessage( msg );
          consumer.acknowledge( msg );
          LOGGER.info( "Consumer - Message Received and Ack'd - " + new String( msg.getData() ));
          return "success";
        } 
        catch( Throwable t )
        {
          LOGGER.error( "Error processing message. Error = " + t.getMessage() );
          throw t;
        }
      });
    };

    try
    {
      consumer = pulsarClient.newConsumer().topic( ServiceCoreIF.MetaDataClientRequestTopic ) 
                                           .subscriptionName( SUBSCRIPTION_NAME )
                                           .subscriptionType( SubscriptionType.Shared )
                                           .messageListener(  requestMsgListener )
                                           .ackTimeout(10, TimeUnit.SECONDS) // Automatic redelivery if not acknowledged
                                           .subscribe();
      LOGGER.info("Metadata request consumer created and subscribed to topic: {}", 
          ServiceCoreIF.MetaDataClientRequestTopic);
    } 
    catch( PulsarClientException e )
    {
      LOGGER.error( "Consumer creation exception. Error = - " + e.getMessage() );
      cleanup();
      throw e;
    } 
  }

  private void handleRequestMessage( Message<byte[]> msg )
  {
    try
    {
      Map<String, String> props = msg.getProperties();
      
      String   eventType = props.get( ServiceCoreIF.MsgHeaderEventType );
      EventBus eventBus  = vertx.eventBus();

      switch( eventType )
      {
        case "cert-notify":
        { 
          String jsonStr = processSave( msg );
          Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "pulsar.cert.notify", jsonStr.getBytes() );
          response.onComplete( this::handleResponse );
          break;
        }
        case "save":
        { 
          String jsonStr = processSave( msg );
          Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "cassandra.save", jsonStr.getBytes() );
          response.onComplete( this::handleResponse );
          break;
        }
        case "get":
        {
          String jsonStr = processGet( msg );
          Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "cassandra.get", jsonStr.getBytes() );
          response.onComplete( this::handleResponse );
          break;
        }
        case "getAll":
        {
          String jsonStr = processGetAll( msg );
          Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "cassandra.get", jsonStr.getBytes() );
          response.onComplete( this::handleResponse );
          break;
        }
        case "update":
        {
          String jsonStr = processUpdate( msg );
          Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "cassandra.update", jsonStr.getBytes() );
          response.onComplete( this::handleResponse );
          break;
        }
        case "delete":
        {
          String jsonStr = processDelete( msg );
          Future<io.vertx.core.eventbus.Message<byte[]>> response = eventBus.request( "cassandra.delete", jsonStr.getBytes() );
          response.onComplete( this::handleResponse );
          break;
        }
        default:
        {
          LOGGER.warn( "Unknown eventType: {}", eventType );
//          consumer.acknowledgeAsync( msg );
          break;
        }
      }
    }
    catch( Exception e )
    {
      LOGGER.error( "Error processing Pulsar message", e );
//      consumer.negativeAcknowledge( msg );
      throw new RuntimeException("Error processing message", e);
    }
  }
  
  private String handleResponse( AsyncResult<io.vertx.core.eventbus.Message<byte[]>> ar )
  {
    if( ar.succeeded() ) { return ServiceCoreIF.SUCCESS; }
     else { return( ServiceCoreIF.FAILURE + " Error: " + ar.cause() ); }
  }

   
  private String processSave( Message<byte[]> msg )
  {
  
    return null;
  }
  
  private String processGet( Message<byte[]> msg )
  {
    
    return null;
  }

  private String processGetAll( Message<byte[]> msg )
  {
    
    return null;
  }

  private String processUpdate( Message<byte[]> msg )
  {
    
    return null;
  }

  private String processDelete( Message<byte[]> msg )
  {
    
    return null;
  }

  /**
  private void publishEvent( String eventType, JsonObject data ) 
  {
    vertx.executeBlocking(()-> 
    {
      try 
      {
        JsonObject event = new JsonObject()
                                .put("type", eventType)
                                .put("timestamp", System.currentTimeMillis())
                                .put("data", data);
            
        producer.sendAsync(event.toString().getBytes() )
                .thenAccept(msgId -> promise.complete())
                .exceptionally(e -> {
                    promise.fail(e);
                    return null;
                 });
        return ServiceCoreIF.SUCCESS;
     } 
      catch( Exception e ) 
     {
       logger.error("Error publishing event to Pulsar", e);
       return ServiceCoreIF.FAILURE;
     }
    };
    return ServiceCoreIF.FAILURE;
  );
 **/
  
  @Override
  public void stop( Promise<Void> stopPromise ) 
  {
    try 
    {
      if (consumer     != null) { consumer.close(); }
      if (pulsarClient != null) { pulsarClient.close(); }
              
      LOGGER.info("Pulsar client closed");
      stopPromise.complete();
    } 
    catch( Exception e )
    {
      LOGGER.error("Error closing Pulsar client", e);
      stopPromise.fail( e );
    }
  }
 
  private void cleanup() 
  {
    // Close worker executor
    if( workerExecutor != null ) 
    {
      try 
      {
        workerExecutor.close();
        LOGGER.info("Closed worker executor");
      }
      catch( Exception e ) 
      {
        LOGGER.warn("Error while closing worker executor: " + e.getMessage(), e);
      }
    }
    
    // Close consumer
    if( consumer != null ) 
    {
      try 
      {
        consumer.close();
        LOGGER.info("Closed consumer");
      }
      catch( Exception e ) 
      {
        LOGGER.warn("Error closing consumer: " + e.getMessage(), e);
      }
    }
    
    LOGGER.info("MetadataConsumerVert cleanup completed");
  }  
  
}
