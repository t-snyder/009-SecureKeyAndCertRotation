package service;


import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

import pulsar.WatcherPulsarClient;

import org.bouncycastle.util.encoders.Hex;
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

import svc.crypto.KyberKEMCrypto;
import svc.model.KyberExchangeMessage;
import svc.model.KyberInitiator;
import svc.model.ServiceCoreIF;
import svc.model.WatcherIF;
import svc.model.WatcherMsgHeader;


/**
 * Manages the rotation of Kyber keys for the Watcher Service. Provides
 * mechanisms for scheduled key rotation and secure key storage.
 */
public class KeyExchangeService
{
  private static final Logger LOGGER = LoggerFactory.getLogger( KeyExchangeService.class );

  private static final long DEFAULT_ROTATION_INTERVAL_MS = TimeUnit.HOURS.toMillis( 12 ); 
   
   // Thread-safe maps for key storage - String = Service ID, 
  private final ConcurrentHashMap<String, KyberInitiator> activeKeys  = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, KyberInitiator> priorKeys   = new ConcurrentHashMap<>();;
  private final ConcurrentHashMap<String, KyberInitiator> inProcess   = new ConcurrentHashMap<>(); 

  private long timerId;
  private long keyRotationIntervalMs;

  // Reference to Vertx instance
  private Vertx               vertx;
  private WatcherPulsarClient pulsarClient   = null;
  private WorkerExecutor      workerExecutor = null;
  private List<String>        clientIds      = Arrays.asList( "watcher" ); 

  private static volatile KeyExchangeService instance = null;

  private KeyExchangeService() 
  {
      // Private constructor to prevent instantiation from outside the class
  }

  public static KeyExchangeService getInstance() 
  {
    if( instance == null ) 
    {
      synchronized( KeyExchangeService.class ) 
      {
        if( instance == null ) 
        {
          instance = new KeyExchangeService();
        }
      }
    }
    return instance;
  }


