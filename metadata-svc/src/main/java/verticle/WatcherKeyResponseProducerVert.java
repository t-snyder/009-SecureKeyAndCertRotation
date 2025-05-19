package verticle;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import io.fabric8.kubernetes.client.KubernetesClient;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import svc.model.ServiceCoreIF;
import svc.model.PulsarMsgHeader;


public class WatcherKeyResponseProducerVert extends AbstractVerticle
{
  private static final Logger LOGGER          = LoggerFactory.getLogger( WatcherKeyResponseProducerVert.class );
  private static final String KeyProducerName = "key-response";
  private static final String Version         = "1.0";

  private PulsarClient            pulsarClient        = null;
  private Producer<byte[]>        keyResponseProducer = null; // Send request for key exchange to metadata service
  private MessageConsumer<byte[]> keyResponseConsumer = null;
 
  
  public WatcherKeyResponseProducerVert( PulsarClient pulsarClient )
  {
    this.pulsarClient  = pulsarClient;
  }
  
  @Override
  public void start( Promise<Void> startPromise ) 
   throws Exception
  {
    try
    {
      // Initialize key exchange response producer
      keyResponseProducer = pulsarClient.newProducer( Schema.BYTES )
                                .topic( ServiceCoreIF.WatcherKeyResponseTopic )
                                .producerName(    KeyProducerName )
                                .enableBatching(  false )                  // Enable guaranteed delivery
                                .maxPendingMessages(0)                     // Enable guaranteed delivery
                                .create();
      LOGGER.info( "Pulsar key exchange resposne producer created" );
    
      // Register event bus consumer
      keyResponseConsumer = vertx.eventBus().consumer("kyber.keyResponse", message -> 
      {
        try
        {
          String result = processKeyResponse( message );
          message.reply(result);
        } catch (Exception e) {
          LOGGER.error("Error processing key response: " + e.getMessage(), e);
          message.fail(500, "Error processing key response: " + e.getMessage());
        }
      });

      startPromise.complete();
      LOGGER.info("WatcherProducerVert started successfully");
    } 
    catch( Exception e ) 
    {
      String msg = "Failed to initialize WatcherProducerVert: " + e.getMessage();
      LOGGER.error(msg, e);
      cleanup();
      startPromise.fail(msg);
      throw e;
    }
  }


  private String processKeyResponse( Message<byte[]> msg )
  {
    LOGGER.info("WatcherProducerVert.processKeyResponse() - Processing key response message");
    if( msg == null || msg.body() == null )
    {
      LOGGER.error( "WatcherProducerVert.processKeyResponse() - Invalid message received" );
      return ServiceCoreIF.FAILURE;
    }
    
    byte[] encapsulation = (byte[]) msg.body();
 
    PulsarMsgHeader header = new PulsarMsgHeader( null, ServiceCoreIF.KyberResponse, UUID.randomUUID().toString(), Instant.now().toString(), Version );
    
    try
    {
      sendMessage( keyResponseProducer, ServiceCoreIF.KyberMsgKey, encapsulation, header.toMap() );
      LOGGER.info("Successfully sent key response message to Watcher service");
      return ServiceCoreIF.SUCCESS;
    } 
    catch( PulsarClientException e )
    {
      LOGGER.error("Failed to send key response message", e);
      return ServiceCoreIF.FAILURE;
    }
  }

  @Override
  public void stop(Promise<Void> stopPromise) 
   throws Exception 
  {
    LOGGER.info("Stopping WatcherProducerVert");
    cleanup();
    stopPromise.complete();
  }

  private void cleanup() 
  {
    // Unregister event bus consumer
    if( keyResponseConsumer != null ) 
    {
      try 
      {
        keyResponseConsumer.unregister().toCompletionStage()
                                        .toCompletableFuture()
                                        .get( 5, TimeUnit.SECONDS );
        LOGGER.info("Unregistered key response consumer");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error unregistering key response consumer: " + e.getMessage(), e);
      }
    }

    // Close producer
    if( keyResponseProducer != null ) 
    {
      try 
      {
        keyResponseProducer.close();
        LOGGER.info("Closed key producer");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error closing key producer: " + e.getMessage(), e);
      }
    }
  }

  public CompletableFuture<MessageId> sendAsyncMessage( Producer<byte[]> producer, String msgKey, byte[] msgBytes, Map<String, String> props )
  {
    try 
    {
      return producer.newMessage().key(   msgKey)
                                  .value( msgBytes)
                                  .properties(props)
                                  .sendAsync()
                                  .exceptionally(ex -> {
                                     LOGGER.error("Async message sending failed for key " + msgKey + ": " + ex.getMessage(), ex);
                                     throw new CompletionException(ex);
                                   });
    } 
    catch( Exception e ) 
    {
      LOGGER.error("Failed to create async message with key " + msgKey + ": " + e.getMessage(), e);
      CompletableFuture<MessageId> future = new CompletableFuture<>();
      future.completeExceptionally(e);
      return future;
    }
  }

  public MessageId sendMessage( Producer<byte[]> producer, String msgKey, byte[] msgBytes, Map<String, String> props )
   throws PulsarClientException
  {
    try
    {
     return producer.newMessage().key(        msgKey   )
                                 .value(      msgBytes )
                                 .properties( props    )
                                 .send();
    } 
    catch( PulsarClientException e )
    {
      LOGGER.error( "Error sending message with msgKey " + msgKey + ". Error = " + e.getMessage() );
      throw e;
    }   
  }
  
}
