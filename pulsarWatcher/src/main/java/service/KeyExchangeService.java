package service;


import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import pulsar.WatcherPulsarClient;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import svc.model.KyberExchangeMessage;
import svc.model.KyberKey;
import svc.model.ServiceCoreIF;
import svc.model.WatcherIF;
import svc.model.WatcherMsgHeader;
import svc.utils.MLKEMUtils;


/**
 * Manages the rotation of Kyber keys for the Watcher Service. Provides
 * mechanisms for scheduled key rotation and secure key storage.
 */
public class KeyExchangeService
{
  private static final Logger LOGGER = LoggerFactory.getLogger( KeyExchangeService.class );

  private static final long DEFAULT_ROTATION_INTERVAL_MS = TimeUnit.HOURS.toMillis( 12 ); 
   
   // Thread-safe maps for key storage - String = Service ID, 
  private final ConcurrentHashMap<String, KyberKey> activeKeys  = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, KyberKey> priorKeys   = new ConcurrentHashMap<>();;
  private final ConcurrentHashMap<String, KyberKey> inProcess   = new ConcurrentHashMap<>(); 

  // State tracking
//  private boolean initialized  = false;
//  private boolean shuttingDown = false;
  private long    timerId;

  // Key rotation configuration
  private long keyRotationIntervalMs;

  // Reference to Vertx instance
  private final Vertx         vertx;
  private WatcherPulsarClient pulsarClient   = null;
  private WorkerExecutor      workerExecutor = null;
  private List<String>        clientIds      = Arrays.asList( "metadatasvc" ); 
  
  private static volatile KeyExchangeService uniqueInstance;

  // Public method to provide access to the instance
  public static KeyExchangeService getInstance( Vertx vertx, WatcherPulsarClient pulsarClient, long rotationIntervalMs ) 
  {
    if( uniqueInstance == null ) 
    {
      synchronized( KeyExchangeService.class ) 
      {
        if( uniqueInstance == null ) 
        {
          uniqueInstance = new KeyExchangeService( vertx, pulsarClient, rotationIntervalMs );
        }
      }
    }
    return uniqueInstance;
  }
  
  private KeyExchangeService( Vertx vertx, WatcherPulsarClient pulsarClient, long rotationIntervalMs )
  {
    this.vertx        = vertx;
    this.pulsarClient = pulsarClient;
    
    this.keyRotationIntervalMs = rotationIntervalMs > 0 ? rotationIntervalMs : DEFAULT_ROTATION_INTERVAL_MS;
  }


  /**
   * Initializes the key rotation manager and generates initial keys
   * 
   * @return Future that completes when initialization is done
   */
  public Future<Void> initialize()
  {
    Promise<Void> promise = Promise.promise();

    try
    {
      // Register event bus handlers for key rotation events
      registerEventBusHandlers();

      // Schedule periodic key rotation
      scheduleNextRotation();

      promise.complete();
    } 
    catch( Exception e )
    {
      LOGGER.error( "Failed to initialize KeyRotationManager", e );
      promise.fail( e );
    }

    return promise.future();
  }

  /**
   * Generates a new Kyber key pair
   */
  private KyberKey generateNewKeyPair( String svcId )
  {
    LOGGER.info( "Generating new Kyber key pair for secret key rotation." );
    
    try
    {
      return new KyberKey( svcId );
    } 
    catch( NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e )
    {
      String errMsg = "Error generating Kyber Key Pair. Error = " + e.getMessage();
      LOGGER.error( errMsg );
      
      throw new RuntimeException( errMsg );
    }
  }

  /**
   * Schedule the next key rotation
   */
  private void scheduleNextRotation()
  {
    timerId = vertx.setTimer( keyRotationIntervalMs, id -> 
    {
      LOGGER.info( "Executing scheduled key rotation" );

      try
      {
        inProcess.clear();
        for( String svcId : clientIds )
        {
          inititateKyberKeyRotation( svcId );
        }
      }
      catch( Exception e )
      {
        
      }

      // Schedule the next rotation
      scheduleNextRotation(); // Recursive call
    } );
  }

  /**
   * Register event bus handlers for key rotation events
   */
  private void registerEventBusHandlers()
  {
    vertx.eventBus().consumer("watcher.keyExchange", (Message<byte[]> message) -> {
      String result = handleKeyExchange( message );
      message.reply(result);
    });
 
   // Handler for rotation requests
    vertx.eventBus().consumer( "watcher.keyRotation.request", (Message<byte[]> message) -> 
    {
      LOGGER.info( "Received key rotation request" );
      generateEncapsulation( message );
      message.reply( new JsonObject().put( "status", "success" ));
    });

    // Handler for key exchange responses
    vertx.eventBus().consumer( "watcher.keyExchange.response", (Message<byte[]> message) ->
    {
      LOGGER.info( "Received key exchange/rotation response" );
      processExchangeResponse( message );
      message.reply( new JsonObject().put( "status", "success" ));
    } );
  }