  /**
   * Initializes the key rotation manager and generates initial keys
   * 
   * @return Future that completes when initialization is done
   */
  public Future<Void> initialize( Vertx vertx, WatcherPulsarClient pulsarClient, long rotationIntervalMs )
  {
    Promise<Void> promise = Promise.promise();

    this.vertx          = vertx;
    this.pulsarClient   = pulsarClient;
    this.workerExecutor = vertx.createSharedWorkerExecutor("key-exchange-handler", 2);
        
    this.keyRotationIntervalMs = rotationIntervalMs > 0 ? rotationIntervalMs : DEFAULT_ROTATION_INTERVAL_MS;
 
    LOGGER.info( "KeyExchangeService starting initialize" );
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
  private KyberInitiator generateNewKeyPair( String svcId )
  {
    LOGGER.info( "Generating new Kyber key pair for secret key rotation." );
    
    try
    {
      // Auto generate the keypair
      return new KyberInitiator( svcId );
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
    LOGGER.info( "KeyExchangeService.scheduleNextRotation - start" );
    
    timerId = vertx.setTimer( keyRotationIntervalMs, id -> 
    {
      LOGGER.info( "KeyExchangeService.scheduleNextRotation - Executing scheduled key rotation" );

      workerExecutor.executeBlocking(() -> 
      {
        inProcess.clear();
        for( String svcId : clientIds ) 
        {
          initiateKyberKeyRotation(svcId);
        }
        
        return ServiceCoreIF.SUCCESS;
      }, false, result -> 
         {
           if( result.succeeded() ) 
           {
             scheduleNextRotation(); // Reschedule on event loop
           } 
           else 
           {
             String errMsg = "KeyExchangeService.scheduleNextRotation - Key rotation failed" + result.cause();
             LOGGER.error( errMsg );
             throw new RuntimeException( errMsg );
           }
         });
    });
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

    // Handler for key exchange responses - sent from PulsarConsumerVert.createKeyExchangeMessageListener
    vertx.eventBus().consumer( "watcher.keyExchange.response", (Message<byte[]> message) ->
    {
      LOGGER.info( "Received key exchange/rotation response" );
      processExchangeResponse( message );
      message.reply( new JsonObject().put( "status", "success" ));
    });
  }

  
  private String handleKeyExchange( Message<byte[]> msg )
  {
    LOGGER.info( "=====================================================================" );
    LOGGER.info("KeyExchangeService.handleKeyExchange() - Starting key exchange request");

    workerExecutor.executeBlocking(() -> 
    {
      try 
      {
        LOGGER.info("Processing key exchange request");

        WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.KyberKeyRequest, UUID.randomUUID().toString(), Instant.now().toString(), "1.0" );
        JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                   .put( "msgKey",  WatcherIF.KyberMsgKey )
                                                   .put( "msgBody", msg.body() );

        // pulsar.keyProducer consumer is in PulsarProducerVert
        vertx.eventBus().send("pulsar.keyProducer", jsonMsg );
      }
      catch( Exception e )
      {
        LOGGER.error( "KeyExchangeService.handleKeyExchange() - Error sending message with msgKey " + WatcherIF.KyberMsgKey + ". Error = " + e.getMessage() );
      }

      LOGGER.info( "===================================================================================" );
      LOGGER.info("KeyExchangeService.handleKeyExchange() - Key exchange request processed successfully");
      return ServiceCoreIF.SUCCESS;
    });  

    return ServiceCoreIF.SUCCESS;
  }
  
  private void processExchangeResponse( Message<byte[]> message )
  {
    LOGGER.info( "KeyExchangeService.processExchangeResponse - received eventBus message. Starting processing.");
    try
    {
      byte[]               msgBytes = message.body();
      KyberExchangeMessage kyberMsg = KyberExchangeMessage.deSerialize( msgBytes );

      try
      {
        LOGGER.info( "=================================================");
        LOGGER.info( "KyberExchangeMessage contents for KeyExchangeService.processExchangeResponse " );
        LOGGER.info( "svcId         = " + kyberMsg.getSvcId() );
        LOGGER.info( "eventType     = " + kyberMsg.getEventType() );
        LOGGER.info( "publicKey     = " + kyberMsg.getPublicKey() );
        LOGGER.info( "encapsulation = " + kyberMsg.getEncapsulation() );
        LOGGER.info( "Encapsulation length: " + kyberMsg.getEncapsulation().length + " bytes" );
        LOGGER.info( "Encapsulation (hex):  " + Hex.toHexString( kyberMsg.getEncapsulation() ) );
        LOGGER.info( "=================================================");
      } 
      catch( Exception e )
      {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }

      KyberInitiator key = pulsarClient.getInProcessKey(); 
      if( key == null )
      {
        String errMsg = "KeyExchangeService.processExchangeResponse Could not find KyberKey";
        LOGGER.error( errMsg );
        throw new IllegalArgumentException( errMsg );
      }

      LOGGER.info( "KeyExchangeService.processExchangeResponse - creating secret key with encapsulation." );
      byte[] sharedKey = KyberKEMCrypto.generateSecretKeyInitiator( key.getPrivateKey(), 
                                                                    kyberMsg.getEncapsulation() );
                
      if( sharedKey == null ) 
      {
        throw new IllegalStateException("KeyExchangeService.processExchangeResponse Failed to generate shared secret key");
      }
  
      LOGGER.info( "KeyExchangeService.processExchangeResponse - generated sharedKey = " + Arrays.toString( sharedKey ));
      key.setSharedSecret( sharedKey );

      putActiveKey( kyberMsg.getSvcId(), key );
       
      if( kyberMsg.getSvcId().equalsIgnoreCase( "watcher" ))
      {
        pulsarClient.setCurrentKey( key );
      
        if( kyberMsg.getEventType().equalsIgnoreCase( WatcherIF.KyberKeyResponse ))
        {
          LOGGER.info( "KeyExchangeService.processExchangeResponse sending result to eventBus 'watcher.keyExchange.complete'");
          //Consumer is WatcherServiceMain.waitForKeyExchange
          vertx.eventBus().send("watcher.keyExchange.complete", ServiceCoreIF.SUCCESS.getBytes() );
        }
      }
      
      message.reply( ServiceCoreIF.SUCCESS );
    } 
    catch( Exception e )
    {
      LOGGER.error( "Error processing key exchange response", e );
      message.reply( "failure" );
    }
  }
  
  private void initiateKyberKeyRotation( String svcId )
   throws Exception
  {
    LOGGER.info( "KeyExchangeService.initiateKyberKeyRotation starting with svcId = " + svcId );
 
    // Generate new key pair
    KyberInitiator kyberKey = generateNewKeyPair( svcId );
    inProcess.put( svcId, kyberKey );

    byte[]               keyEncoded = KyberKEMCrypto.encodePublicKey( kyberKey.getPublicKey() );
    KyberExchangeMessage msgObj     = new KyberExchangeMessage( svcId, WatcherIF.KyberRotateRequest, keyEncoded );
    byte[]               msgBytes   = KyberExchangeMessage.serialize( msgObj );
 
    WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.KyberRotateRequest, UUID.randomUUID().toString(), Instant.now().toString(), "1.0" );
    JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                               .put( "msgKey",  WatcherIF.KyberRotateKey )
                                               .put( "msgBody", msgBytes );
 
    vertx.eventBus().send("pulsar.keyProducer", jsonMsg );
    LOGGER.info( "KeyExchangeService.initiateKyberKeyRotation compeleted and send 'pulsar.keyProducer event msg for svcId = " + svcId );
  } 

  /**
   * Obtain current encryption key for pulsar messages to the requested service
   * 
   */
  public KyberInitiator getActiveKey( String svc )
  {
    LOGGER.info( "KeyExchangeService.getActiveKey() - looking for key = " + svc );

    KyberInitiator key = activeKeys.get( svc );
 
    LOGGER.info( "KeyExchangeService.putActiveKey() - key.svcId = " + key.getSvcId() );
    LOGGER.info( "KeyExchangeService.putActiveKey() - key.publicKey = " + Arrays.toString( key.getPublicKeyEncoded() ));
    LOGGER.info( "KeyExchangeService.putActiveKey() - key.sharedSecret = " + Arrays.toString( key.getSharedSecret() ));
    
    if( activeKeys.containsKey( svc ))
      return activeKeys.get( svc );
    
    return null;
  }
 
  public void putActiveKey( String svcId, KyberInitiator key )
  {
    LOGGER.info( "KeyExchangeService.putActiveKey() - svcId = " + svcId );
    LOGGER.info( "KeyExchangeService.putActiveKey() - key.publicKey = " + Arrays.toString( key.getPublicKeyEncoded() ));
    LOGGER.info( "KeyExchangeService.putActiveKey() - key.sharedSecret = " + Arrays.toString( key.getSharedSecret() ));
  
    if( activeKeys.contains( svcId )) 
    { 
      priorKeys.put( svcId, activeKeys.get( svcId ));
    }

    activeKeys.put( svcId, key );
    
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