package svc.pulsar;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provide functionality to forward an unprocessable message to a pulsar dead letter queue.
 */
public class PulsarDLQueue
{
  private static final Logger           logger      = LoggerFactory.getLogger( PulsarDLQueue.class );
  private static       Producer<byte[]> dlqProducer = null;
  
  /**
   * Sends problematic messages to a dead letter queue
   */
  public static void sendToDeadLetterQueue( PulsarClient client, Message<byte[]> msg )
  {
    try 
    {
      if( dlqProducer == null ) 
      {
        initializeDLQProducer( client, msg.getTopicName() );
      }
          
      dlqProducer.newMessage().properties( msg.getProperties() )
                              .property("original-topic", msg.getTopicName())
                              .property("failure-timestamp", String.valueOf(System.currentTimeMillis()))
                              .value(msg.getData())
                              .send();
            
      logger.info("Message sent to DLQ: " + msg.getMessageId());
    } catch( Exception e )
    {
      logger.error("Failed to send message to DLQ", e);
    }
  }

  /**
   * Initializes the Dead Letter Queue producer
   */
  private static void initializeDLQProducer( PulsarClient client, String origTopic ) 
   throws PulsarClientException 
  {
    try 
    {
      String dlqTopic = origTopic + ".dlq";
        
      dlqProducer = client.newProducer().topic( dlqTopic )
                                        .producerName("dlq-producer-" + UUID.randomUUID().toString())
                                        .enableBatching(true)
                                        .batchingMaxPublishDelay( 1, TimeUnit.SECONDS )
                                        .blockIfQueueFull(true)
                                        .maxPendingMessages(1000)
                                        .create();
        
      logger.info("DLQ Producer initialized for topic: " + dlqTopic);
    } 
    catch( PulsarClientException e ) 
    {
      logger.error("Failed to initialize DLQ producer: " + e.getMessage(), e);
      throw e;
    } 
    catch( Exception e ) 
    {
      logger.error("Unexpected error initializing DLQ producer: " + e.getMessage(), e);
      throw new PulsarClientException(e);
    }
  }
}