  private String handleKeyExchange( Message<byte[]> msg )
  {
    LOGGER.info("EventBusHandler.handleKeyExchange() - Starting key exchange request");

    workerExecutor.executeBlocking(() -> 
    {
      try 
      {
        LOGGER.info("Processing key exchange request");

        WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.KyberKeyRequest, UUID.randomUUID().toString(), Instant.now().toString(), "1.0" );
        JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                   .put( "msgKey",  WatcherIF.KyberMsgKey )
                                                   .put( "msgBody", msg.body() );

        vertx.eventBus().send("pulsar.keyProducer", jsonMsg );
      }
      catch( Exception e )
      {
        LOGGER.error( "EventBusHandler.handleKeyExchange() - Error sending message with msgKey " + WatcherIF.KyberMsgKey + ". Error = " + e.getMessage() );
      }

      LOGGER.info("EventBusHandler.handleKeyExchange() - Key exchange request processed successfully");
      return ServiceCoreIF.SUCCESS;
    });  

    LOGGER.info("EventBusHandler.handleKeyExchange() - Key exchange request processed successfully");
    return ServiceCoreIF.SUCCESS;
  }
  
  private void processExchangeResponse( Message<byte[]> message )
  {
    try
    {
      byte[]               msgBytes = message.body();
      KyberExchangeMessage kyberMsg = KyberExchangeMessage.deserialize( msgBytes );

      if( kyberMsg.getEncapsulation() == null || kyberMsg.getEncapsulation().length == 0 ) 
      {
        String errMsg = "KeyExchangeHandler.handleKeyExchangeResponse() Received empty key exchange response";
        LOGGER.error( errMsg );
        throw new IllegalArgumentException( errMsg );
      }

      KyberKey key = inProcess.get( kyberMsg.getSvcId() );
      if( key == null )
      {
        String errMsg = "KeyRotationManager.registerEventBusHandlers() Received empty key exchange response";
        LOGGER.error( errMsg );
        throw new IllegalArgumentException( errMsg );
      }

      SecretKeyWithEncapsulation sharedKeyWithEnc = MLKEMUtils.generateSecretKeyReceiver(
                                                                 key.getPrivateKey(), 
                                                                 kyberMsg.getEncapsulation() );
                
      if( sharedKeyWithEnc == null ) 
      {
        throw new IllegalStateException("Failed to generate shared secret key");
      }
  
      key.setEncapsulatedKey( sharedKeyWithEnc );

      if( activeKeys.contains( kyberMsg.getSvcId() )) 
      { 
        priorKeys.put( kyberMsg.getSvcId(), activeKeys.get( kyberMsg.getSvcId() ));
      }

      activeKeys.put( kyberMsg.getSvcId(), key );
       
      if( kyberMsg.getSvcId().equalsIgnoreCase( "watcher" ))
      {
        pulsarClient.setCurrentKey( key );
      
        if( kyberMsg.getEventType().equalsIgnoreCase( WatcherIF.KyberKeyResponse ))
        {
          vertx.eventBus().send("watcher.keyExchange.complete", ServiceCoreIF.SUCCESS );
        }
      }
      
      message.reply( "success" );
    } 
    catch( Exception e )
    {
      LOGGER.error( "Error processing key exchange response", e );
      message.reply( "failure" );
    }
  }
  
  private void inititateKyberKeyRotation( String svcId )
   throws Exception
  {
    workerExecutor = vertx.createSharedWorkerExecutor( "watcher-worker", 2, 360000 );

    try 
    {
      workerExecutor.executeBlocking( () -> 
      {
        try
        {
          // Generate new key pair
          KyberKey kyberKey = generateNewKeyPair( svcId );
          inProcess.put( svcId, kyberKey );

          byte[]               keyEncoded = MLKEMUtils.encodePublicKey( kyberKey.getPublicKey() );
          KyberExchangeMessage msgObj     = new KyberExchangeMessage( svcId, WatcherIF.KyberRotateRequest, keyEncoded );
          byte[]               msgBytes   = KyberExchangeMessage.serialize( msgObj );
 
          WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.KyberRotateRequest, UUID.randomUUID().toString(), Instant.now().toString(), "1.0" );
          JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                     .put( "msgKey",  WatcherIF.KyberRotateKey )
                                                     .put( "msgBody", msgBytes );

          vertx.eventBus().send("pulsar.keyProducer", jsonMsg );
        } 
        catch( Exception e )
        {
          String msg = "Fatal error initializing Pulsar client. Stopping verticle.";
          LOGGER.error( msg );
          throw e;
        }
           
        return ServiceCoreIF.SUCCESS;
      }, result -> 
         {
           if( result.succeeded() )
           {
             LOGGER.info( "Pulsar client initialized successfully." );
           }
           else
           {
             String msg = "Error initializing Pulsar client. Stopping verticle.";
             LOGGER.error( msg );
           }
           return;
         }
      );
    }
    catch( Exception e )
    {
      String msg = "Error initializing Pulsar client. Stopping verticle.";
      LOGGER.error( msg );
      throw e;
    }   

    LOGGER.info( "WatcherServiceMain - requestKyberKeyRotation completed - eventBus msg published" );
  }

