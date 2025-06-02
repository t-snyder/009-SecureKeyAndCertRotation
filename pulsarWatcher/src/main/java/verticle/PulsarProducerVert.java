package verticle;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
//import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import pulsar.WatcherPulsarClient;
import svc.exceptions.AvroTransformException;
import svc.model.KyberExchangeMessage;
import svc.model.ServiceCoreIF;
import svc.model.WatcherIF;
import svc.model.WatcherMsgHeader;


public class PulsarProducerVert extends AbstractVerticle
{
  private static final Logger LOGGER          = LoggerFactory.getLogger( PulsarProducerVert.class );
  private static final String TlsProducerName = "tls-producer";
  private static final String KeyProducerName = "key-producer";

  private WatcherPulsarClient watcherClient = null;
  private PulsarClient        pulsarClient  = null;
  private Producer<byte[]>    tlsProducer   = null; // Send tls cert initial, add, modify, delete events
  private Producer<byte[]>    keyProducer   = null; // Send request for key exchange to metadata service
  
  
  public PulsarProducerVert( WatcherPulsarClient pulsarClient )
  {
    this.watcherClient = pulsarClient;
    this.pulsarClient  = watcherClient.getPulsarClient();
  }

  @Override
  public void start(Promise<Void> startPromise) 
   throws Exception
  {
    LOGGER.info("PulsarPublisherVert.start() - Starting verticle");
    
    try
    {
      initializeCertProducer();
      initializeKeyProducer();
    } 
    catch( Exception e )
    {
      LOGGER.error( "Error waiting for Pulsar client to be ready: {}", e.getMessage(), e );
      cleanup();
      startPromise.fail(e);
      throw new RuntimeException( e );
    }

    // Register to consume messages from the event bus for the pulsar keyProducer
    vertx.eventBus().consumer("pulsar.keyProducer", message -> 
    { 
      LOGGER.info("PulsarPublisherVert.start() - Received  pulsar.keyProducer message on event bus");
      
      JsonObject jsonMsg = (JsonObject) message.body();
      
      Map<String, String> headers = WatcherMsgHeader.fromJson( jsonMsg.getJsonObject( "headers" ));
      String              msgKey  = jsonMsg.getString( "msgKey"  );
      byte[]              msgBody = jsonMsg.getBinary( "msgBody" );

      // Temporary Test
      try
      {
        KyberExchangeMessage testMsg = KyberExchangeMessage.deSerialize( msgBody );
        LOGGER.info( "KyberExchangeMessage successfully deserialized in PulsarProducerVert." );
      } 
      catch( Exception e )
      {
        LOGGER.error( "KyberExchangeMessage deSerialize error. Error = " + e.getMessage() );
        throw new RuntimeException( e );
      }
      
      try
      {
        sendMessage( keyProducer, msgKey, (byte[])msgBody, headers );
        LOGGER.info("===========================================================================" );
        LOGGER.info("PulsarProducerVert eventbus consumer pulsar.keyProducer sent pulsar message." );
        LOGGER.info("Message sent using keyProducer on " + ServiceCoreIF.KeyExchangeRequestTopic + " topic." );
      } 
      catch( PulsarClientException e )
      {
        String errMsg = "Error sending pulsar message. Error = " + e.getMessage();
        LOGGER.error( errMsg, e );
      } 
    });    

    // Register to consume messages from the event bus for the pulsar certProducer
    vertx.eventBus().consumer("pulsar.certProducer", message -> 
    { 
      LOGGER.info("PulsarPublisherVert.start() - Received cert message on event bus");
      
      JsonObject jsonMsg = (JsonObject) message.body();
      
      Map<String, String> headers = WatcherMsgHeader.fromJson( jsonMsg.getJsonObject( "headers" ));
      String              msgKey  = jsonMsg.getString( "msgKey"  );
      byte[]              msgBody = jsonMsg.getBinary( "msgBody" );
 
      try
      {
        sendMessage( tlsProducer, msgKey, (byte[])msgBody, headers );
      } 
      catch( PulsarClientException e )
      {
        String errMsg = "Error sending pulsar message. Error = " + e.getMessage();
        LOGGER.error( errMsg, e );
      } 
    });    

    startPromise.complete();
    LOGGER.info("PulsarPublisherVert.start() - Completed startup" );
  }

