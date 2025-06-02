package service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import java.security.PublicKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eventbus.EventBusHandler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;

// Pulsar Admin API imports
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TopicStats;

import pulsar.WatcherPulsarClient;
import svc.crypto.KyberKEMCrypto;
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
 * Enhanced with Pulsar Admin API integration for client validation and authorization management.
 * 
 * The Deployment of this service within a Kubernetes cluster (within the pulsar namespace) requires the following env variables to be set within the Deployment:
 *    kubeClusterName  = cluster name the ClusterWatcherVert is being deployed into. Used for publishing changes.
 *    watcherNameSpace = Namespace the watcher deployment is to. Used for validation and publishing changes.
 */
public class WatcherServiceMain
{
  private static final Logger  LOGGER         = LoggerFactory.getLogger( WatcherServiceMain.class );
  private static final String  TlsDefaultPath = "/app/certs/tls/tls.crt";  // Persistent volume mount path for TLS certs
  private static final String  ServiceId      = "watcher";
  
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
  private WorkerExecutor      workerExecutor    = null;

  // Pulsar Admin API components
  private PulsarAdmin         pulsarAdmin       = null;
  private PulsarAuthManager   authManager       = null;

  private PulsarConsumerVert  consumerVert = null;
  private PulsarProducerVert  producerVert = null;
  private SecretsWatcherVert  secretsVert  = null;

  
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

      // Initialize Pulsar Admin API
      initializePulsarAdmin();
      
