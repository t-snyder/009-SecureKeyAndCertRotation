package service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eventbus.EventBusHandler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;

import pulsar.WatcherPulsarClient;
import svc.model.ChildVerticle;
import svc.model.KyberExchangeMessage;
import svc.model.ServiceCoreIF;
import svc.model.WatcherConfig;
import svc.model.WatcherIF;
import verticle.PulsarConsumerVert;
import verticle.PulsarProducerVert;
import verticle.SecretsWatcherVert;

/**
 * Creates a secrets Watcher using the Kubernetes Client API to watch for specific events which are generally tls certificate renewal events and new certificates.
 * By capturing the change events on the TLS client certificates being managed by Cert-Manager the watcher publishes updated
 * renewal certificates to the MetaData Service which publishes the renewals to client services. 
 * 
 * The Deployment of this service within a Kubernetes cluster (within the pulsar namespace) requires the following env variables to be set within the Deployment:
 *    kubeClusterName  = cluster name the ClusterWatcherVert is being deployed into. Used for publishing changes.
 *    watcherNameSpace = Namespace the watcher deployment is to. Used for validation and publishing changes.
 *    
 */
public class WatcherServiceMain
{
  private static final Logger  LOGGER         = LoggerFactory.getLogger( WatcherServiceMain.class );
  private static final String  TlsDefaultPath = "/app/certs/tls/tls.crt";  // Persistent volume mount path for TLS certs
  private static final String  ServiceId      = "watcher";
  
//  private static final String CONFIG_FILE = "config/watcher-config.json";
//  private static final int    DEFAULT_ROTATION_INTERVAL_HOURS = 24;
//  private static final int    DEFAULT_KEY_EXPIRY_HOURS = 72;

//  private static final AtomicReference<WatcherServiceMain> INSTANCE = new AtomicReference<>();

  private WatcherConfig watchConfig = null;
  private String        nameSpace   = null;
  private String        podName     = null;
  private String        tlsCertPath = null;
  
  private Vertx               vertx             = null;
  private KubernetesClient    kubeClient        = null;
  private WatcherPulsarClient pulsarClient      = null;
  private KeyExchangeService  keyExchangeSvc    = null;
  private EventBusHandler     eventBusHandler   = null;
  private List<ChildVerticle> deployedVerticles = new ArrayList<ChildVerticle>();

  private PulsarConsumerVert  consumerVert = null;
  private PulsarProducerVert  producerVert = null;
  private SecretsWatcherVert  secretsVert  = null;;

  
  public WatcherServiceMain() 
  {
    try 
    {
      // Initialize Vertx with worker pool settings
      VertxOptions options   = new VertxOptions().setWorkerPoolSize(20)
                                                 .setEventLoopPoolSize(4);
      this.vertx             = Vertx.vertx( options );

      // Create a Fabric8 Kubernetes client. The client will read in-cluster configuration
      Config apiConfig = new ConfigBuilder().build();
      kubeClient       = new KubernetesClientBuilder().withConfig( apiConfig ).build();
      LOGGER.info("WatcherServiceMain.main() - Kubernetes client initialized");

      this.nameSpace   = kubeClient.getNamespace();
      this.podName     = getPodName();
      this.watchConfig = readConfig( kubeClient, nameSpace, "watcher-config" );

      validateAttributes();
      this.tlsCertPath = TlsDefaultPath;
      watchConfig.setTlsCertPath( tlsCertPath );    

      pulsarClient = new WatcherPulsarClient( kubeClient, watchConfig, nameSpace, podName );
      if( pulsarClient == null )
      {
        throw new IllegalStateException( "Failed to create Pulsar client" );
      }
      
      keyExchangeSvc = KeyExchangeService.getInstance( vertx, pulsarClient, 0 );
    }
    catch( Exception e )
    {
      String errMsg = "Error initializing WatcherServiceMain: " + e.getMessage();
      LOGGER.error( errMsg, e );
      throw new RuntimeException( errMsg );
    }
  }

  private void validateAttributes()
  {
    if( nameSpace == null || nameSpace.isEmpty() ) 
    {
      throw new IllegalArgumentException( "Could not obtain namespace");
    }

    // Get pod name
    if( podName == null || podName.isEmpty() ) 
    {
      throw new IllegalArgumentException("POD_NAME environment variable must be set");
    }
  
    // Read configuration
    if( watchConfig == null ) 
    {
      throw new IllegalStateException("Failed to read watcher configuration");
    }
  }

  /**
   * Starts the watcher service and initializes components
   */
  public void start() 
  {
    LOGGER.info("Starting Watcher Service...");

    try 
    {
      deployPrerequisiteServices();
    }
    catch( Exception e )
    {
      String errMsg = "Fatal error initializing WatcherServiceMain: " + e.getMessage();
      LOGGER.error( errMsg, e );
      cleanupResources();
      System.exit(1);
    }
  }
  
  /**
   * Stops the watcher service and cleans up resources
   */
  public void stop() 
  {
    LOGGER.info("Stopping Watcher Service...");
    cleanupResources();
  }
 
