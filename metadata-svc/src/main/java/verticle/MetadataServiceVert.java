package verticle;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import service.MetadataService;

//import java.util.concurrent.TimeUnit;
//import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pulsar.client.api.PulsarClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import controller.PulsarTLSClient;
import svc.model.ChildVerticle;
//import svc.model.ServiceCoreIF;

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

    // Use Future composition for proper async handling
    deployPulsarVerticles()
      .compose(v -> 
      {
        // Add Cassandra deployment if needed
        // return deployCassandraVerticle();
        return Future.succeededFuture();
      })
      .onSuccess(v -> 
      {
        LOGGER.info("MetadataServiceVert started successfully");
        startPromise.complete(); // Single completion point
      })
      .onFailure(throwable -> 
      {
        String msg = "Fatal error during verticle deployment: " + throwable.getMessage();
        LOGGER.error(msg, throwable);
        svc.cleanupResources();
        startPromise.fail(msg); // Single failure point
      });    
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
  
  private Future<Void> deployPulsarVerticles() 
   throws Exception
  {
    DeploymentOptions pulsarOptions = new DeploymentOptions();
    pulsarOptions.setConfig( new JsonObject().put( "worker", true ) );

    List<ChildVerticle> childDeployments  = svc.getDeployedVerticles();
    Promise<Void>       deploymentPromise = Promise.promise();

    workerExecutor.executeBlocking( () -> 
    {
      try 
      {
        // Create individual futures for each verticle deployment
        Future<String> keyConsumerFuture = deployVerticle( new WatcherKeyRequestConsumerVert( vertx, pulsarClient, svc), 
                                                           pulsarOptions, 
                                                           "WatcherKeyRequestConsumerVert"
                                                         );

        Future<String> keyResponseFuture = deployVerticle( new WatcherKeyResponseProducerVert( vertx, pulsarClient), 
                                                           pulsarOptions, 
                                                           "WatcherKeyResponseProducerVert"
                                                         );

        Future<String> certConsumerFuture = deployVerticle( new WatcherCertConsumerVert( vertx, pulsarClient, svc), 
                                                            pulsarOptions, 
                                                            "WatcherCertConsumerVert"
                                                          );

        Future<String> mdConsumerFuture = deployVerticle( new MetadataClientConsumerVert( vertx, pulsarClient), 
                                                          pulsarOptions, 
                                                          "MetadataClientConsumerVert"
                                                        );

        // Wait for all deployments to complete using varargs version
        return Future.all( keyConsumerFuture, keyResponseFuture, 
                           certConsumerFuture, mdConsumerFuture );
      } 
      catch( Exception e ) 
      {
        String msg = "Fatal error during Pulsar verticles deployment";
        LOGGER.error(msg, e);
        svc.cleanupResources();
        throw new RuntimeException(msg, e);
      }
    }).onComplete( ar -> 
      {
        if( ar.succeeded() ) 
        {
          CompositeFuture compositeFuture = (CompositeFuture) ar.result();
            
          // Add deployed verticles to the list with specific indices
          String[] verticleNames = {
                "WatcherKeyRequestConsumerVert",
                "WatcherKeyResponseProducerVert", 
                "WatcherCertConsumerVert",
                "MetadataClientConsumerVert"
          };
            
          for( int i = 0; i < compositeFuture.size(); i++ ) 
          {
            String deploymentId = compositeFuture.resultAt(i);
            childDeployments.add(new ChildVerticle(verticleNames[i], deploymentId));
            LOGGER.info("{} deployed successfully: {}", verticleNames[i], deploymentId);
          }
            
          LOGGER.info("All Pulsar verticles deployed successfully");
          deploymentPromise.complete();
        } 
        else 
        {
          LOGGER.error("Worker execution failed: {}", ar.cause().getMessage());
          deploymentPromise.fail(ar.cause());
        }
    });

    return deploymentPromise.future();
  }
  
  /**
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
        String mdVertId = vertx.deployVerticle( mdConsumerVert, pulsarOptions )
                               .toCompletionStage()
                               .toCompletableFuture().get(30, TimeUnit.SECONDS);
        childDeployments.add( new ChildVerticle( mdConsumerVert.getClass().getName(), mdVertId ));
        LOGGER.info("MetadataConsumerVert deployed successfully: " + mdVertId);
        return ServiceCoreIF.SUCCESS;
      } 
      catch( Exception e ) 
      {
        String msg = "Fatal error during Pulsar verticles deployment";
        LOGGER.error(msg, e);
        cleanup();
        throw new Exception(msg, e);
      }
    }).onComplete(ar -> 
    {
      if( ar.failed() ) 
      {
        LOGGER.error("Worker execution failed: {}", ar.cause().getMessage());
        throw new RuntimeException( ar.cause() );
      }
    });
	return ServiceCoreIF.SUCCESS;
  };
**/
  
  // Helper method to deploy a single verticle
  private Future<String> deployVerticle( AbstractVerticle verticle, DeploymentOptions options, String name ) 
  {
    return vertx.deployVerticle( verticle, options )
                .onFailure(throwable -> LOGGER.error( "Failed to deploy {}: {}", name, throwable.getMessage() ));
  }

/**
  // Helper method to get verticle names for logging
  private String getVerticleName( int index ) 
  {
    switch( index ) 
    {
      case 0: return "WatcherKeyRequestConsumerVert";
      case 1: return "WatcherKeyResponseProducerVert";
      case 2: return "WatcherCertConsumerVert";
      case 3: return "MetadataClientConsumerVert";
      default: return "UnknownVerticle";
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
 **/
  
}