      keyExchangeSvc = KeyExchangeService.getInstance();
      keyExchangeSvc.initialize( vertx, pulsarClient, 0 );
    }
    catch( Exception e )
    {
      String errMsg = "Error initializing WatcherServiceMain: " + e.getMessage();
      LOGGER.error( errMsg, e );
      throw new RuntimeException( errMsg );
    }
  }

  /**
   * Initialize Pulsar Admin API client using configuration from Kubernetes
   */
  private void initializePulsarAdmin() throws Exception 
  {
    LOGGER.info("Initializing Pulsar Admin API client");
    
    try 
    {
      // Read Pulsar admin configuration from Kubernetes Secret
      PulsarAdminConfig adminConfig = readPulsarAdminConfig();
      
      PulsarAdminBuilder adminBuilder = PulsarAdmin.builder()
          .serviceHttpUrl(adminConfig.getServiceHttpUrl());

      // Configure authentication if provided
      if (adminConfig.hasAuthentication()) 
      {
        if (adminConfig.isTokenAuth()) 
        {
          adminBuilder.authentication(
              AuthenticationFactory.token(adminConfig.getAuthToken())
          );
        } 
        else if (adminConfig.isTlsAuth()) 
        {
          adminBuilder.authentication(
              AuthenticationFactory.createTLS(
                  adminConfig.getTlsCertPath(), 
                  adminConfig.getTlsKeyPath()
              )
          );
        }
      }

      // Configure TLS if needed
      if (adminConfig.isTlsEnabled()) 
      {
        adminBuilder.tlsTrustCertsFilePath(adminConfig.getTlsTrustCertsPath())
                   .allowTlsInsecureConnection(adminConfig.isAllowTlsInsecureConnection())
                   .enableTlsHostnameVerification(adminConfig.isTlsHostnameVerificationEnabled());
      }

      pulsarAdmin = adminBuilder.build();
      
      // Initialize auth manager with Pulsar Admin and Kubernetes client
      authManager = new PulsarAuthManager(pulsarAdmin, kubeClient, nameSpace);
      
      LOGGER.info("Pulsar Admin API client initialized successfully");
      
      // Verify connection and setup initial authorization
      setupInitialAuthorization();
    } 
    catch (Exception e) 
    {
      LOGGER.error("Failed to initialize Pulsar Admin API: {}", e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Read Pulsar Admin configuration from Kubernetes ConfigMap and Secret
   */
  private PulsarAdminConfig readPulsarAdminConfig() throws Exception 
  {
    LOGGER.info("Reading Pulsar Admin configuration from Kubernetes");
    
    // Read configuration from ConfigMap
    ConfigMap adminConfigMap = kubeClient.configMaps()
        .inNamespace(nameSpace)
        .withName("pulsar-admin-config")
        .get();
        
    if (adminConfigMap == null) 
    {
      throw new IllegalStateException("Pulsar admin ConfigMap 'pulsar-admin-config' not found");
    }

    Map<String, String> configData = adminConfigMap.getData();
    PulsarAdminConfig adminConfig = new PulsarAdminConfig(configData);

    // Read sensitive data from Secret if authentication is enabled
    if (adminConfig.hasAuthentication()) 
    {
      Secret adminSecret = kubeClient.secrets()
          .inNamespace(nameSpace)
          .withName("pulsar-admin-secret")
          .get();
          
      if (adminSecret != null) 
      {
        Map<String, String> secretData = adminSecret.getStringData();
        if (secretData != null) 
        {
          adminConfig.setSecretData(secretData);
        }
      }
    }

    return adminConfig;
  }

  /**
   * Setup initial authorization for the watcher service
   */
  private void setupInitialAuthorization() throws Exception 
  {
    LOGGER.info("Setting up initial Pulsar authorization for watcher service");
    
    try 
    {
      // Verify connection
      List<String> clusters = pulsarAdmin.clusters().getClusters();
      LOGGER.info("Connected to Pulsar clusters: {}", clusters);

      // Setup tenant and namespace permissions for watcher
      authManager.ensureWatcherPermissions(ServiceId);
      
      // Register event bus handlers for auth management
      registerAuthEventHandlers();
      
      LOGGER.info("Initial Pulsar authorization setup completed");
    } 
    catch (Exception e) 
    {
      LOGGER.error("Failed to setup initial authorization: {}", e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Register event bus handlers for Pulsar authentication/authorization management
   */
  private void registerAuthEventHandlers() 
  {
    // Handler for client authorization requests
    vertx.eventBus().consumer("pulsar.auth.authorize", (Message<JsonObject> message) -> {
      JsonObject authRequest = message.body();
      
      workerExecutor.executeBlocking(() -> {
        try 
        {
          String clientId = authRequest.getString("clientId");
          String topic = authRequest.getString("topic");
          String action = authRequest.getString("action"); // "produce" or "consume"
          
          boolean authorized = authManager.authorizeClient(clientId, topic, action);
          
          JsonObject response = new JsonObject()
              .put("authorized", authorized)
              .put("clientId", clientId)
              .put("topic", topic)
              .put("action", action);
              
          message.reply(response);
          
          return ServiceCoreIF.SUCCESS;
        } 
        catch (Exception e) 
        {
          LOGGER.error("Error processing authorization request", e);
          message.fail(500, e.getMessage());
          return ServiceCoreIF.FAILURE;
        }
      });
    });

    // Handler for retrieving client stats
    vertx.eventBus().consumer("pulsar.stats.client", (Message<JsonObject> message) -> {
      JsonObject statsRequest = message.body();
      
      workerExecutor.executeBlocking(() -> {
        try 
        {
          String topic = statsRequest.getString("topic");
          JsonObject stats = authManager.getTopicStats(topic);
          message.reply(stats);
          return ServiceCoreIF.SUCCESS;
        } 
        catch (Exception e) 
        {
          LOGGER.error("Error retrieving topic stats", e);
          message.fail(500, e.getMessage());
          return ServiceCoreIF.FAILURE;
        }
      });
    });

    // Handler for managing client permissions
    vertx.eventBus().consumer("pulsar.auth.manage", (Message<JsonObject> message) -> {
      JsonObject mgmtRequest = message.body();
      
      workerExecutor.executeBlocking(() -> {
        try 
        {
          String operation = mgmtRequest.getString("operation"); // "grant", "revoke", "list"
          String clientId = mgmtRequest.getString("clientId");
          String namespace = mgmtRequest.getString("namespace");
          
          JsonObject result = authManager.manageClientPermissions(operation, clientId, namespace, mgmtRequest);
          message.reply(result);
          
          return ServiceCoreIF.SUCCESS;
        } 
        catch (Exception e) 
        {
          LOGGER.error("Error managing client permissions", e);
          message.fail(500, e.getMessage());
          return ServiceCoreIF.FAILURE;
        }
      });
    });

    LOGGER.info("Pulsar authentication event bus handlers registered");
  }

  /**
   * Validate client access using both Kubernetes and Pulsar Admin APIs
   */
  public CompletableFuture<Boolean> validateClientAccess(String clientId, String topic, String action) 
  {
    return CompletableFuture.supplyAsync(() -> {
      try 
      {
        // First check if client exists in Kubernetes (ServiceAccount, Pod, etc.)
        boolean kubernetesValid = validateKubernetesClient(clientId);
        if (!kubernetesValid) 
        {
          LOGGER.warn("Client {} not found in Kubernetes namespace {}", clientId, nameSpace);
          return false;
        }

        // Then check Pulsar permissions
        boolean pulsarAuthorized = authManager.authorizeClient(clientId, topic, action);
        if (!pulsarAuthorized) 
        {
          LOGGER.warn("Client {} not authorized for {} on topic {}", clientId, action, topic);
          return false;
        }

        LOGGER.info("Client {} validated successfully for {} on topic {}", clientId, action, topic);
        return true;
      } 
      catch (Exception e) 
      {
        LOGGER.error("Error validating client access for {}: {}", clientId, e.getMessage(), e);
        return false;
      }
    });
  }

  /**
   * Validate client exists in Kubernetes cluster
   */
  private boolean validateKubernetesClient(String clientId) 
  {
    try 
    {
      // Check if ServiceAccount exists
      if (kubeClient.serviceAccounts().inNamespace(nameSpace).withName(clientId).get() != null) 
      {
        return true;
      }

      // Check if Pod with matching label exists
      if (!kubeClient.pods().inNamespace(nameSpace)
          .withLabel("app", clientId).list().getItems().isEmpty()) 
      {
        return true;
      }

      // Check if client is in approved clients ConfigMap
      ConfigMap approvedClients = kubeClient.configMaps()
          .inNamespace(nameSpace)
          .withName("approved-pulsar-clients")
          .get();
          
      if (approvedClients != null) 
      {
        String clients = approvedClients.getData().get("clients");
        if (clients != null && clients.contains(clientId)) 
        {
          return true;
        }
      }

      return false;
    } 
    catch (Exception e) 
    {
      LOGGER.error("Error validating Kubernetes client {}: {}", clientId, e.getMessage(), e);
      return false;
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

    workerExecutor = vertx.createSharedWorkerExecutor( "main", 3 );
   
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

    workerExecutor.executeBlocking( () -> 
    {
      try 
      {
        if( keyExchangeSvc != null )
        {
          keyExchangeSvc.shutdown();
        }

        // Close Pulsar Admin client
        if (pulsarAdmin != null) 
        {
          try 
          {
            pulsarAdmin.close();
            LOGGER.info("Pulsar Admin client closed");
          } 
          catch (Exception e) 
          {
            LOGGER.warn("Error closing Pulsar Admin client: {}", e.getMessage(), e);
          }
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
      }
      catch( Exception e )
      {
        LOGGER.error( "Error encountered cleaning up resourcecs. Error = " + e.getMessage());
      }

      return ServiceCoreIF.SUCCESS;
    }); 
 
    System.exit( 1 );
  }

  private void deployPrerequisiteServices()
  {
    workerExecutor.executeBlocking( () -> 
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
 
      return ServiceCoreIF.SUCCESS;
    });
  }

  private void requestKyberKeyExchange()
   throws Exception
  {
    LOGGER.info( "===============================================================================" );
    LOGGER.info( "WatcherServiceMain.requestKyberKeyExchange starting" );

    if( pulsarClient.getInProcessKey() == null )
    {
      String errMsg = "WatcherServiceMain.requestKyberKeyExchagne found null inProcessKey ";
      LOGGER.error( errMsg );
      throw new Exception( errMsg );
    }
    
    byte[] keyBytes = KyberKEMCrypto.encodePublicKey( pulsarClient.getInProcessKey().getPublicKey() );
    LOGGER.info( "WatcherServiceMain.requestKyberKeyExchange - publicKey = " + keyBytes );
 
    KyberExchangeMessage msg = new KyberExchangeMessage( ServiceId, WatcherIF.KyberKeyRequest, keyBytes );

    // watcher.keyExchange defined within KeyExchagneService
    vertx.eventBus().publish("watcher.keyExchange", KyberExchangeMessage.serialize( msg ));

    LOGGER.info( "===============================================================================" );
    LOGGER.info( "WatcherServiceMain - requestKyberKeyExchange completed - eventBus msg published" );
  }

  private Future<String> waitForKeyExchange() 
  {
    Promise<String> promise = Promise.promise();
    
    MessageConsumer<byte[]> consumer = vertx.eventBus().consumer("watcher.keyExchange.complete", message -> 
    {
      String result = new String(message.body(), StandardCharsets.UTF_8);
      if( ServiceCoreIF.SUCCESS.equals( result ))
      {
        promise.complete(result);
      } 
      else 
      {
        promise.fail("Key exchange failed: " + result);
      }
    });
    
    // Set timeout
    vertx.setTimer( 30000, id -> 
    {
      if( !promise.future().isComplete() ) 
      {
        promise.fail("Timeout waiting for key exchange");
      }
    });
    
    // Cleanup on completion
    promise.future().onComplete(ar -> consumer.unregister());
    
    return promise.future();
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
  
    workerExecutor.executeBlocking(() -> 
    {
      LOGGER.info( "WatcherServiceMain.deploySecretsVerticle - Start inside workerExecutor" );
      try 
      {
        secretsVert = new SecretsWatcherVert( kubeClient, keyExchangeSvc, pulsarClient, watchConfig, nameSpace, podName);
        String deploymentId = vertx.deployVerticle( secretsVert, pulsarOptions)
                                   .toCompletionStage()
                                   .toCompletableFuture()
                                   .get(30, TimeUnit.SECONDS);
        LOGGER.info( "WatcherServiceMain.deploySecretsVerticle - After secretsVert deployment completed." );
      
        deployedVerticles.add( new ChildVerticle( secretsVert.getClass().getName(), deploymentId ));
        LOGGER.info("SecretsWatcherVert deployed successfully: {}", deploymentId);
      } 
      catch( Exception e )
      {
        LOGGER.error("Fatal error deploying SecretsWatcherVert: {}", e.getMessage(), e);
        throw e;
      }

      LOGGER.info("WatcherServiceMain.deployVerticles() - All verticles deployed successfully");   
 
      return ServiceCoreIF.SUCCESS;
    });
  }

  // Getters for accessing components
  public PulsarAdmin getPulsarAdmin() {
    return pulsarAdmin;
  }

  public PulsarAuthManager getAuthManager() {
    return authManager;
  }

  public KubernetesClient getKubernetesClient() {
    return kubeClient;
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