  /**
   * Reads the configuration from the configMap in the specified namespace.
   * 
   * @param client
   *          Kubernetes client
   * @param nameSpace
   *          Namespace to read the configMap from
   * @param name
   *          Name of the configMap
   * @return WatcherConfig object containing the configuration data
   */
  private WatcherConfig readConfig( KubernetesClient client, String nameSpace, String name )
  {
    LOGGER.info("Reading configuration from configMap: {} in namespace: {}", name, nameSpace);

    ConfigMap config = client.configMaps().inNamespace( nameSpace ).withName( name ).get();

    if( config == null )
    {
      LOGGER.error( "ConfigMap not found: " + name );
      return null;
    }

    // Get the data from the secret
    Map<String, String> configData = config.getData();

    watchConfig = new WatcherConfig( configData );
    LOGGER.info("Configuration read successfully");
    
    return watchConfig;
  }

  private String getPodName()
  {
    podName = System.getenv("POD_NAME");

    if( podName == null || podName.isEmpty() ) 
    {
      LOGGER.warn("POD_NAME environment variable is not set");
    }
    
    return podName;
  }


  private void cleanupResources()
  {
    LOGGER.info("Starting cleanup of resources");

    if( keyExchangeSvc != null )
    {
      keyExchangeSvc.shutdown();
    }
    
    // First undeploy all verticles in reverse order
    for( int i = deployedVerticles.size() - 1; i >= 0; i-- )
    {
      ChildVerticle child    = deployedVerticles.get( i );
      String        vertInfo = child.vertName() + " with id = " + child.id();
      
      try 
      {
        LOGGER.info("Undeploying verticle: {}", vertInfo);
        vertx.undeploy( child.id()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS );
        LOGGER.info("Successfully undeployed verticle: {}", vertInfo);
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error while undeploying verticle {}: {}", vertInfo, e.getMessage(), e);
      }
    }
    deployedVerticles.clear();
    
    // Close the Kubernetes client
    if( kubeClient != null ) 
    {
      try 
      {
        kubeClient.close();
        LOGGER.info("Kubernetes client closed");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error while closing Kubernetes client: {}", e.getMessage(), e);
      }
    }
    
    // Finally, close Vertx
    if( vertx != null ) 
    {
      try 
      {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        LOGGER.info("Vertx instance closed");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error while closing Vertx instance: {}", e.getMessage(), e);
      }
    }

    System.exit( 1 );
  }

  private void deployPrerequisiteServices()
  {
    try
    {
      deployPulsarVerticles();

      eventBusHandler = new EventBusHandler( vertx );
      eventBusHandler.registerEventBusHandlers();
      LOGGER.info( "EventBusHandlercompleted registerEventBusHandlers" );

      requestKyberKeyExchange();
      LOGGER.info("Prerequisites completed successfully");
    } 
    catch( Exception e ) 
    {
      LOGGER.error("Error during prerequisites: {}", e.getMessage(), e);
      cleanupResources();
    }

    waitForKeyExchange().onComplete( res ->
    {
      if( res.succeeded() )
      {
        LOGGER.info("Key exchange completed successfully");
        try 
        {
          deploySecretsVerticle();
        } 
        catch( Exception e ) 
        {
          LOGGER.error("Error deploying SecretsVerticle after key exchange: {}", e.getMessage(), e);
          cleanupResources();
        }
      } 
      else
      {
        LOGGER.error( "Key exchange failed: {}", res.cause().getMessage() );
        cleanupResources();
      }
    });
  }

  private void requestKyberKeyExchange()
   throws Exception
  {
    // Kyber Key exchange
    byte[]               keyEncoded = pulsarClient.getInProcessKey().getPublicKeyEncoded();
    KyberExchangeMessage msg        = new KyberExchangeMessage( ServiceId, WatcherIF.KyberKeyRequest, keyEncoded );

    vertx.eventBus().publish("watcher.keyExchange", KyberExchangeMessage.serialize( msg ));

    LOGGER.info( "WatcherServiceMain - requestKyberKeyExchange completed - eventBus msg published" );
  }

