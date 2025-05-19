package verticle;


import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pulsar.WatcherPulsarClient;
import svc.model.CertificateMessageFactory;
import svc.model.ServiceCoreIF;
import svc.model.WatcherConfig;


/**
 * Creates a series of Watchers to watch for specific configured events within
 * the Kubernetes Cluster it is deployed to.
 */
public class SecretsWatcherVert extends AbstractVerticle
{
  private static final Logger LOGGER = LoggerFactory.getLogger( SecretsWatcherVert.class );

  // Kubernetes client instance
  private KubernetesClient client       = null;
  private WatcherConfig    config       = null;
  private String           nameSpace    = null;
  private List<String>     watcherNames = null;
  
  private WatcherPulsarClient       pulsarClient   = null;
  private WorkerExecutor            workerExecutor = null;
  private CertificateMessageFactory messageFactory = null;
  
  
  // Constructor injection for the KubernetesClient.
  public SecretsWatcherVert( KubernetesClient kubernetesClient, WatcherPulsarClient pulsarClient, WatcherConfig watcherConfig, String nameSpace, String podName ) throws Exception
  {
    this.client       = kubernetesClient;
    this.config       = watcherConfig;
    this.nameSpace    = nameSpace;
    this.pulsarClient = pulsarClient;

    if( config.getWatcherNames() != null  && config.getWatcherNames().length() > 0 )
    {
      String names = config.getWatcherNames();
      if( names.contains( "," ) )
      {
        this.watcherNames = List.of( names.split( "," ) );
      }
      else
      {
        this.watcherNames = List.of( names );
      }
    }
    else
    {
      String msg = "No watcher names found in config.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }
    
    if( client == null )
    {
      String msg = "Kubernetes Client can not be null.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }

    if( config == null )
    {
      String msg = "WatchConfig can not be null.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }

    if( ( nameSpace == null || nameSpace.length() == 0 ) )
    {
      String msg = "Could not obtain namespace.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }

    if( pulsarClient == null )
    {
      String msg = "WatcherPulsarClient can not be null.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }

    LOGGER.info( "SecretsWatcherVert created." );
  }

  @Override
  public void start( Promise<Void> startPromise ) 
   throws Exception
  {
    LOGGER.info( "SecretsWatcherVert.start() - Starting for namespace: " + nameSpace );
 
    workerExecutor = vertx.createSharedWorkerExecutor( "watcher-worker", 2, 360000 );
    try 
    {
      workerExecutor.executeBlocking( () -> 
      {
        try
        {
          monitorSecrets();
        } 
        catch( Exception e )
        {
          String msg = "Fatal error initializing Pulsar client. Stopping verticle.";
          LOGGER.error( msg );
          startPromise.fail( msg );
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
             startPromise.fail( msg );
           }
           return;
         }
      );
    }
    catch( Exception e )
    {
      String msg = "Error initializing Pulsar client. Stopping verticle.";
      LOGGER.error( msg );
      startPromise.fail( msg );
      throw e;
    }   

    startPromise.complete();
    LOGGER.info( "SecretsWatcherVert.start() - SecretWatcherVert started." );
  }

  @Override
  public void stop() 
   throws Exception
  {
    if( client != null )
    {
      client.close();
      LOGGER.info( "Kubernetes client closed in SecretWatcherVerticle" );
    }
    
    if( workerExecutor != null ) 
    {
      workerExecutor.close();
      LOGGER.info( "Worker executor closed in SecretWatcherVerticle" );
    }
  }

  private void monitorSecrets()
  {
    LOGGER.info( "SecretsWatcherVert.monitorSecrets() - Starting to watch for secrets." );
 
    // Check if the watcherNames list is empty or null
    if( watcherNames == null || watcherNames.isEmpty() )
    {
      LOGGER.warn( "SecretsWatcherVert.monitorSecrets() - No secrets to watch. Exiting." );
      return;
    }
    
    // Set up the watch using the Fabric8 Watcher interface.
    client.secrets().inNamespace( nameSpace ).watch( new Watcher<Secret>()
    {
      @Override
      public void eventReceived( Action action, Secret secret )
      {
        String secretName = secret.getMetadata().getName();

        if( watcherNames != null && !watcherNames.contains( secretName ) )
        {
          LOGGER.info( "Secret {} not in watcher list. Ignoring.", secretName );
          return;
        }
        
        if( secret == null || secret.getMetadata() == null )
        {
          LOGGER.warn( "Received null secret for action {}.", action );
          return;
        }

        try 
        {
          workerExecutor.executeBlocking( () ->
          {
            try
            {
              switch( action )
              {
                case ADDED:
                  LOGGER.info( "Secret ADDED: {}", secretName );
                  processAdd( secret );
                  break;
                case MODIFIED:
                  LOGGER.info( "Secret MODIFIED: {}", secretName );
                  processModified( secret );
                  break;
                case DELETED:
                  LOGGER.info( "Secret DELETED: {}", secretName );
                  processDeleted( secret );
                  break;
                case ERROR:
                  LOGGER.error( "Error event received on Secret: {}", secretName );
                  break;
                default:
                  LOGGER.warn( "Received unknown action {} for Secret: {}", action, secretName );
              }
 
              return ServiceCoreIF.SUCCESS;
            } 
            catch( Exception e ) 
            {
              LOGGER.error( "Error processing secret {}: {}", secretName, e.getMessage(), e );
              throw e;
            }
          },
          result -> 
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
          });  
        }
        catch( Exception e )
        {
          LOGGER.error( "Error processing secret {}: {}", secretName, e.getMessage() );
          throw new RuntimeException( e );
        }
      }
 
      @Override
      public void onClose( WatcherException cause )
      {
        if( cause != null )
        {
          LOGGER.error( "SecretsWatcherVert.monitor() - Watcher closed due to exception: {}", cause.getMessage(), cause );
          vertx.setTimer( 5000, id -> 
          {
            LOGGER.info("Attempting to restart secrets watcher");
           
            workerExecutor.executeBlocking( () ->
            {
              try 
              {
                monitorSecrets();
                return ServiceCoreIF.SUCCESS;
              } 
              catch( Exception e ) 
              {
                LOGGER.error("Failed to restart secrets watcher", e);
                return ServiceCoreIF.FAILURE;
              }
            },
            res -> 
            {
              if( res.failed() ) 
              {
                LOGGER.error("Failed to restart secrets watcher: {}", res.cause().getMessage());
              }
            }
          );
          }); 
        } 
        else
        {
          LOGGER.info( "Watcher closed" );
        }
      }
    });
  }
  
  private void processAdd( Secret secret )
   throws Exception
  {
    if( secret != null )
    {
      try 
      {
        // Create the message factory if not already created
        if( messageFactory == null ) 
        {
          messageFactory = new CertificateMessageFactory( pulsarClient.getCurrentKey()
                                                                      .getEncapsulatedSecretKey()
                                                                      .getEncoded()  );
        }
            
        // Create encrypted message from the secret
        byte[] encryptedMessage = messageFactory.createAddedMessage( secret );
            
        // Send via event bus
        vertx.eventBus().request("cert.publishAdded", encryptedMessage, ar -> 
        {
          if( ar.succeeded() ) 
          {
            LOGGER.info("Added cert message successfully sent.");
          } 
          else 
          {
            LOGGER.error("Failed to send Added cert message: {}", ar.cause().getMessage());
          }
        });
      } 
      catch( Exception e ) 
      {
        LOGGER.error("Error creating or sending Added cert message", e);
        throw e;
      }
      
      LOGGER.info( "Added TLS cert request message successfully sent." );
    }
  }

  private void processModified( Secret secret )
   throws Exception
  {
    if( secret != null )
    {
      try 
      {
        // Create the message factory if not already created
        if( messageFactory == null ) 
        {
          messageFactory = new CertificateMessageFactory( pulsarClient.getCurrentKey()
                                                                      .getEncapsulatedSecretKey()
                                                                      .getEncoded() );
        }
            
        // Create encrypted message from the secret
        byte[] encryptedMessage = messageFactory.createModifiedMessage( secret );
            
        // Send via event bus
        vertx.eventBus().request("cert.publishModified", encryptedMessage, ar -> 
        {
          if( ar.succeeded() ) 
          {
            LOGGER.info("Modified cert message successfully sent.");
          } 
          else 
          {
            LOGGER.error("Failed to send Modified cert message: {}", ar.cause().getMessage());
          }
        });
      } 
      catch( Exception e ) 
      {
        LOGGER.error("Error creating or sending Modified cert message", e);
        throw e;
      }
      
      LOGGER.info( "Modified cert message successfully sent." );
    }
  }

  private void processDeleted( Secret secret )
   throws Exception
  {
    if( secret != null )
    {
      try 
      {
        // Create the message factory if not already created
        if( messageFactory == null ) 
        {
          messageFactory = new CertificateMessageFactory( pulsarClient.getCurrentKey()
                                                                      .getEncapsulatedSecretKey()
                                                                      .getEncoded() );
        }
            
        // Create encrypted message from the secret
        byte[] encryptedMessage = messageFactory.createDeletedMessage( secret );
            
        // Send via event bus
        vertx.eventBus().request("cert.publishDeleted", encryptedMessage, ar -> 
        {
          if( ar.succeeded() ) 
          {
            LOGGER.info("Deleted cert message successfully sent.");
          } 
          else 
          {
            LOGGER.error("Failed to send Deleted cert message: {}", ar.cause().getMessage());
          }
        });
      } 
      catch( Exception e ) 
      {
        LOGGER.error("Error creating or sending Deleted cert message", e);
        throw e;
      }
      
      LOGGER.info( "Deleted cert message successfully sent." );
    }
  }

}
