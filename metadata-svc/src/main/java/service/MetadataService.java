package service;

import io.fabric8.kubernetes.client.KubernetesClient;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import controller.PulsarTLSClient;
import svc.model.ChildVerticle;
import svc.utils.ConfigReader;

import verticle.MetadataServiceVert;


public class MetadataService
{
  private static final Logger LOGGER      = LoggerFactory.getLogger( MetadataService.class );
  private static final String ConfEnvKey  = "CONFIG_MAP_NAME"; 
  private static final String DefaultConf = "metadata-svc-config";
  private static ConfigReader confReader  = new ConfigReader();

  private Vertx               vertx       = null;
  private KubernetesClient    kubeClient  = null;
  private String              nameSpace   = null;
  private String              configName  = null;
  private Map<String, String> config      = null;

  private PulsarClient        pulsarClient      = null;
  private List<ChildVerticle> deployedVerticles = new ArrayList<ChildVerticle>();

  private MetadataServiceVert mdVert     = null;

  
  // Static inner helper class responsible for holding the Singleton instance
  private static class SingletonHelper 
  {
    // The Singleton instance is created when the class is loaded
    private static final MetadataService INSTANCE = new MetadataService();
  }

  // Global access point to get the Singleton instance
  public static MetadataService getInstance()
  {
    return SingletonHelper.INSTANCE;
  }

  private MetadataService()
  {
  }
 
  public void init( Vertx vertx )
   throws Exception
  {
    this.vertx = vertx;
 
    this.kubeClient = confReader.getKubeClient();
    this.nameSpace  = confReader.getNamespace();
    this.configName = confReader.getConfigMapNameFromEnv( ConfEnvKey );
    
    if( configName == null )
      configName = DefaultConf;
    
    LOGGER.info( "*** Namespace = " + nameSpace + "; Config map name = " + configName );
    
    this.config = confReader.getConfigProperties( configName );

    verifyConfig(); 
    
    if( config == null )
    {
      String msg = "Could not obtain configuration map.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }

    pulsarClient = new PulsarTLSClient( config ).getPulsarClient();
    
    try
    {
      mdVert = new MetadataServiceVert( this, config, nameSpace, pulsarClient );
    } 
    catch( Exception e )
    {
      String msg = "Fatal error creating MetadataServiceVert - system will stop: " + e.getMessage();
      LOGGER.error(msg, e);
      cleanupResources( );
      throw new RuntimeException(msg, e);
    }

    try 
    {
      String mdVertId = vertx.deployVerticle( mdVert )
                             .toCompletionStage()
                             .toCompletableFuture().get();
      deployedVerticles.add( new ChildVerticle( mdVert.getClass().getName(), mdVertId ));
      LOGGER.info( "MetadataServiceVert deployment id is: " + mdVertId);
    }
    catch( Exception e ) 
    {
      String msg = "Fatal initialization error in MetadataService: " + e.getMessage();
      LOGGER.error(msg, e);
      cleanupResources();
      throw new RuntimeException( msg, e );
    }
  }

  public List<ChildVerticle> getDeployedVerticles() 
  {
    return deployedVerticles;
  }
  
  private void verifyConfig()
  throws Exception
  {
    if( config == null )
    {
      String msg = "config can not be null.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }

    if(( nameSpace == null || nameSpace.length() == 0 ) )
    {
      String msg = "Could not obtain current namespace.";
      LOGGER.error( msg );
      throw new Exception( msg );
    }
  }

  public void cleanupResources() 
  {
    LOGGER.info("Starting cleanup of MetadataServiceVert resources");
    
    // First undeploy all verticles
    if( !deployedVerticles.isEmpty() && vertx != null ) 
    {
      for( ChildVerticle child : deployedVerticles ) 
      {
        String vertInfo = new String( child.vertName() + " with id = " + child.id() );
  
        try 
        {
          vertx.undeploy( child.id() ).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
          LOGGER.info(" Successfully undeployed child verticle: " + vertInfo );
        } 
        catch( Exception e ) 
        {
          LOGGER.warn("Error while undeploying child verticle " + vertInfo + ": " + e.getMessage(), e);
        }
      }  
    }
    deployedVerticles.clear();
  
    if( kubeClient != null ) 
    {
      try 
      {
        kubeClient.close();
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error while closing kubeClient: " + e.getMessage(), e);
      }
    }
    
    // Finally, close the Vertx instance
    if( vertx != null )
    {
      try 
      {
        vertx.close().toCompletionStage().toCompletableFuture().get();
        LOGGER.info("Closed Vertx instance");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn( "Error while closing Vertx instance: " + e.getMessage(), e );
      }
    }
  }  

//  public KyberResponder getWatcherKey()               { return watcherKey; }   
//  public void     setWatcherKey( KyberResponder key ) { this.watcherKey = key; }
  
  
  // Main method can be used to run without Maven plugin
  public static void main( String[] args )
  {
    VertxOptions options = new VertxOptions().setWorkerPoolSize( 20 ).setEventLoopPoolSize( 4 );
    Vertx        vertx   = Vertx.vertx( options );
 
    MetadataService svc = MetadataService.getInstance();

    try
    {
      svc.init( vertx );
    } 
    catch( Exception e )
    {
      String msg = "Fatal error in MetadataService: " + e.getMessage();
      LOGGER.error( msg, e );
      svc.cleanupResources();
      vertx.close();
      System.exit( 1 );
    }
    
    // Register shutdown hook for graceful shutdown
    Runtime.getRuntime().addShutdownHook( new Thread(() -> 
    {
      LOGGER.info( "Shutdown hook triggered - cleaning up resources" );
      svc.cleanupResources();
    }));

  }
}