  private Future<String> waitForKeyExchange() 
  {
    Promise<String> keyExchangePromise = Promise.promise();
    
    // Create event bus consumer with proper lifecycle management
    final long   timeoutMs = 30000; // 30 seconds timeout
    
    // Set up a timer for the timeout
    final long timerId = vertx.setTimer( timeoutMs, id -> 
    {
      if( !keyExchangePromise.future().isComplete() ) 
      {
        String msg = "Timeout waiting for key exchange completion after " + timeoutMs + "ms";
        LOGGER.error(msg);
        keyExchangePromise.fail(msg);
      }
    });
    
    // Create the consumer and register completion handler
    final MessageConsumer<byte[]> consumer = vertx.eventBus().consumer( "watcher.keyExchange.complete", (Message<byte[]> message)-> 
    {
      try 
      {
        LOGGER.info("Key exchange completion message received");
        String result = new String( message.body(), StandardCharsets.UTF_8 );
         
        // Cancel the timeout timer
        vertx.cancelTimer( timerId );
        
        // Process the completion and reply to the sender
        if( result != null && result.compareTo( ServiceCoreIF.SUCCESS ) == 0 ) 
        {
          message.reply( ServiceCoreIF.SUCCESS );
          keyExchangePromise.complete( ServiceCoreIF.SUCCESS );
          LOGGER.info("Initial Key exchange completed successfully");
        } 
        else 
        {
          String errorMsg = "Initial Key exchange failed with result: " + result;
          LOGGER.error(errorMsg);
          keyExchangePromise.fail(errorMsg);
        }
      } 
      catch( Exception e ) 
      {
        LOGGER.error("Error handling key exchange completion", e);
        keyExchangePromise.fail(e);
      }
    });
    
    // Ensure proper cleanup on completion
    keyExchangePromise.future().onComplete(ar -> 
    {
      // Unregister the consumer
      consumer.unregister().onComplete(unregResult -> 
      {
        if( unregResult.succeeded() ) 
        {
          LOGGER.debug("Key exchange consumer unregistered successfully");
        } 
        else 
        {
          LOGGER.warn("Failed to unregister key exchange consumer", unregResult.cause());
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
            LOGGER.debug("Key exchange response consumer unregistered successfully");
          }
          else 
          {
            LOGGER.warn("Failed to unregister key exchange response consumer", unregResult.cause());
          }
        });
      });
 
      if( ar.succeeded() ) 
      {
        LOGGER.info("Key exchange completed successfully");
      } 
      else 
      {
        LOGGER.error("Key exchange failed: {}", ar.cause().getMessage());
      }
    });
    
    return keyExchangePromise.future();
  }
  
  /**
   * Deploys the verticles for the WatcherService.
   * 
   * @throws Exception
   */ 
  private void deployPulsarVerticles() 
   throws Exception 
  {
    DeploymentOptions pulsarOptions = new DeploymentOptions();
    pulsarOptions.setConfig( new JsonObject().put( "worker", true ) );

    try 
    {
      consumerVert = new PulsarConsumerVert( pulsarClient, watchConfig, nameSpace );
      String deploymentId = vertx.deployVerticle( consumerVert, pulsarOptions)
                                 .toCompletionStage()
                                 .toCompletableFuture()
                                 .get(30, TimeUnit.SECONDS);
      
      deployedVerticles.add( new ChildVerticle( consumerVert.getClass().getName(), deploymentId ));
      LOGGER.info("PulsarConsumerVertVert deployed successfully: {}", deploymentId);
    } 
    catch( Exception e )
    {
      LOGGER.error("Fatal error deploying PulsarConsumerVert: {}", e.getMessage(), e);
      throw e;
    }

    try 
    {
      producerVert = new PulsarProducerVert( pulsarClient );
      String deploymentId = vertx.deployVerticle( producerVert, pulsarOptions)
                                 .toCompletionStage()
                                 .toCompletableFuture()
                                 .get(60, TimeUnit.SECONDS);
      
      deployedVerticles.add( new ChildVerticle( producerVert.getClass().getName(), deploymentId ));
      LOGGER.info("PulsarProducerVertVert deployed successfully: {}", deploymentId);
    } 
    catch( Exception e )
    {
      LOGGER.error("Fatal error deploying PulsarProducerVert: {}", e.getMessage(), e);
      throw e;
    }

    LOGGER.info("WatcherServiceMain.deployPulsarVerticles() - Consumer and Producer verticles deployed successfully");   
  }
  
  private void deploySecretsVerticle() 
   throws Exception 
  {
    DeploymentOptions pulsarOptions = new DeploymentOptions();
    pulsarOptions.setConfig( new JsonObject().put( "worker", true ) );
  
    try 
    {
      secretsVert = new SecretsWatcherVert( kubeClient, pulsarClient, watchConfig, nameSpace, podName);
      String deploymentId = vertx.deployVerticle( secretsVert, pulsarOptions)
                                 .toCompletionStage()
                                 .toCompletableFuture()
                                 .get(30, TimeUnit.SECONDS);
      
      deployedVerticles.add( new ChildVerticle( secretsVert.getClass().getName(), deploymentId ));
      LOGGER.info("SecretsWatcherVert deployed successfully: {}", deploymentId);
    } 
    catch( Exception e )
    {
      LOGGER.error("Fatal error deploying SecretsWatcherVert: {}", e.getMessage(), e);
      throw e;
    }

    LOGGER.info("WatcherServiceMain.deployVerticles() - All verticles deployed successfully");   
  }
  
  
  public static void main( String[] args )
  {
    LOGGER.info( "WatcherServiceMain.main - Starting WatcherService" );

    final WatcherServiceMain watcherSvc = new WatcherServiceMain();
    
    // Register shutdown hook for graceful shutdown
    Runtime.getRuntime().addShutdownHook( new Thread(() -> 
    {
      LOGGER.info( "Shutdown hook triggered - cleaning up resources" );
      watcherSvc.cleanupResources();
    }));
    
    watcherSvc.start();
  }
}