  @Override
  public void stop(Promise<Void> stopPromise) 
   throws Exception
  {
    LOGGER.info("PulsarPublisherVert.stop() - Starting");
    cleanup();
    stopPromise.complete();
    LOGGER.info("PulsarPublisherVert.stop() - Stopped successfully");
  }
  
  private void cleanup()
  {
    LOGGER.info("PulsarPublisherVert.cleanup() - Cleaning up PulsarPublisherVert resources");
    
    // Close producers
    if( tlsProducer != null ) 
    {
      try 
      {
        tlsProducer.close();
        LOGGER.info("PulsarPublisherVert.cleanup() - TLS producer closed");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("PulsarPublisherVert.cleanup() -Error closing TLS producer: {}", e.getMessage(), e);
      }
    }
    
    if( keyProducer != null ) 
    {
      try 
      {
        keyProducer.close();
        LOGGER.info("PulsarPublisherVert.cleanup() - Key producer closed");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("PulsarPublisherVert.cleanup() - Error closing key producer: {}", e.getMessage(), e);
      }
    }
    
    LOGGER.info("PulsarPublisherVert.cleanup() - PulsarPublisherVert cleanup completed");
  }

  private void initializeCertProducer() 
   throws PulsarClientException
  {
    LOGGER.info( "PulsarPublisherVert.initializeTLSProducer begin" );

    try 
    {
      tlsProducer = pulsarClient.newProducer(Schema.BYTES)
                                .topic(ServiceCoreIF.MetaDataWatcherCertTopic)
                                .producerName( TlsProducerName )
                                .enableBatching( false )                   // Enable guaranteed delivery
                                .maxPendingMessages(0)                    // Enable guaranteed delivery
                                .sendTimeout(30, TimeUnit.SECONDS)
                                .create();
      LOGGER.info("Pulsar TLS cert producer created successfully");
    } 
    catch( PulsarClientException e ) 
    {
      String msg = "Failed to create TLS producer: " + e.getMessage();
      LOGGER.error( msg );
      throw e;
    }

    LOGGER.info( "Pulsar TLS producers initialized successfully" );
  }
  
  private void initializeKeyProducer() 
   throws PulsarClientException
  {
    LOGGER.info( "PulsarPublisherVert.initializeKeyProducer begin" );
    try 
    {
      keyProducer = pulsarClient.newProducer(Schema.BYTES)
                                .topic( ServiceCoreIF.KeyExchangeRequestTopic)
                                .producerName(  KeyProducerName )
                                .enableBatching(false)                   // Enable guaranteed delivery
                                .maxPendingMessages(0)                    // Enable guaranteed delivery
                                .sendTimeout(30, TimeUnit.SECONDS)
                                .create();
      LOGGER.info("Pulsar key exchange request producer created successfully");
    } 
    catch( PulsarClientException e ) 
    {
      String msg = "Failed to create key producer: " + e.getMessage();
      LOGGER.error( msg );
      throw e;
    }
    
    LOGGER.info( "Pulsar Key producers initialized successfully" );
  }
 
  
  public CompletableFuture<MessageId> sendAsyncMessage( Producer<byte[]> producer, String msgKey, byte[] msgBytes, Map<String, String> props )
  {
    if( producer == null ) 
    {
      LOGGER.error("Cannot send message - producer is null");
      CompletableFuture<MessageId> future = new CompletableFuture<>();
      future.completeExceptionally( new IllegalStateException( "Producer is null" ));
      return future;
    }
    
    LOGGER.debug("Sending async message with key: {}", msgKey);
    return producer.newMessage().key(msgKey)
                                .value(msgBytes)
                                .properties(props)
                                .sendAsync()
                                .whenComplete( (messageId, throwable) -> 
                                 {
                                   if( throwable != null ) 
                                   {
                                     LOGGER.error("Failed to send async message: {}", throwable.getMessage(), throwable);
                                   } 
                                   else 
                                   {
                                     LOGGER.debug("Async message sent successfully, message ID: {}", messageId);
                                   }
                                 });
  }

  
  public MessageId sendMessage( Producer<byte[]> producer, String msgKey, byte[] msgBytes, Map<String, String> props )
   throws PulsarClientException
  {
    if( producer == null ) 
    {
      LOGGER.error("Cannot send message - producer is null");
      throw new IllegalStateException("Producer is null");
    }
    
    LOGGER.debug("Sending message with key: {}", msgKey);
    
    try
    {
      return producer.newMessage().key(         msgKey   )
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
