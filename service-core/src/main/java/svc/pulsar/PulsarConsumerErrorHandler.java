package svc.pulsar;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.PulsarClientException.ConnectException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import svc.exceptions.TemporaryProcessingException;

public class PulsarConsumerErrorHandler
{
  private static final Logger logger = LoggerFactory.getLogger( PulsarConsumerErrorHandler.class );
  
  private AtomicInteger     errorCount           = new AtomicInteger(0);
  private long              lastErrorResetTime   = System.currentTimeMillis();
  private static final int  ERROR_THRESHOLD      = 10;
  private static final long ERROR_RESET_INTERVAL = 60000; // 1 minute
  
  public PulsarConsumerErrorHandler()
  {
  }
  
  /**
   * Determines if an error is unrecoverable
   */
  @SuppressWarnings( "removal" )
  public boolean isUnrecoverableError( Throwable t ) 
  {
    // Define conditions for unrecoverable errors
    return t instanceof OutOfMemoryError 
        || t instanceof ThreadDeath
        || t instanceof LinkageError
        || (t instanceof PulsarClientException && ((PulsarClientException) t).getCause() instanceof ConnectException)
        || errorCountExceedsThreshold();
  }

  public boolean errorCountExceedsThreshold() 
  {
    long currentTime = System.currentTimeMillis();

    if( currentTime - lastErrorResetTime > ERROR_RESET_INTERVAL ) 
    {
      // Reset error count after the interval
      errorCount.set(0);
      lastErrorResetTime = currentTime;
    }
    
    return errorCount.incrementAndGet() > ERROR_THRESHOLD;
  }

  /**
   * Handles message processing failures
   */
  public void handleMessageProcessingFailure( PulsarClient client, Consumer<byte[]> consumer, Message<byte[]> msg, Throwable cause )
  {
    try 
    {
      if( cause instanceof TemporaryProcessingException ) 
      {
        // negative acknowledge to trigger redelivery
        consumer.negativeAcknowledge(msg);
        logger.info("Message nack'd for redelivery");
      } 
      else 
      {
        // Dead letter queue handling for unprocessable messages
        PulsarDLQueue.sendToDeadLetterQueue( client, msg );
        consumer.acknowledge( msg );
        logger.info("Unprocessable message sent to Dead Letter Queue and acknowledged.");
      }
    } 
    catch (Exception e) 
    {
      logger.error("Error handling message processing failure", e);
    }
  }
}
