package eventbus;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

import svc.model.ServiceCoreIF;
import svc.model.WatcherIF;
import svc.model.WatcherMsgHeader;

public class EventBusHandler
{
  private static final Logger LOGGER  = LoggerFactory.getLogger( EventBusHandler.class );
  private static final String Version = "1.0";
  
  private final Vertx          vertx;
  private final EventBus       eventBus;
  private final WorkerExecutor workerExecutor;

  
  public EventBusHandler( Vertx vertx )
  {
    this.vertx     = vertx;
    this.eventBus  = vertx.eventBus();
    workerExecutor = vertx.createSharedWorkerExecutor( "eventbus-worker", 5, 360000 );
  }

  public void registerHandler( String address, Handler<Message<byte[]>> handler )
  {
    eventBus.consumer( address, handler );
    LOGGER.info( "Registered handler for address: {}", address );
  }

  public void registerEventBusHandlers()
  {
    LOGGER.info( "EventBusHandler.registerEventBusHandlers() - begin" );

    vertx.eventBus().consumer("cert.publishInitial", (Message<byte[]> message) -> {
      String result = publishInitial( message );
      message.reply(result);
    });
    
    vertx.eventBus().consumer("cert.publishAdded", (Message<byte[]> message) -> {
      String result = publishAdded( message );
      message.reply(result);
    });

    vertx.eventBus().consumer("cert.publishModified", (Message<byte[]> message) -> {
      String result = publishModified( message );
      message.reply(result);
    });

    vertx.eventBus().consumer("cert.publishDeleted", (Message<byte[]> message) -> {
      String result = publishDeleted( message );
      message.reply(result);
    });
    
    LOGGER.info("EventBusHandler.registerEventBusHandlers() - Event bus handlers registered");
  }


  private String publishInitial( Message<byte[]> msg )
  {
    LOGGER.info("EventBusHandler.publishInitial() - Starting publish initial cert.");
    
    workerExecutor.executeBlocking(() -> 
    {
      try 
      {
        LOGGER.info("Processing initital cert send");

        WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.CertInitial, UUID.randomUUID().toString(), Instant.now().toString(), Version );
        JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                   .put( "msgKey",  WatcherIF.CertInitial )
                                                   .put( "msgBody", msg.body() );

        vertx.eventBus().send("pulsar.certProducer", jsonMsg );
      }
      catch( Exception e )
      {
        LOGGER.error( "EventBusHandler.publishInitial() - Error sending initial cert message with msgKey " + WatcherIF.KyberMsgKey + ". Error = " + e.getMessage() );
      }

      LOGGER.info("EventBusHandler.publishInitial() - Initital cert processed successfully");
      return ServiceCoreIF.SUCCESS;
    });  

    LOGGER.info("EventBusHandler.publishInitial() - Initial cert processed successfully");
    return ServiceCoreIF.SUCCESS;
  }

  
  private String publishAdded( Message<byte[]> msg )
  {
    LOGGER.info("EventBusHandler.publishAdded() - Starting publish added cert.");
 
    workerExecutor.executeBlocking(() -> 
    {
      try 
      {
        LOGGER.info("Processing added cert send");

        WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.CertAdded, UUID.randomUUID().toString(), Instant.now().toString(), Version );
        JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                   .put( "msgKey",  WatcherIF.CertAdded )
                                                   .put( "msgBody", msg.body() );

        vertx.eventBus().send("pulsar.certProducer", jsonMsg );
      }
      catch( Exception e )
      {
        LOGGER.error( "EventBusHandler.publishAdded() - Error sending added cert message with msgKey " + WatcherIF.KyberMsgKey + ". Error = " + e.getMessage() );
      }

      LOGGER.info("EventBusHandler.publishAdded() - Added cert processed successfully");
      return ServiceCoreIF.SUCCESS;
    });  

    LOGGER.info("EventBusHandler.publishAdded() - Added cert processed successfully");
    return ServiceCoreIF.SUCCESS;
  }

  private String publishModified( Message<byte[]> msg )
  {
    LOGGER.info("EventBusHandler.publishModified() - Starting publish modified cert.");
    
    workerExecutor.executeBlocking(() -> 
    {
      try 
      {
        LOGGER.info("Processing modified cert send");

        WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.CertModified, UUID.randomUUID().toString(), Instant.now().toString(), Version );
        JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                   .put( "msgKey",  WatcherIF.CertModified )
                                                   .put( "msgBody", msg.body() );

        vertx.eventBus().send("pulsar.certProducer", jsonMsg );
      }
      catch( Exception e )
      {
        LOGGER.error( "EventBusHandler.publishModified() - Error sending modified cert message with msgKey " + WatcherIF.KyberMsgKey + ". Error = " + e.getMessage() );
      }

      LOGGER.info("EventBusHandler.publishModified() - Modified cert processed successfully");
      return ServiceCoreIF.SUCCESS;
    });  

    LOGGER.info("EventBusHandler.publishModified() - Modified cert processed successfully");
    return ServiceCoreIF.SUCCESS;
  }

  private String publishDeleted( Message<byte[]> msg )
  {
    LOGGER.info("EventBusHandler.publishDeleted() - Starting publish deleted cert.");
    
    workerExecutor.executeBlocking(() -> 
    {
      try 
      {
        LOGGER.info("Processing deleted cert send");

        WatcherMsgHeader header  = new WatcherMsgHeader( null, WatcherIF.CertDeleted, UUID.randomUUID().toString(), Instant.now().toString(), Version );
        JsonObject       jsonMsg = new JsonObject().put( "headers", header.toJson())
                                                   .put( "msgKey",  WatcherIF.CertDeleted )
                                                   .put( "msgBody", msg.body() );

        vertx.eventBus().send("pulsar.certProducer", jsonMsg );
      }
      catch( Exception e )
      {
        LOGGER.error( "EventBusHandler.publishDeleted() - Error sending deleted cert message with msgKey " + WatcherIF.KyberMsgKey + ". Error = " + e.getMessage() );
      }

      LOGGER.info("EventBusHandler.publishDeleted() - Deleted cert processed successfully");
      return ServiceCoreIF.SUCCESS;
    });  

    LOGGER.info("EventBusHandler.publishDeleted() - Deleted cert processed successfully");
    return ServiceCoreIF.SUCCESS;
  }
 
}
