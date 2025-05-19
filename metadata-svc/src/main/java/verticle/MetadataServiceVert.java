package verticle;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import service.MetadataService;

import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

import org.apache.pulsar.client.api.PulsarClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import controller.PulsarTLSClient;
import svc.model.ChildVerticle;

/**
 * Main verticle for the Metadata Service, responsible for deploying
 * and managing child verticles.
 */
public class MetadataServiceVert extends AbstractVerticle
{
  private static final Logger LOGGER = LoggerFactory.getLogger( MetadataServiceVert.class );

//  private List<ChildVerticle> childDeployments = new ArrayList<ChildVerticle>();

  private MetadataService svc            = null;
  private PulsarClient    pulsarClient   = null;
  private WorkerExecutor  workerExecutor = null;

  
  /**
   * Constructs a new MetadataServiceVert.
   *
   * @param svc The metadata service instance
   * @param config Configuration map
   * @param nameSpace Current namespace
   * @throws Exception If initialization fails
   */
  public MetadataServiceVert( MetadataService svc, Map<String, String> config, String nameSpace, PulsarClient pulsarClient )
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

    this.svc          = svc;
    this.pulsarClient = pulsarClient;
  }
  
  @Override
  public void start( Promise<Void> startPromise )
   throws Exception
  {
    workerExecutor = vertx.createSharedWorkerExecutor("msg-handler");

    try
    {
      deployPulsarVerticles();
      deployCassandraVerticle();
      startPromise.complete();
      LOGGER.info("MetadataServiceVert started successfully");
    } 
    catch( Exception e )
    {
      String msg = "Fatal error initializing Pulsar client. Stopping verticle.";
      LOGGER.error( msg );
      cleanup();
      startPromise.fail( msg );
      throw e;
    }
  }

  @Override
  public void stop( Promise<Void> stopPromise ) 
   throws Exception
  {
    LOGGER.info("Stopping MetadataServiceVert");
    cleanup();
    stopPromise.complete();
  }

  /**
   * Vertx.undeploy method performs the following:
   *  1. Locates the verticle instance associated with the provided deployment ID
   *  2. Calls the verticle's stop() method (which executes any cleanup code defined there)
   *  3. Removes the verticle from the Vert.x deployment registry
   *  4. Releases any resources associated with that verticle
   */
  private void cleanup()
  {
    // Close worker executor
    if( workerExecutor != null ) 
    {
      try 
      {
        workerExecutor.close();
        LOGGER.info("Closed worker executor");
      } 
      catch( Exception e ) 
      {
        LOGGER.warn("Error while closing worker executor: " + e.getMessage(), e);
      }
    }
    
    // Close Pulsar client
    if( pulsarClient != null )
    {
      try
      {
        pulsarClient.close();
        LOGGER.info("Closed Pulsar client");
      }
      catch( Exception e ) 
      {
        LOGGER.warn("Error while closing Pulsar client: " + e.getMessage(), e);
      }
    }
    
    LOGGER.info("MetadataServiceVert cleanup completed");
  }
  
  private void deployPulsarVerticles() 
   throws Exception
  {
    DeploymentOptions pulsarOptions = new DeploymentOptions();
    pulsarOptions.setConfig( new JsonObject().put( "worker", true ) );

    List<ChildVerticle> childDeployments = svc.getDeployedVerticles();
    
    try 
    {
      // Watcher Key Request Consumer
      WatcherKeyRequestConsumerVert  keyConsumerVert = new WatcherKeyRequestConsumerVert( pulsarClient, svc  );
      String keyConsumerVertId = vertx.deployVerticle( keyConsumerVert, pulsarOptions )
                                      .toCompletionStage()
                                      .toCompletableFuture().get( 30, TimeUnit.SECONDS);
      childDeployments.add( new ChildVerticle( keyConsumerVert.getClass().getName(), keyConsumerVertId ));
      LOGGER.info("WatcherConsumerVert deployed successfully: " + keyConsumerVertId);

      // Watcher Producer
      WatcherKeyResponseProducerVert keyResponseVert   = new WatcherKeyResponseProducerVert( pulsarClient );
      String keyResponseVertId = vertx.deployVerticle( keyResponseVert, pulsarOptions )
                                      .toCompletionStage()
                                      .toCompletableFuture().get(30, TimeUnit.SECONDS);
      childDeployments.add( new ChildVerticle( keyResponseVert.getClass().getName(), keyResponseVertId ));
      LOGGER.info("WatcherPublisherVert deployed successfully: " + keyResponseVertId);

      // Watcher Consumer
      WatcherCertConsumerVert certConsumerVert = new WatcherCertConsumerVert( pulsarClient, svc );
      String certConsumerVertId = vertx.deployVerticle( certConsumerVert, pulsarOptions )
                                          .toCompletionStage()
                                          .toCompletableFuture().get(30, TimeUnit.SECONDS);
      childDeployments.add( new ChildVerticle( certConsumerVert.getClass().getName(), certConsumerVertId ));
      LOGGER.info("WatcherConsumerVert deployed successfully: " + certConsumerVertId);

      //Metadata Client Consumer
      MetadataClientConsumerVert mdConsumerVert = new MetadataClientConsumerVert( pulsarClient );
      String               mdVertId       = vertx.deployVerticle( mdConsumerVert, pulsarOptions )
                                                 .toCompletionStage()
                                                 .toCompletableFuture().get(30, TimeUnit.SECONDS);
      childDeployments.add( new ChildVerticle( mdConsumerVert.getClass().getName(), mdVertId ));
      LOGGER.info("MetadataConsumerVert deployed successfully: " + mdVertId);
    
      LOGGER.info( "Pulsar verticles deployed." );
    } 
    catch( Exception e ) 
    {
      String msg = "Fatal error during Pulsar verticles deployment";
      LOGGER.error(msg, e);
      cleanup();
      throw new Exception(msg, e);
    }
  }
  
  
  private void deployCassandraVerticle()
   throws Exception
  {
    DeploymentOptions options = new DeploymentOptions();
    options.setConfig( new JsonObject().put( "worker", true ) );

    List<ChildVerticle> childDeployments = svc.getDeployedVerticles();

    try
    {
      CassandraVert  cassandraVert   = new CassandraVert();
      String         cassandraVertId = vertx.deployVerticle( cassandraVert, options )
                                            .toCompletionStage()
                                            .toCompletableFuture().get(30, TimeUnit.SECONDS);
      childDeployments.add( new ChildVerticle( cassandraVert.getClass().getName(), cassandraVertId ));
      LOGGER.info("CassandraVert deployed successfully: " + cassandraVertId );

      LOGGER.info( "Cassandra verticle deployed." );
    }
    catch( Exception e )
    {
      String msg = "Failed to initialize Cassandra";
      LOGGER.error(msg, e);
      cleanup();
      throw new Exception(msg, e);
    }
  }  
  
}