/**  
  private Future<String> waitForKeyExchange() 
  {
    Promise<String> keyRotationPromise = Promise.promise();
    
    // Create event bus consumer with proper lifecycle management
    final String address   = "watcher.keyRotation.complete";
    final long   timeoutMs = 30000; // 30 seconds timeout
    
    // Set up a timer for the timeout
    final long timerId = vertx.setTimer( timeoutMs, id -> 
    {
      if( !keyRotationPromise.future().isComplete() ) 
      {
        String msg = "Timeout waiting for key rotation completion after " + timeoutMs + "ms";
        LOGGER.error(msg);
        keyRotationPromise.fail(msg);
      }
    });
    
    // Create the consumer and register completion handler
    final MessageConsumer<String> consumer = vertx.eventBus().consumer( address, message -> 
    {
      try 
      {
        LOGGER.info("Key rotation completion message received");
        String result = message.body();
        
        // Cancel the timeout timer
        vertx.cancelTimer( timerId );
        
        // Process the completion and reply to the sender
        if( ServiceCoreIF.SUCCESS.equals( result )) 
        {
          message.reply(ServiceCoreIF.SUCCESS);
          keyRotationPromise.complete(result);
          LOGGER.info("Key exchange completed successfully");
        } 
        else 
        {
          String errorMsg = "Key exchange failed with result: " + result;
          LOGGER.error(errorMsg);
          keyRotationPromise.fail(errorMsg);
        }
      } 
      catch( Exception e ) 
      {
        LOGGER.error("Error handling key exchange completion", e);
        keyRotationPromise.fail(e);
      }
    });
    
    // Ensure proper cleanup on completion
    keyRotationPromise.future().onComplete(ar -> 
    {
      // Unregister the consumer
      consumer.unregister().onComplete(unregResult -> 
      {
        if( unregResult.succeeded() ) 
        {
          LOGGER.debug("Key rotation consumer unregistered successfully");
        } 
        else 
        {
          LOGGER.warn("Failed to unregister key rotation consumer", unregResult.cause());
        }
      });
      
      // Always cancel the timer to prevent memory leaks
      vertx.cancelTimer(timerId);
      
      vertx.runOnContext(v -> 
      {
        consumer.unregister().onComplete(unregResult -> 
        {
          if( unregResult.succeeded() ) 
          {
            LOGGER.debug("Key rotation response consumer unregistered successfully");
          }
          else 
          {
            LOGGER.warn("Failed to unregister key rotation response consumer", unregResult.cause());
          }
        });
      });
 
      if( ar.succeeded() ) 
      {
        LOGGER.info("Key rotation completed successfully");
      } 
      else 
      {
        LOGGER.error("Key rotation failed: {}", ar.cause().getMessage());
      }
    });
    
    return keyRotationPromise.future();
  }
**/
/**  
  public void handleKeyExchangeResponse( Message<byte[]> msg ) 
  {
    LOGGER.info("KeyExchangeHandler - Processing key exchange response");

    try 
    {
      String eventType     = msg.getProperty( WatcherIF.WatcherHeaderEventType );
      byte[] kyberMsgBytes = msg.getData();

      KyberExchangeMessage kyberMsg = KyberExchangeMessage.deserialize( kyberMsgBytes );
      
      if( kyberMsg.getEncapsulation() == null || kyberMsg.getEncapsulation().length == 0 ) 
      {
        String errMsg = "KeyExchangeHandler.handleKeyExchangeResponse() Received empty key exchange response";
        LOGGER.error( errMsg );
        throw new IllegalArgumentException( errMsg );
      }

      SecretKeyWithEncapsulation sharedKeyWithEnc = MLKEMUtils.generateSecretKeyReceiver(
                                                                 watcherClient.getPrivateKey(), 
                                                                 kyberMsg.getEncapsulation() );
                
      if( sharedKeyWithEnc == null ) 
      {
        throw new IllegalStateException("Failed to generate shared secret key");
      }

      
      watcherClient.setSharedSecretKey(sharedKeyWithEnc);
      LOGGER.info("Key exchange completed successfully");

      if( eventType.contentEquals( WatcherIF.KyberResponse ))
      {
        // Notify via event bus that key exchange is complete
        vertx.eventBus().publish( "watcher.keyExchange.complete", kyberMsgBytes );
      }
      else if( eventType.contentEquals( WatcherIF.KyberResponse ))
      {
        // Notify via event bus that key rotation is complete
        vertx.eventBus().publish( "watcher.keyRotation.complete", kyberMsgBytes );
      }
    } 
    catch( NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException | ClassNotFoundException | IOException e ) 
    {
      String errorMsg = "Cryptographic error during key exchange: " + e.getMessage();
      LOGGER.error(errorMsg);
      throw new RuntimeException(errorMsg, e);
    }
  }
**/  
  private void generateEncapsulation( Message<byte[]> msg )
  {
    
  }

  /**
   * Shutdown the key rotation manager
   */
  public void shutdown()
  {
    // Cancel scheduled rotation timer
    if( timerId > 0 )
    {
      vertx.cancelTimer( timerId );
    }
  }
}